package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.building.BuildingInstance;
import com.wenzai.neosim.building.ConstructionEngine;
import com.wenzai.neosim.building.InventoryManager;
import com.wenzai.neosim.building.PlacementSupport;
import com.wenzai.neosim.compat.attached.AttachedBlockTable;
import com.wenzai.neosim.compat.sable.PhysicsWorld;
import com.wenzai.neosim.schematic.BlueprintPlacement;
import com.wenzai.neosim.schematic.LightweightBlockContainer;
import com.wenzai.neosim.schematic.MaterialCalculator;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SchematicRegistry;
import com.wenzai.neosim.schematic.SpecialMarker;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.SimData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

// 重建任务：不需要 NPC，按蓝图把"应为方块却成了空气"的格子补回去
// - 与控制箱同一套落地链（BlueprintPlacement），保证与建造时位置/朝向一致
// - 与建筑模盒同一套放置收尾（PlacementSupport）：依附朝向、连接性方块、双方块、双开门
// - 两轮：先实心方块，后依附 / 连接性方块；每轮扫完还有被推迟的方块就回卷重试
// - 材料从重建盒相邻箱子取；创造模式（mode 2）完全不耗材
public class RebuildTask
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// 区块还没加载好时的重试间隔（tick）：小建筑首轮可能一 tick 就跑完，别急着判定完成
	private static final int CHUNK_RETRY_TICKS = 20;

	// 旧记录（缺落地几何）定向探测的采样格数上限；蓝图体积超过它时按 stride 抽样
	private static final int PROBE_SAMPLE_CELLS = 8000;

	// 依附表兜底：一轮扫完仍有被推迟的方块（支撑 / 连接还没就位）时的回卷重试上限
	private static final int MAX_RETRY_ROUNDS = 4;

	public enum State
	{
		RUNNING,
		WAITING,
		UNBOUND,

		// 重建完成，任务终止（盒子方块保留，不再工作）
		COMPLETE;

		public static State valueOfSafe(String name)
		{
			for (State s : values())
			{
				if (s.name().equals(name)) return s;
			}
			return RUNNING;
		}
	}

	private final ServerLevel level;
	private final String cityName;
	private RebuildBoxPersistence.RebuildRecord record;
	private final BlockPos boxPos;
	private final BlockPos controlBoxPos;
	private final BlockPos originPos;

	private State state = State.RUNNING;

	// 解析后的蓝图与落地几何
	private boolean prepared;
	private boolean chunksRegistered;
	private boolean destroyed;
	private SchematicData schematic;
	private LightweightBlockContainer container;
	private BlueprintPlacement placement;
	private int sx, sy, sz, totalVolume;

	// 扫描游标：phase 0 = 实心轮，phase 1 = 依附 / 连接性方块轮
	private int cursor;
	private int phase;

	// 本轮结果：放置数 / 被推迟数 / 命中该轮的格数 / 读到已加载区块的格数
	private int placedInRound;
	private int deferredInRound;
	private int phaseCellsInRound;
	private int loadedInRound;

	// 当前轮回卷次数；一个完整循环（第一轮 + 第二轮）里是否放置过
	private int retryRound;
	private boolean placedInCycle;

	// 区块未加载的等待剩余 tick
	private int retryTicks;

	// 本轮累计应付费用（每 tick 只落盘一次）
	private double creditDue;

	// 解析/探测出的落地几何（写回记录用）
	private String geoRotation = "NONE";
	private String geoMirror = "NONE";
	private String geoFacing;

	public RebuildTask(ServerLevel level, String cityName, RebuildBoxPersistence.RebuildRecord record)
	{
		this.level = level;
		this.cityName = cityName;
		this.record = record;
		this.boxPos = record.boxPos();
		this.controlBoxPos = record.controlBoxPos();
		this.originPos = record.originPos();
		this.state = State.valueOfSafe(record.state());
	}

	public BlockPos boxPos()
	{
		return boxPos;
	}

	public String cityName()
	{
		return cityName;
	}

	public RebuildBoxPersistence.RebuildRecord record()
	{
		return record;
	}

	public State getState()
	{
		return state;
	}

	// 服务器停止前的收尾
	public void onBoxDestroyed()
	{
		destroyed = true;
		RebuildChunkLoader.release(level, boxPos);
	}

	public void tick()
	{
		if (destroyed) return;

		if (!prepare())
		{
			return;
		}

		// 同栋建筑正在建造：让行，避免两个系统抢同一格
		for (BuildingInstance b : ConstructionEngine.getActiveBuildings())
		{
			if (b.getControlBoxPos() != null && b.getControlBoxPos().equals(originPos)) return;
		}

		// 区块尚未加载完（小建筑首轮可能一 tick 跑完）：稍后再试，别急着判定完成
		if (retryTicks > 0)
		{
			retryTicks--;
			return;
		}

		byte mode = ModSavedData.get(level).getMode();
		List<ChestBlockEntity> chests = null;

		// 扫描与放置共用一个每 tick 上限（rebuildPerTick）
		int scanBudget = Config.REBUILD_PER_TICK.get();
		int placeBudget = scanBudget;
		int scanned = 0;
		int placed = 0;
		boolean materialShort = false;

		Map<BlockPos, SpecialMarker> markers = schematic.getSpecialMarkers();

		while (scanned < scanBudget && placed < placeBudget)
		{
			if (cursor >= totalVolume)
			{
				// 命中该轮却没有一格区块已加载：回到本轮起点，等区块加载好后整轮重扫
				if (phaseCellsInRound > 0 && loadedInRound == 0)
				{
					retryTicks = CHUNK_RETRY_TICKS;
					cursor = 0;
					placedInRound = 0;
					deferredInRound = 0;
					phaseCellsInRound = 0;
					loadedInRound = 0;
					return;
				}

				// 本轮结算
				boolean placedRound = placedInRound > 0;
				boolean deferred = deferredInRound > 0;
				placedInRound = 0;
				deferredInRound = 0;
				phaseCellsInRound = 0;
				loadedInRound = 0;

				// 有被推迟的方块且本轮确实放过：回卷重试（支撑 / 连接可能刚刚才就位）
				if (deferred && placedRound && retryRound < MAX_RETRY_ROUNDS)
				{
					retryRound++;
					cursor = 0;
					continue;
				}

				// 第一轮（实心）扫完：进入第二轮（依附 / 连接性方块）
				if (phase == 0)
				{
					phase = 1;
					cursor = 0;
					retryRound = 0;
					continue;
				}

				// 第二轮扫完：整个循环里有过进展就再来一轮，否则判定无事可做
				phase = 0;
				cursor = 0;
				retryRound = 0;
				if (placedInCycle)
				{
					placedInCycle = false;
					continue;
				}
				setState(State.COMPLETE);
				return;
			}

			int idx = cursor++;
			scanned++;

			int layer = idx / (sx * sz);
			int depth = (idx / sx) % sz;
			int width = idx % sx;

			// 特殊标记优先：生活点等标记不落方块
			SpecialMarker marker = markers.isEmpty() ? null
					: markers.get(new BlockPos(width, layer, depth));
			BlockState desired;
			if (marker != null)
			{
				desired = marker.toBlockState();
				if (desired == null) continue;
			}
			else
			{
				desired = container.get(width, layer, depth);
				if (desired.isAir()) continue;
			}

			// 两轮：先实心，后依附 / 连接性方块
			if (MaterialCalculator.isAttachedBlock(desired) != (phase == 1)) continue;

			BlockPos world = placement.pos(width, layer, depth);
			if (world.equals(boxPos)) continue;
			phaseCellsInRound++;

			// 只处理已加载区块（强制加载由 RebuildChunkLoader 负责）
			if (!level.hasChunkAt(world)) continue;
			loadedInRound++;

			// 只补"应为方块、现为空气"的格子；已有任何方块（含玩家手改）一律尊重现状
			if (!PhysicsWorld.getBlockState(level, world).isAir()) continue;

			BlockState toPlace = placement.state(desired);
			if (toPlace == null) continue;

			// 依附性方块：朝向贴着实际支撑；支撑没就位则推迟，下一轮再试（不白扣材料）
			if (MaterialCalculator.isAttachedBlock(desired))
			{
				toPlace = PlacementSupport.fixAttachedFacing(level, world, toPlace);
				if (toPlace == null)
				{
					deferredInRound++;
					continue;
				}
			}

			// 连接性方块：放置时按实际相邻方块重算连接
			if (PlacementSupport.isConnective(toPlace))
			{
				toPlace = PlacementSupport.fixConnectiveConnections(level, world, toPlace);
			}

			// 兜底预检：表未覆盖的模组依附方块也能正确推迟，且不白扣一份材料
			if (AttachedBlockTable.precheck(toPlace) && !PlacementSupport.canSurviveAt(level, world, toPlace))
			{
				deferredInRound++;
				continue;
			}

			// 材料：创造模式（2）完全不耗材，与建筑模盒一致
			if (mode != 2 && MaterialCalculator.requiresMaterial(desired, mode))
			{
				if (chests == null)
				{
					chests = InventoryManager.findNearbyChests(level, boxPos);
				}
				Item item = desired.getBlock().asItem();
				if (!takeOne(chests, item))
				{
					materialShort = true;
					break;
				}
			}

			PhysicsWorld.setBlock(level, world, toPlace, Block.UPDATE_ALL);

			// 双箱合并：纯 setBlock 不会触发原版合并逻辑
			PlacementSupport.mergeDoubleChest(level, world, toPlace);

			// 双方块补齐：门补另一半并配对双开门；床按容器相邻床格补另一半
			if (toPlace.getBlock() instanceof DoorBlock)
			{
				PlacementSupport.completeDoor(level, world, toPlace);
			}
			else if (toPlace.getBlock() instanceof BedBlock)
			{
				PlacementSupport.completeBed(level, world, toPlace, adjacentBedWorld(width, layer, depth));
			}

			if (mode != 2)
			{
				creditDue += Config.REBUILD_CREDIT_PER_BLOCK.get();
			}

			placed++;
			placedInRound++;
			placedInCycle = true;
		}

		if (placed > 0)
		{
			setState(State.RUNNING);
			flushCredit();
		}
		else if (materialShort)
		{
			// 缺料：保持任务存活，等箱子补料后下一轮继续（不终止）
			setState(State.WAITING);
		}
		else
		{
			setState(State.RUNNING);
		}
	}

	// 解析蓝图与落地几何（含旧记录的定向探测）；失败则保持 UNBOUND 等待下次重试
	private boolean prepare()
	{
		if (prepared) return true;

		if (!(level.getBlockState(controlBoxPos).getBlock() instanceof ControlBox))
		{
			setState(State.UNBOUND);
			return false;
		}

		if (schematic == null)
		{
			SchematicData sd = SchematicRegistry.getInstance().get(record.schematicName());
			if (sd == null) return false;
			schematic = sd;
			container = sd.getBlockContainer();
			sx = container.getSizeX();
			sy = container.getSizeY();
			sz = container.getSizeZ();
			totalVolume = container.getTotalVolume();
			if (sx <= 0 || sy <= 0 || sz <= 0) return false;
		}

		ensureChunks();

		if (placement == null)
		{
			placement = resolvePlacement();
			if (placement == null)
			{
				setState(State.UNBOUND);
				return false;
			}

			// 探测出的几何写回记录，重启后不再探测
			record = record.withGeometry(geoRotation, geoMirror, geoFacing);
			RebuildBoxPersistence.updateRecord(level, cityName, record);
		}

		prepared = true;
		setState(State.RUNNING);
		return true;
	}

	// 用粗略包围盒强制加载建筑区块（覆盖所有旋转/镜像/朝向可能落点）
	private void ensureChunks()
	{
		if (chunksRegistered) return;
		chunksRegistered = true;
		int r = Math.max(sx, sz) + 2;
		BlockPos a = originPos.offset(-r, -sy - 2, -r);
		BlockPos b = originPos.offset(r, sy + 2, r);

		// 连同重建盒与控制箱所在区块一起纳入，保证下面 getBlockState 不会触发隐式加载
		BlockPos min = new BlockPos(
				Math.min(a.getX(), Math.min(b.getX(), Math.min(boxPos.getX(), controlBoxPos.getX()))),
				Math.min(a.getY(), Math.min(b.getY(), Math.min(boxPos.getY(), controlBoxPos.getY()))),
				Math.min(a.getZ(), Math.min(b.getZ(), Math.min(boxPos.getZ(), controlBoxPos.getZ()))));
		BlockPos max = new BlockPos(
				Math.max(a.getX(), Math.max(b.getX(), Math.max(boxPos.getX(), controlBoxPos.getX()))),
				Math.max(a.getY(), Math.max(b.getY(), Math.max(boxPos.getY(), controlBoxPos.getY()))),
				Math.max(a.getZ(), Math.max(b.getZ(), Math.max(boxPos.getZ(), controlBoxPos.getZ()))));
		RebuildChunkLoader.register(level, boxPos, min, max);
	}

	private BlueprintPlacement resolvePlacement()
	{
		Rotation rotation = parseRotation(record.rotation());
		Mirror mirror = parseMirror(record.mirror());
		Direction facing = parseFacing(record.facing());

		geoRotation = rotation.name();
		geoMirror = mirror.name();
		geoFacing = facing != null ? facing.name() : null;

		if (facing != null)
		{
			return new BlueprintPlacement(schematic.frame(), sx, sz, originPos, facing, mirror, rotation);
		}

		// 旧记录无朝向：枚举 4 朝向 × 2 镜像 × 4 旋转，按"蓝图非空位在世界上有多少仍是同种方块"打分
		BlueprintPlacement best = null;
		int bestScore = -1;
		Direction bestFacing = null;
		Mirror bestMirror = Mirror.NONE;
		Rotation bestRotation = Rotation.NONE;
		int stride = Math.max(1, totalVolume / PROBE_SAMPLE_CELLS);
		for (Direction f : new Direction[]{Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST})
		{
			for (Mirror m : new Mirror[]{Mirror.NONE, Mirror.LEFT_RIGHT})
			{
				for (Rotation rt : Rotation.values())
				{
					BlueprintPlacement p = new BlueprintPlacement(schematic.frame(), sx, sz, originPos, f, m, rt);
					int score = scorePlacement(p, stride);
					if (score > bestScore)
					{
						bestScore = score;
						best = p;
						bestFacing = f;
						bestMirror = m;
						bestRotation = rt;
					}
				}
			}
		}
		geoFacing = bestFacing != null ? bestFacing.name() : null;
		geoMirror = bestMirror.name();
		geoRotation = bestRotation.name();
		if (bestScore <= 0)
		{
			LOGGER.warn("NeoSim-RebuildTask: cannot infer geometry for '{}' at {} — rebuild idle",
					record.schematicName(), boxPos);
			return null;
		}
		LOGGER.info("NeoSim-RebuildTask: inferred geometry for '{}' at {} (score {})",
				record.schematicName(), boxPos, bestScore);
		return best;
	}

	private int scorePlacement(BlueprintPlacement p, int stride)
	{
		int score = 0;
		int tested = 0;
		for (int idx = 0; idx < totalVolume; idx += stride)
		{
			int layer = idx / (sx * sz);
			int depth = (idx / sx) % sz;
			int width = idx % sx;
			BlockState desired = container.get(width, layer, depth);
			if (desired.isAir()) continue;
			BlockPos world = p.pos(width, layer, depth);
			if (!level.hasChunkAt(world)) continue;
			tested++;
			BlockState current = level.getBlockState(world);
			if (!current.isAir() && current.getBlock() == p.state(desired).getBlock())
			{
				score++;
			}
		}
		return tested == 0 ? -1 : score;
	}

	// 在容器局部坐标中查找相邻的床格，返回其世界坐标（用于补齐床的另一半）
	@Nullable
	private BlockPos adjacentBedWorld(int width, int layer, int depth)
	{
		int[][] dirs = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
		for (int[] d : dirs)
		{
			int nx = width + d[0];
			int nz = depth + d[1];
			if (nx >= 0 && nx < sx && nz >= 0 && nz < sz)
			{
				if (container.get(nx, layer, nz).getBlock() instanceof BedBlock)
				{
					return placement.pos(nx, layer, nz);
				}
			}
		}
		return null;
	}

	private void flushCredit()
	{
		if (creditDue <= 0) return;
		double amount = creditDue;
		creditDue = 0;
		try
		{
			SimData.CityData data = SimData.CityData.read(level, cityName);
			double now = Math.max(0.0, data.credit() - amount);
			SimData.CityData.write(level, cityName, data.withCredit(now));
			ModSavedData.get(level).syncCityToClients(level, cityName);
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-RebuildTask: credit deduction failed", e);
		}
	}

	private void setState(State s)
	{
		if (state == s && s.name().equals(record.state())) return;
		state = s;
		record = record.withState(s.name());
	}

	private static Rotation parseRotation(String s)
	{
		try
		{
			return Rotation.valueOf(s);
		}
		catch (Exception e)
		{
			return Rotation.NONE;
		}
	}

	private static Mirror parseMirror(String s)
	{
		try
		{
			return Mirror.valueOf(s);
		}
		catch (Exception e)
		{
			return Mirror.NONE;
		}
	}

	private static Direction parseFacing(String s)
	{
		if (s == null || s.isEmpty()) return null;
		try
		{
			return Direction.valueOf(s);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private static boolean takeOne(List<ChestBlockEntity> chests, Item item)
	{
		if (chests.isEmpty()) return false;
		if (InventoryManager.countItems(chests, item) <= 0) return false;
		return InventoryManager.extractItem(chests, item, 1) > 0;
	}
}

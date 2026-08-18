package com.wenzai.neosim.building;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.block.*;
import com.wenzai.neosim.life.LifeSystem;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.NpcGoals;
import com.wenzai.neosim.schematic.*;
import com.wenzai.neosim.storage.FileCreater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConstructionTask
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final int BASE_DELAY = 2000;

	// 建造者等级上限
	private static final float MAX_LEVEL = 10.0f;

	// 等待材料时3秒检查一次
	private static final int WAITING_CHECK_DELAY = 3000;
	private static final int SEARCH_RADIUS = 5;

	// C6b：建造跳过循环每 tick 扫描上限（大段空气/标记区不得单 tick 连续扫描数万格）
	private static final int MAX_SCAN_PER_TICK = 64;

	// 抬手时长
	private static final int RAISE_ANIM_MS = 400;

	// 放手时长
	private static final int LOWER_ANIM_MS = 400;

	private final BuildingInstance building;
	private final ServerLevel level;
	private final SchematicData schematic;

	private BuildingInstance.BuildState currentState = BuildingInstance.BuildState.IDLE;
	private int resumeIndex;
	private long lastTickTime;
	private float builderLevel = 1.0f;
	private int buildDelay = BASE_DELAY;
	private boolean paused;

	// 两轮建造
	private boolean phaseTwo;

	// 抬手
	private long animStartTime;
	private Entity builderNpc;

	private long lastWaitCheck;

	// 工人不在模盒附近的累计tick
	private int workerMissingTicks;

	// 最近一次已通知的缺料
	private Item lastNotifiedMissingItem;

	// 当前缺料
	private Item lastMissingMaterial;

	private List<ChestBlockEntity> nearbyChests;

	public ConstructionTask(BuildingInstance building, ServerLevel level)
	{
		this.building = building;
		this.level = level;
		this.schematic = building.getSchematic();
		this.resumeIndex = building.getBuildProgress();
		this.phaseTwo = building.isPhaseTwo();
		this.paused = building.isPaused();
		updateBuildSpeed(builderLevel);
	}

	// 分配NPC后调用
	public void assignWorker()
	{
		if (currentState == BuildingInstance.BuildState.IDLE)
		{
			currentState = BuildingInstance.BuildState.WORKER_ASSIGNED;
			building.setState(currentState);
			LOGGER.info("NeoSim-ConstructionTask: worker assigned — {}", building.getSchematicName());
		}
	}

	// NPC到达后调用
	public void onWorkerArrived()
	{
		if (currentState == BuildingInstance.BuildState.WORKER_ASSIGNED
				|| currentState == BuildingInstance.BuildState.WAITING_FOR_WORKER)
		{
			currentState = BuildingInstance.BuildState.LOADING_BLUEPRINT;
			building.setState(currentState);
			nearbyChests = findNearbyChestsNow();
			LOGGER.info("NeoSim-ConstructionTask: worker arrived, loading blueprint — {}",
					building.getSchematicName());
		}
	}

	// 每tick调用
	public void tick()
	{
		if (paused) return;
		if (currentState == BuildingInstance.BuildState.COMPLETE)
		{
			return;
		}

		// 区块窗口随建造进度滚动（当前层 ±1），整栋不再数天常驻全部区块
		BuildingChunkLoader.updateWindow(building, level);

		// 防删改：蓝图原点越出本维度建造高度则安全中止
		if (building.getControlBoxPos() != null && schematic != null)
		{
			int originY = building.getControlBoxPos().getY();
			if (originY < level.getMinBuildHeight()
					|| originY >= level.getMaxBuildHeight()
					|| originY + schematic.getSizeY() > level.getMaxBuildHeight())
			{
				abortConstruction();
				return;
			}
		}

		// 夜晚
		if (isNightTime())
		{
			if (isWorkerOnShift())
			{
				goOffWork();
			}
			else
			{
				restNewWorker();
			}
			return;
		}
		ensureWorkerAtSite();

		if (!hasWorker())
		{
			if (currentState != BuildingInstance.BuildState.WAITING_FOR_WORKER)
			{
				currentState = BuildingInstance.BuildState.WAITING_FOR_WORKER;
				building.setState(currentState);
				setBuilderAnim(0.0F);
				clearBuilderHand();
				LOGGER.info("NeoSim-ConstructionTask: waiting for worker — {}",
						building.getSchematicName());
			}
			return;
		}
		if (currentState == BuildingInstance.BuildState.WAITING_FOR_WORKER)
		{
			// 工人已分配：等待NPC
			currentState = BuildingInstance.BuildState.WORKER_ASSIGNED;
			building.setState(currentState);
			return;
		}
		if (currentState == BuildingInstance.BuildState.WORKER_ASSIGNED)
		{
			// 等NPC到达模盒正上方
			resolveBuilderNpc();
			BlockPos box = building.getConstructorPos();
			if (box == null) box = building.getControlBoxPos();
			if (builderNpc != null && NpcGoals.MoveToSiteGoal.isAboveSite(builderNpc, box))
			{
				workerMissingTicks = 0;
				onWorkerArrived();
			}
			else if (builderNpc == null)
			{
				// 累计超时后处理
				workerMissingTicks++;
				if (workerMissingTicks >= 200)
				{
					workerMissingTicks = 0;
					String workerName = NeoSim.WORKER_MAP.get(box);
					if (workerName != null && !workerName.isEmpty()
							&& !workerExistsInLevel(workerName))
					{
						tryRestoreWorker();
					}
				}
			}
			else
			{
				// 工人在正常寻路中，重置计时
				workerMissingTicks = 0;
			}
			return;
		}
		if (currentState == BuildingInstance.BuildState.IDLE)
		{
			return;
		}

		// 加载蓝图
		if (currentState == BuildingInstance.BuildState.LOADING_BLUEPRINT)
		{
			currentState = BuildingInstance.BuildState.BUILDING;
			building.setState(currentState);
			LOGGER.info("NeoSim-ConstructionTask: building started — {}", building.getSchematicName());
		}

		// 等待材料
		if (currentState == BuildingInstance.BuildState.WAITING_FOR_RESOURCES)
		{
			long nowMs = System.currentTimeMillis();
			if (nowMs - lastWaitCheck < WAITING_CHECK_DELAY) return;
			lastWaitCheck = nowMs;

			Item needed = getNextBlockItem();
			if (needed == null || hasMaterial(needed))
			{
				currentState = BuildingInstance.BuildState.BUILDING;
				building.setState(currentState);
				lastNotifiedMissingItem = null;
				lastMissingMaterial = null;

				// 材料补足后抬手
				animStartTime = System.currentTimeMillis();
				LOGGER.info("NeoSim-ConstructionTask: resources replenished, resuming");
			}
			else
			{
				// 继续等待，缺料变化时聊天栏提醒

				// 手臂放下
				setBuilderAnim(0.0F);
				clearBuilderHand();
				notifyMissingMaterial(needed);
				return;
			}
		}

		// 建造中：实体在场但不在模盒正上方时，等就位
		resolveBuilderNpc();
		if (builderNpc != null && builderNpc.getPregnancyStage() <= 0.0F)
		{
			BlockPos box = building.getConstructorPos();
			if (box == null) box = building.getControlBoxPos();
			if (box != null && !NpcGoals.MoveToSiteGoal.isAboveSite(builderNpc, box))
			{
				setBuilderAnim(0.0F);
				clearBuilderHand();
				return;
			}
		}

		// 速度控制+抬手
		long now = System.currentTimeMillis();
		long elapsed = now - animStartTime;
		if (currentMode() != 2 && elapsed < buildDelay)
		{
			if (elapsed < LOWER_ANIM_MS)
			{
				setBuilderAnim(1.0F - elapsed / (float) LOWER_ANIM_MS);
			}
			else
			{
				long raiseStart = buildDelay - RAISE_ANIM_MS;
				if (elapsed < raiseStart)
				{
					// 手放下
					setBuilderAnim(0.0F);
				}
				else
				{
					// 抬手
					setBuilderAnim((elapsed - raiseStart) / (float) RAISE_ANIM_MS);
				}
			}
			return;
		}
		animStartTime = now;
		setBuilderAnim(1.0F);

		// 建造循环
		LightweightBlockContainer container = schematic.getBlockContainer();
		int sx = container.getSizeX();
		int sz = container.getSizeZ();
		int totalVolume = container.getTotalVolume();
		Map<BlockPos, SpecialMarker> specialMarkers = schematic.getSpecialMarkers();
		BlockPos.MutableBlockPos markerScanPos = new BlockPos.MutableBlockPos();

		// C6：建造 flag 按模式降级（创造模式不触发全量光照/邻居更新）
		int placeFlags = currentMode() == 2 ? Block.UPDATE_CLIENTS : Block.UPDATE_ALL;

		// C6b/C7：跳过循环每 tick 上限——大段空气/标记区不得单 tick 连续扫描数万格
		int scanned = 0;
		while (resumeIndex < totalVolume)
		{
			if (++scanned > MAX_SCAN_PER_TICK)
			{
				return;
			}
			int layer = resumeIndex / (sx * sz);
			int depth = (resumeIndex / sx) % sz;
			int width = resumeIndex % sx;

			BlockState desired = container.get(width, layer, depth);

			// 特殊标记
			SpecialMarker marker = specialMarkers != null
					? specialMarkers.get(markerScanPos.set(width, layer, depth)) : null;
			if (marker != null)
			{
				if (!phaseTwo)
				{
					placeMarkerBlock(marker, width, layer, depth);
				}
				resumeIndex++;
				building.setBuildProgress(resumeIndex);
				continue;
			}

			if (desired.isAir())
			{
				resumeIndex++;
				continue;
			}

			if (MaterialCalculator.isAttachedBlock(desired) != phaseTwo)
			{
				resumeIndex++;
				continue;
			}

			BlockPos worldPos = building.blueprintToWorld(width, layer, depth);
			BlockState current = level.getBlockState(worldPos);

			// 应用镜像/旋转
			BlockState toPlace = CoordTransform.transformState(desired, building.getFacing());
			if (building.getMirror() != net.minecraft.world.level.block.Mirror.NONE)
			{
				toPlace = toPlace.mirror(building.getMirror());
			}
			if (building.getRotation() != net.minecraft.world.level.block.Rotation.NONE)
			{
				toPlace = toPlace.rotate(building.getRotation());
			}

			// 依附性方块朝向贴着实际支撑
			if (MaterialCalculator.isAttachedBlock(desired))
			{
				toPlace = fixAttachedFacing(worldPos, toPlace);
				if (toPlace == null)
				{
					LOGGER.warn("NeoSim-ConstructionTask: skip '{}' at {} — no support",
							desired.getBlock().getDescriptionId(), worldPos);
					resumeIndex++;
					continue;
				}
			}

			// 连接性方块：放置时按实际相邻方块重算连接
			if (isConnectiveBlock(toPlace))
			{
				toPlace = fixConnectiveConnections(worldPos, toPlace);
			}

			if (current.equals(toPlace))
			{
				resumeIndex++;
				continue;
			}

			// 双方块：目标位置已有同类型方块，跳过避免覆盖
			if ((toPlace.getBlock() instanceof DoorBlock || toPlace.getBlock() instanceof BedBlock)
					&& current.getBlock() == toPlace.getBlock())
			{
				resumeIndex++;
				continue;
			}

			// 清除现有方块（创造模式不掉落，避免掉落物风暴）
			if (!current.isAir())
			{
				level.destroyBlock(worldPos, currentMode() != 2);
			}

			// 材料检查与消耗
			if (MaterialCalculator.requiresMaterial(desired, currentMode()))
			{
				Item item = desired.getBlock().asItem();
				if (!hasMaterial(item))
				{
					currentState = BuildingInstance.BuildState.WAITING_FOR_RESOURCES;
					building.setState(currentState);
					notifyMissingMaterial(item);
					LOGGER.info("NeoSim-ConstructionTask: waiting for {}", item.getDescription().getString());

					return;
				}
				extractMaterial(item);
			}

			// 渲染NPC手持要放置的方块（C6：仅物品变化时更新，避免每块触发装备同步）
			resolveBuilderNpc();
			if (builderNpc != null)
			{
				net.minecraft.world.item.ItemStack held = builderNpc.getItemInHand(
						net.minecraft.world.InteractionHand.MAIN_HAND);
				net.minecraft.world.item.Item want = toPlace.getBlock().asItem();
				if (held.isEmpty() || held.getItem() != want)
				{
					builderNpc.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
							new net.minecraft.world.item.ItemStack(want));
				}
			}

			// 放置方块（C6：创造模式降级 flag）
			level.setBlock(worldPos, toPlace, placeFlags);

			// 特殊方块放置后生效
			activatePlacedSpecial(toPlace, worldPos);

			// 双方块补齐：自动补另一半
			if (toPlace.getBlock() instanceof DoorBlock)
			{
				net.minecraft.world.level.block.state.properties.DoubleBlockHalf half =
						toPlace.getValue(DoorBlock.HALF);
				if (half == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
				{
					// 放下半格，补上半格
					BlockState upper = toPlace.setValue(DoorBlock.HALF,
							net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
					if (level.getBlockState(worldPos.above()).isAir())
					{
						level.setBlock(worldPos.above(), upper, placeFlags);
					}
				}
				else if (level.getBlockState(worldPos.below()).isAir())
				{
					// 上半格先：补下半格
					BlockState lower = toPlace.setValue(DoorBlock.HALF,
							net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
					level.setBlock(worldPos.below(), lower, placeFlags);
				}

				// 双开门
				fixDoubleDoor(worldPos);
				LOGGER.debug("NeoSim-ConstructionTask: door placed at {} → {}", worldPos,
						level.getBlockState(worldPos));
			}
			else if (toPlace.getBlock() instanceof BedBlock)
			{
				net.minecraft.world.level.block.state.properties.BedPart part =
						toPlace.getValue(BedBlock.PART);

				// 用容器相邻的床格定位另一半
				BlockPos other = findAdjacentBedCellWorld(width, layer, depth, container, sx, sz);
				if (other == null)
				{
					Direction bedFacing = toPlace.getValue(BedBlock.FACING);
					other = part == net.minecraft.world.level.block.state.properties.BedPart.HEAD
							? worldPos.relative(bedFacing.getOpposite())
							: worldPos.relative(bedFacing);
				}
				BlockState otherState = toPlace.setValue(BedBlock.PART,
						part == net.minecraft.world.level.block.state.properties.BedPart.HEAD
								? net.minecraft.world.level.block.state.properties.BedPart.FOOT
								: net.minecraft.world.level.block.state.properties.BedPart.HEAD);
				if (!level.getBlockState(other).equals(otherState))
				{
					level.setBlock(other, otherState, placeFlags);
				}
			}

			// 放置音效（C6：创造模式静音，普通模式随机音高防单调）
			if (currentMode() != 2)
			{
				level.playSound(null, worldPos,
						desired.getSoundType().getPlaceSound(),
						SoundSource.BLOCKS, 1.0F, 0.8F + level.random.nextFloat() * 0.4F);
			}
			resumeIndex++;
			building.setBuildProgress(resumeIndex);

			// 技能成长与信用点扣除
			int b4 = (int) Math.floor(builderLevel);
			if (builderLevel < MAX_LEVEL)
			{
				builderLevel += 0.001f / b4;
			}
			int aft = (int) Math.floor(builderLevel);
			if (aft > b4)
			{
				resolveBuilderNpc();
				if (builderNpc != null)
				{
					builderNpc.setJobArchitect((byte) Math.min(aft, (int) MAX_LEVEL));
					// 升级写盘走 D4 合并窗口（脏标记 + 周期 flush）
					builderNpc.syncToJson();
				}
			}

			updateBuildSpeed(builderLevel);

			// 一次tick只放一个实心方块
			break;
		}

		if (resumeIndex >= totalVolume && !phaseTwo)
		{
			phaseTwo = true;
			resumeIndex = 0;
			building.setPhaseTwo(true);
			building.setBuildProgress(0);
			setBuilderAnim(0.0F);
			LOGGER.info("NeoSim-ConstructionTask: phase 1 done, building attached blocks — {}",
					building.getSchematicName());
		}

		// 完工
		if (resumeIndex >= totalVolume && phaseTwo)
		{
			currentState = BuildingInstance.BuildState.COMPLETE;
			building.setState(currentState);
			building.setBuildingComplete(true);
			building.setAssignedBuilder(null);
			building.setBuilderName(null);
			setBuilderAnim(0.0F);
			clearBuilderHand();

			// 完工：统一校正双开门铰链
			repairDoubleDoors();

			announceComplete();

			// 生活点注册：建造者优先入住，剩余空位分给城市无家NPC
			com.wenzai.neosim.npc.CityLivingManager.onBuildingCompleted(level, building);

			LOGGER.info("NeoSim-ConstructionTask: Complete {} (Lv.{}, builder auto-resigned)",
					building.getSchematicName(), (int) builderLevel);
		}
	}

	// 特殊标记
	private void placeMarkerBlock(SpecialMarker marker, int width, int layer, int depth)
	{
		BlockState markerState = marker.toBlockState();
		if (markerState == null) return;

		BlockPos worldPos = building.blueprintToWorld(width, layer, depth);
		if (!level.getBlockState(worldPos).equals(markerState))
		{
			level.setBlock(worldPos, markerState, Block.UPDATE_ALL);
		}

		// 标记棒：登记矩形
		if (markerState.getBlock() instanceof Marker)
		{
			MarkerManager.onPlaced(level, worldPos);
			WorkPlotEngine.tryBindAfterMarkerPlacement(level);
		}

		// 控制箱：登记文件
		if (marker == SpecialMarker.CONTROL_BOX)
		{
			recordControlBox(worldPos);
		}
	}

	// 特殊方块生效：放置者为蓝图放置者
	private void activatePlacedSpecial(BlockState placed, BlockPos pos)
	{
		if (placed.getBlock() instanceof FarmingBox)
		{
			WorkPlotEngine.createFarmPlot(level, pos, building.getPlacerName());
		}
		else if (placed.getBlock() instanceof MiningBox)
		{
			WorkPlotEngine.createMinePlot(level, pos, building.getPlacerName());
		}
		else if (placed.getBlock() instanceof Marker)
		{
			MarkerManager.onPlaced(level, pos);
			WorkPlotEngine.tryBindAfterMarkerPlacement(level);
		}
	}

	// 写入控制箱记录，放置者未入城时跳过（与任务持久化一致）
	private void recordControlBox(BlockPos boxPos)
	{
		String city = cityOf(building, level);
		if (city == null || city.isEmpty())
		{
			LOGGER.warn("NeoSim-ConstructionTask: skip control box record '{}' at {} — placer has no city",
					building.getSchematicName(), boxPos);
			return;
		}
		ControlBoxPersistence.ControlBoxRecord rec = ControlBoxPersistence.ControlBoxRecord.of(
				boxPos, building.getControlBoxPos(), building.getSchematicName(),
				building.getPlacerName(), building.getAuthor(),
				livingPointsOf(building));

		// 定价
		if (building.getSchematic() != null)
		{
			rec = rec.withRent(building.getSchematic().getTotalSolidBlocks() * Config.LIFE_RENT_PER_BLOCK.get());
		}
		ControlBoxPersistence.addOrUpdate(level, city, rec);
	}

	// 建筑全部生活点的世界坐标
	private static List<BlockPos> livingPointsOf(BuildingInstance building)
	{
		List<BlockPos> out = new ArrayList<>();
		Map<BlockPos, SpecialMarker> markers = building.getSchematic().getSpecialMarkers();
		if (markers != null)
		{
			for (Map.Entry<BlockPos, SpecialMarker> e : markers.entrySet())
			{
				if (e.getValue() == SpecialMarker.LIVING_POINT)
				{
					BlockPos l = e.getKey();
					out.add(building.blueprintToWorld(l.getX(), l.getY(), l.getZ()));
				}
			}
		}
		return out;
	}

	// 在容器局部坐标中查找相邻的床格，返回其世界坐标
	private BlockPos findAdjacentBedCellWorld(int width, int layer, int depth,
											   LightweightBlockContainer container, int sx, int sz)
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
					return building.blueprintToWorld(nx, layer, nz);
				}
			}
		}
		return null;
	}

	// 继续等待
	private boolean hasMaterial(Item item)
	{
		nearbyChests = findNearbyChestsNow();
		return InventoryManager.countItems(nearbyChests, item) > 0;
	}

	private List<ChestBlockEntity> findNearbyChestsNow()
	{
		List<ChestBlockEntity> chests = new ArrayList<>(
				InventoryManager.findNearbyChests(level, building.getControlBoxPos()));
		BlockPos box = building.getConstructorPos();
		if (box != null && !box.equals(building.getControlBoxPos()))
		{
			for (ChestBlockEntity chest : InventoryManager.findNearbyChests(level, box))
			{
				if (!chests.contains(chest))
				{
					chests.add(chest);
				}
			}
		}
		return chests;
	}

	private void extractMaterial(Item item)
	{
		InventoryManager.extractItem(nearbyChests, item, 1);
	}

	// 依附性方块朝向修正：找不到支撑跳过
	private BlockState fixAttachedFacing(BlockPos worldPos, BlockState state)
	{
		Block block = state.getBlock();

		// 贴墙类
		if (block instanceof LadderBlock
				|| block instanceof WallTorchBlock
				|| block instanceof RedstoneWallTorchBlock
				|| block instanceof WallSignBlock
				|| block instanceof ButtonBlock
				|| block instanceof LeverBlock
				|| block instanceof TripWireHookBlock
				|| block instanceof CocoaBlock
				|| block instanceof VineBlock)
		{
			if (block instanceof VineBlock)
			{
				for (Direction d : Direction.Plane.HORIZONTAL)
				{
					if (state.getValue(vineProperty(d)) && hasSupport(worldPos, d))
					{
						// 蓝图方向已贴墙
						return state;
					}
				}
				for (Direction d : Direction.Plane.HORIZONTAL)
				{
					if (hasSupport(worldPos, d))
					{
						return state.setValue(vineProperty(d), true);
					}
				}
				return null;
			}

			net.minecraft.world.level.block.state.properties.Property<Direction> facingProp =
					block instanceof CocoaBlock
							? CocoaBlock.FACING
							: BlockStateProperties.HORIZONTAL_FACING;
			if (!state.hasProperty(facingProp)) return state;
			Direction facing = state.getValue(facingProp);

			if (hasSupport(worldPos, facing.getOpposite())) return state;

			for (Direction d : Direction.Plane.HORIZONTAL)
			{
				if (d != facing && hasSupport(worldPos, d.getOpposite()))
				{
					return state.setValue(facingProp, d);
				}
			}
			return null;
		}

		// 地面类
		if (block instanceof BushBlock
				|| block instanceof CropBlock
				|| block instanceof StemBlock
				|| block instanceof AttachedStemBlock
				|| block instanceof SaplingBlock
				|| block instanceof SugarCaneBlock
				|| block instanceof FlowerPotBlock
				|| block instanceof StandingSignBlock
				|| block instanceof TorchBlock
				|| block instanceof PressurePlateBlock
				|| block instanceof BedBlock
				|| block instanceof SnowLayerBlock
				|| block instanceof AnvilBlock
				|| block instanceof DoorBlock
				|| block instanceof BaseRailBlock
				|| block instanceof RedStoneWireBlock
				|| block instanceof TripWireBlock
				|| block instanceof BannerBlock
				|| block instanceof CarpetBlock)
		{
			return level.getBlockState(worldPos.below()).isAir() ? null : state;
		}

		return state;
	}

	// 连接性方块
	private static boolean isConnectiveBlock(BlockState state)
	{
		return state.getBlock() instanceof CrossCollisionBlock
				|| state.getBlock() instanceof WallBlock;
	}

	// 逐个方向重算连接，使新放的连接块自动相连
	private BlockState fixConnectiveConnections(BlockPos pos, BlockState state)
	{
		for (Direction dir : Direction.values())
		{
			BlockPos neighborPos = pos.relative(dir);
			state = state.updateShape(dir, level.getBlockState(neighborPos), level, pos, neighborPos);
		}
		return state;
	}

	// 双开门
	private void fixDoubleDoor(BlockPos doorPos)
	{
		// 一律以下半格为准
		BlockPos lowerPos = doorPos;
		BlockState lower = level.getBlockState(lowerPos);
		if (!(lower.getBlock() instanceof DoorBlock))
		{
			LOGGER.info("NeoSim-ConstructionTask: fixDoubleDoor skip {} — not a door", lowerPos);
			return;
		}
		if (lower.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
		{
			lowerPos = lowerPos.below();
			lower = level.getBlockState(lowerPos);
			if (!(lower.getBlock() instanceof DoorBlock)) return;
		}

		Direction facing = lower.getValue(BlockStateProperties.HORIZONTAL_FACING);
		LOGGER.info("NeoSim-ConstructionTask: fixDoubleDoor lower={} facing={} hinge={}",
				lowerPos, facing, lower.getValue(BlockStateProperties.DOOR_HINGE));

		// 门在朝向的垂直方向相邻、同朝向->铰链取反
		for (Direction side : new Direction[] { facing.getCounterClockWise(), facing.getClockWise() })
		{
			BlockPos neighborPos = lowerPos.relative(side);
			BlockState neighbor = level.getBlockState(neighborPos);
			LOGGER.debug("NeoSim-ConstructionTask: fixDoubleDoor side={} at {} → {}",
					side, neighborPos, neighbor.getBlock().getDescriptionId());
			if (neighbor.getBlock() instanceof DoorBlock
					&& neighbor.getValue(BlockStateProperties.HORIZONTAL_FACING) == facing
					&& neighbor.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
			{
				net.minecraft.world.level.block.state.properties.DoorHingeSide neighborHinge =
						neighbor.getValue(BlockStateProperties.DOOR_HINGE);

				// 取反铰链
				net.minecraft.world.level.block.state.properties.DoorHingeSide opposite =
						neighborHinge == net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT
								? net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT
								: net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT;
				LOGGER.debug("NeoSim-ConstructionTask: fixDoubleDoor pair with {} hinge={} → set {} to {}",
						neighborPos, neighborHinge, lowerPos, opposite);
				if (lower.getValue(BlockStateProperties.DOOR_HINGE) == opposite)
				{
					// 已配对
					return;
				}

				// 本门两格一起改铰链，保持上下一致
				level.setBlock(lowerPos, lower.setValue(BlockStateProperties.DOOR_HINGE, opposite), Block.UPDATE_ALL);
				BlockState upper = level.getBlockState(lowerPos.above());
				if (upper.getBlock() instanceof DoorBlock)
				{
					level.setBlock(lowerPos.above(),
							upper.setValue(BlockStateProperties.DOOR_HINGE, opposite), Block.UPDATE_ALL);
				}
				return;
			}
		}

		// txt蓝图的门重定向成双开门
		for (Direction side : new Direction[] { facing, facing.getOpposite() })
		{
			BlockPos neighborPos = lowerPos.relative(side);
			BlockState neighbor = level.getBlockState(neighborPos);
			if (neighbor.getBlock() instanceof DoorBlock
					&& neighbor.getValue(BlockStateProperties.HORIZONTAL_FACING) == facing
					&& neighbor.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
			{
				orientDoubleDoorPair(lowerPos, neighborPos, side);
				return;
			}
		}
	}

	// 把同朝向、沿朝向轴相邻的两扇门重定向成真正对开门
	private void orientDoubleDoorPair(BlockPos posA, BlockPos posB, Direction side)
	{
		BlockState lowerA = level.getBlockState(posA);
		Direction orig = lowerA.getValue(BlockStateProperties.HORIZONTAL_FACING);
		Direction target = pickDoubleDoorFacing(posA, posB, orig);

		// 铰链朝外侧
		net.minecraft.world.level.block.state.properties.DoorHingeSide hingeA =
				side == target.getCounterClockWise()
						? net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT
						: net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT;
		net.minecraft.world.level.block.state.properties.DoorHingeSide hingeB =
				hingeA == net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT
						? net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT
						: net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT;

		LOGGER.info("NeoSim-ConstructionTask: orientDoubleDoor {} + {} → facing={} hinge={}/{}",
				posA, posB, target, hingeA, hingeB);
		setDoorFacingHinge(posA, target, hingeA);
		setDoorFacingHinge(posB, target, hingeB);
	}

	// 目标朝向：垂直于邻接轴，优先选正前方开阔的一侧
	private Direction pickDoubleDoorFacing(BlockPos posA, BlockPos posB, Direction orig)
	{
		Direction a = orig.getClockWise();
		Direction b = orig.getCounterClockWise();
		int solidsA = solidsInFront(posA, posB, a);
		int solidsB = solidsInFront(posA, posB, b);
		if (solidsA != solidsB)
		{
			return solidsA < solidsB ? a : b;
		}

		// 平局：取逆时针候选
		return b;
	}

	private int solidsInFront(BlockPos posA, BlockPos posB, Direction dir)
	{
		int n = 0;
		if (!level.getBlockState(posA.relative(dir)).isAir()) n++;
		if (!level.getBlockState(posB.relative(dir)).isAir()) n++;
		return n;
	}

	// 改一扇门的朝向与铰链
	private void setDoorFacingHinge(BlockPos pos, Direction facing,
									net.minecraft.world.level.block.state.properties.DoorHingeSide hinge)
	{
		BlockState lower = level.getBlockState(pos);
		if (!(lower.getBlock() instanceof DoorBlock)) return;
		level.setBlock(pos, lower.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
				.setValue(BlockStateProperties.DOOR_HINGE, hinge), Block.UPDATE_ALL);
		BlockState upper = level.getBlockState(pos.above());
		if (upper.getBlock() instanceof DoorBlock)
		{
			level.setBlock(pos.above(), upper.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
					.setValue(BlockStateProperties.DOOR_HINGE, hinge), Block.UPDATE_ALL);
		}
	}

	// 完工：把所有蓝图里的门都过一遍双开门配对
	private void repairDoubleDoors()
	{
		LightweightBlockContainer container = schematic.getBlockContainer();
		int sx = container.getSizeX();
		int sz = container.getSizeZ();
		int count = 0;
		for (int y = 0; y < container.getSizeY(); y++)
		{
			for (int z = 0; z < container.getSizeZ(); z++)
			{
				for (int x = 0; x < container.getSizeX(); x++)
				{
					BlockState st = container.get(x, y, z);
					if (st.getBlock() instanceof DoorBlock
							&& st.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
					{
						BlockPos worldPos = building.blueprintToWorld(x, y, z);
						fixDoubleDoor(worldPos);
						count++;
					}
				}
			}
		}
		if (count > 0)
		{
			LOGGER.info("NeoSim-ConstructionTask: repairDoubleDoors checked {} doors", count);
		}
	}

	private boolean hasSupport(BlockPos pos, Direction dir)
	{
		return !level.getBlockState(pos.relative(dir)).isAir();
	}

	// 方向属性
	private static net.minecraft.world.level.block.state.properties.BooleanProperty vineProperty(Direction d)
	{
		return switch (d)
		{
			case NORTH -> VineBlock.NORTH;
			case SOUTH -> VineBlock.SOUTH;
			case EAST -> VineBlock.EAST;
			default -> VineBlock.WEST;
		};
	}

	// C6d：缺料扫描结果缓存（resumeIndex/phaseTwo 未变时复用，避免每 3 秒/每次派单全量重扫）
	private Item cachedNextItem;
	private int cachedNextScanIndex = -1;
	private boolean cachedNextPhaseTwo;

	// 下一个要放的方块所需材料
	public Item getNextBlockItem()
	{
		if (cachedNextScanIndex == resumeIndex && cachedNextPhaseTwo == phaseTwo)
		{
			return cachedNextItem;
		}

		LightweightBlockContainer container = schematic.getBlockContainer();
		int sx = container.getSizeX();
		int sz = container.getSizeZ();
		Item result = null;
		for (int i = resumeIndex; i < container.getTotalVolume(); i++)
		{
			int layer = i / (sx * sz);
			int depth = (i / sx) % sz;
			int width = i % sx;
			BlockState desired = container.get(width, layer, depth);
			if (desired.isAir()) continue;

			// 检查当前轮次的方块
			if (MaterialCalculator.isAttachedBlock(desired) != phaseTwo) continue;
			BlockPos worldPos = building.blueprintToWorld(width, layer, depth);
			BlockState current = level.getBlockState(worldPos);
			if (current.equals(CoordTransform.transformState(desired, building.getFacing()))) continue;
			if (MaterialCalculator.requiresMaterial(desired, currentMode()))
			{
				result = desired.getBlock().asItem();
			}
			break;
		}
		cachedNextScanIndex = resumeIndex;
		cachedNextPhaseTwo = phaseTwo;
		cachedNextItem = result;
		return result;
	}

	// 聊天栏提醒缺料
	private void notifyMissingMaterial(Item item)
	{
		if (item == null) return;

		// 无论是否已通知过，都刷新缓存供GUI状态页读取
		this.lastMissingMaterial = item;
		if (item == lastNotifiedMissingItem) return;
		lastNotifiedMissingItem = item;

		if (level.getServer() == null) return;
		sendPacketToCityPlayers(new com.wenzai.neosim.network.ServerToClientPayloads.ResourceShortagePacket(
				LifeSystem.tpl(Config.ANNOUNCE_MISSING_MATERIAL,
						building.getSchematicName(),
						item.getDescription().getString())));
	}

	// 只发给该建筑所属城市的在线玩家(放置者未入城时发给所有玩家)
	private void sendPacketToCityPlayers(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload)
	{
		String cityName = cityOf(building, level);
		boolean dedicated = level.getServer().isDedicatedServer();
		String saveName = dedicated ? null : level.getServer().getWorldData().getLevelName();
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers())
		{
			if (cityName != null && !cityName.isEmpty())
			{
				String pname = player.getName().getString();
				boolean inCity = dedicated
						? FileCreater.isPlayerInCity(cityName, pname)
						: FileCreater.isPlayerInCity(cityName, saveName, pname);
				if (!inCity) continue;
			}
			net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload);
		}
	}

	// C6d：缺料需求量缓存（item+resumeIndex 未变时复用，避免每次派单全量扫描剩余体积）
	private Item cachedMissingItem;
	private int cachedMissingScanIndex = -1;
	private int cachedMissingNeed;

	// 统计剩余蓝图中该物品的需求量与箱子存量的差额（至少为1）
	private int countMissing(Item item)
	{
		if (nearbyChests == null || nearbyChests.isEmpty())
		{
			nearbyChests = findNearbyChestsNow();
		}
		int need;
		if (item == cachedMissingItem && cachedMissingScanIndex == resumeIndex)
		{
			need = cachedMissingNeed;
		}
		else
		{
			need = 0;
			LightweightBlockContainer container = schematic.getBlockContainer();
			int sx = container.getSizeX();
			int sz = container.getSizeZ();
			for (int i = resumeIndex; i < container.getTotalVolume(); i++)
			{
				int layer = i / (sx * sz);
				int depth = (i / sx) % sz;
				int width = i % sx;
				BlockState st = container.get(width, layer, depth);
				if (st.getBlock().asItem() == item) need++;
			}
			cachedMissingItem = item;
			cachedMissingScanIndex = resumeIndex;
			cachedMissingNeed = need;
		}
		int have = InventoryManager.countItems(nearbyChests, item);
		return Math.max(1, need - have);
	}

	// 剩余缺口数量（快递盒取料量用；至少为 1）
	public int getMissingCount(Item item)
	{
		return countMissing(item);
	}

	// 完工公告
	private void announceComplete()
	{
		sendPacketToCityPlayers(new com.wenzai.neosim.network.ServerToClientPayloads.BuildingCompletePacket(
				LifeSystem.tpl(Config.ANNOUNCE_BUILDING_COMPLETE, building.getSchematicName())));
		LOGGER.info("NeoSim-ConstructionTask: announce complete — {}", building.getSchematicName());
	}

	// 从建筑所属城市的资金中扣除费用
	public static void deductCredits(ServerLevel level, BuildingInstance building, double amount)
	{
		if (amount <= 0 || level.getServer() == null) return;
		String city = cityOf(building, level);
		if (city == null || city.isEmpty())
		{
			LOGGER.warn("NeoSim-ConstructionTask: skip credit deduction — placer has no city");
			return;
		}
		com.wenzai.neosim.storage.SimData.CityData data = com.wenzai.neosim.storage.SimData.CityData.read(level, city);
		double now = data.credit() - amount;
		if (now < 0) now = 0;
		com.wenzai.neosim.storage.SimData.CityData.write(level, city, data.withCredit(now));
		com.wenzai.neosim.storage.ModSavedData.get(level).syncCityToClients(level, city);
		LOGGER.info("NeoSim-ConstructionTask: deducted {} credits from city '{}'",
				Math.round(amount * 100.0) / 100.0, city);
	}

	// 建筑所属城市=放置者所属城市（放置者未入城时返回空）
	public static String cityOf(BuildingInstance building, ServerLevel level)
	{
		String placer = building.getPlacerName();
		if (placer == null || placer.isEmpty()) return "";
		if (level.getServer() == null) return "";
		boolean dedicated = level.getServer().isDedicatedServer();
		return dedicated
				? FileCreater.findPlayerCity(placer)
				: FileCreater.findPlayerCity(level.getServer().getWorldData().getLevelName(), placer);
	}

	// 清空NPC手持
	private void clearBuilderHand()
	{
		resolveBuilderNpc();
		if (builderNpc != null)
		{
			builderNpc.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
					net.minecraft.world.item.ItemStack.EMPTY);
		}
	}

	// 是否已雇佣工人
	private boolean hasWorker()
	{
		BlockPos box = building.getConstructorPos();
		if (box == null) return false;
		String name = NeoSim.WORKER_MAP.get(box);
		return name != null && !name.isEmpty();
	}

	// 工人是否已到岗
	private boolean isWorkerOnShift()
	{
		return currentState == BuildingInstance.BuildState.LOADING_BLUEPRINT
				|| currentState == BuildingInstance.BuildState.BUILDING
				|| currentState == BuildingInstance.BuildState.WAITING_FOR_RESOURCES;
	}

	// 是否夜晚
	private boolean isNightTime()
	{
		return level.getDayTime() % 24000 >= 12000;
	}

	// 下班：回生活点
	private void goOffWork()
	{
		resolveBuilderNpc();
		if (builderNpc != null)
		{
			BlockPos home = homePosition();
			if (home != null) builderNpc.setMoveTarget(home);
		}
	}

	// 夜晚入职的工人：当晚不前往工地
	private void restNewWorker()
	{
		String workerName = NeoSim.WORKER_MAP.get(building.getConstructorPos());
		if (workerName == null || workerName.isEmpty()) return;
		Entity npc = Entity.findByNpcName(level, workerName);
		if (npc != null)
		{
			BlockPos home = npc.getHomePos();
			if (home != null)
			{
				npc.setMoveTarget(home);
			}
			else
			{
				npc.clearMoveTarget();
			}
		}
	}

	// 白天
	private void ensureWorkerAtSite()
	{
		resolveBuilderNpc();
		if (builderNpc != null)
		{
			// 产假：孕期NPC白天不返工
			if (builderNpc.getPregnancyStage() > 0.0F) return;
			BlockPos box = building.getConstructorPos();
			if (box == null) box = building.getControlBoxPos();
			if (box != null) builderNpc.setMoveTarget(box);
		}
	}

	// 建筑生活点
	private BlockPos homePosition()
	{
		// 生活点系统：工人已有生活点则回生活点
		if (builderNpc != null && builderNpc.getHomePos() != null)
		{
			return builderNpc.getHomePos();
		}

		// 建筑生活点：多生活点优先空闲者，全被占才退回第一个
		List<BlockPos> living = livingPointsOf(building);
		if (!living.isEmpty())
		{
			ControlBoxPersistence.ControlBoxRecord rec = findHomeRecord();
			if (rec != null)
			{
				for (BlockPos lp : living)
				{
					if (!ControlBoxPersistence.isLivingPointOccupied(rec, lp))
					{
						return lp;
					}
				}
			}
			return living.get(0);
		}

		// 兜底：控制箱
		return building.getControlBoxPos();
	}

	// 本建筑的控制箱记录（未入城/未登记时返回null）
	private ControlBoxPersistence.ControlBoxRecord findHomeRecord()
	{
		String city = cityOf(building, level);
		if (city == null || city.isEmpty()) return null;
		BlockPos box = building.getControlBoxPos();
		if (box == null) return null;
		return ControlBoxPersistence.findRecord(level, city, box);
	}

	// 驱动雇佣的建造者NPC的抬手
	private void setBuilderAnim(float value)
	{
		resolveBuilderNpc();
		if (builderNpc != null)
		{
			builderNpc.setBuildAnim(value);
		}
	}

	// 按模盒坐标找雇佣的NPC实体（C1：名字索引 O(1)）
	private void resolveBuilderNpc()
	{
		if (builderNpc != null && builderNpc.isAlive()) return;
		builderNpc = null;
		BlockPos box = building.getConstructorPos();
		if (box == null) return;
		String workerName = NeoSim.WORKER_MAP.get(box);
		if (workerName == null || workerName.isEmpty()) return;

		builderNpc = Entity.findByNpcName(level, workerName);
		if (builderNpc != null)
		{
			// 从NPC读取建造者等级，建造速度随等级
			float lvl = Math.max(1.0F, (float) builderNpc.getJobArchitect());
			if (lvl != builderLevel)
			{
				updateBuildSpeed(lvl);
			}
		}
	}

	private boolean workerExistsInLevel(String workerName)
	{
		return Entity.findByNpcName(level, workerName) != null;
	}

	// NPC被卸载时，恢复并传送
	private void tryRestoreWorker()
	{
		BlockPos box = building.getConstructorPos();
		if (box == null) return;
		String workerName = NeoSim.WORKER_MAP.get(box);
		if (workerName == null || workerName.isEmpty()) return;

		String city = cityOf(building, level);

		// 城市出错时，放弃本次恢复
		if (city.isEmpty()) return;

		com.wenzai.neosim.npc.Entity npc =
				com.wenzai.neosim.npc.Manage.spawnSingle(level, city, workerName, box);
		if (npc != null)
		{
			npc.assignToSite(box);
			builderNpc = npc;
			LOGGER.info("NeoSim-ConstructionTask: worker '{}' restored to site (was missing)", workerName);
		}
		else
		{
			// 已死亡：解雇，任务回到等待，GUI可重新雇佣
			NeoSim.WORKER_MAP.remove(box);
			building.setWorkerName(null);
			currentState = BuildingInstance.BuildState.WAITING_FOR_WORKER;
			building.setState(currentState);
			LOGGER.warn("NeoSim-ConstructionTask: worker '{}' gone (file deleted), task back to waiting for worker",
					workerName);
		}
	}

	public void updateBuildSpeed(float levelBuilder)
	{
		this.builderLevel = levelBuilder;
		this.buildDelay = Math.max(1, (int) (BASE_DELAY / levelBuilder));
	}

	// 当前运行模式
	private byte currentMode()
	{
		return com.wenzai.neosim.storage.ModSavedData.get(level).getMode();
	}

	public void pause()
	{
		this.paused = true;

		// 同步到实例，随文件持久化
		building.setPaused(true);
		setBuilderAnim(0.0F);
		clearBuilderHand();
		building.setState(currentState);
		LOGGER.info("NeoSim-ConstructionTask: paused at {}/{}", resumeIndex, getTotal());
	}

	public void resume()
	{
		this.paused = false;
		building.setPaused(false);
		if (currentState == BuildingInstance.BuildState.WAITING_FOR_RESOURCES)
		{
			currentState = BuildingInstance.BuildState.BUILDING;
		}
		animStartTime = System.currentTimeMillis();
		building.setState(currentState);
		LOGGER.info("NeoSim-ConstructionTask: resumed from {}/{}", resumeIndex, getTotal());
	}

	public BuildingInstance.BuildState getState() { return currentState; }
	public void setState(BuildingInstance.BuildState state) { this.currentState = state; building.setState(state); }

	// GUI进度：两轮显示
	public int getProgress()
	{
		return phaseTwo ? schematic.getTotalVolume() + resumeIndex : resumeIndex;
	}
	public int getTotal() { return schematic.getTotalVolume() * 2; }
	public BuildingInstance getBuilding() { return building; }
	public boolean isPaused() { return paused; }
	public float getBuilderLevel() { return builderLevel; }

	// GUI状态页读取：当前阻塞建造的缺料
	public Item getLastMissingMaterial() { return lastMissingMaterial; }

	// 防删改：蓝图数据越界（原点/高度超世界）时安全中止建造
	private void abortConstruction()
	{
		if (building.getConstructorPos() != null)
		{
			NeoSim.WORKER_MAP.remove(building.getConstructorPos());
		}
		resolveBuilderNpc();
		if (builderNpc != null)
		{
			builderNpc.releaseFromSite();
			builderNpc.setBuildAnim(0.0F);
		}
		clearBuilderHand();
		BuildingChunkLoader.releaseForBuilding(building, level);
		building.setBuildingComplete(true);
		building.setState(BuildingInstance.BuildState.COMPLETE);
		currentState = BuildingInstance.BuildState.COMPLETE;
		LOGGER.warn("NeoSim-ConstructionTask: aborted construction of '{}' at {} — origin out of build height",
				building.getSchematicName(), building.getControlBoxPos());
	}
}

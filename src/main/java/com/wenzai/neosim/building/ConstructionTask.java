package com.wenzai.neosim.building;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.block.*;
import com.wenzai.neosim.compat.attached.AttachedBlockTable;
import com.wenzai.neosim.compat.sable.PhysicsWorld;
import com.wenzai.neosim.life.LifeSystem;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.NpcGoals;
import com.wenzai.neosim.schematic.*;
import com.wenzai.neosim.storage.FileCreater;
import com.wenzai.neosim.util.BlueprintName;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConstructionTask
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final int BASE_DELAY = 2000;

	// 挖掘阻挡方块的等级进度惩罚（等价 10 个方块的建造经验：每块 0.001/等级）
	private static final float DIG_PENALTY = 0.01f;

	// 挖掘耗时倍率（相对 buildDelay）
	private static final int DIG_DELAY_MULTIPLIER = 2;

	// 等待材料时3秒检查一次
	private static final int WAITING_CHECK_DELAY = 3000;

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
	private float builderLevel = 1.0f;
	private int buildDelay = BASE_DELAY;

	// 待挖掘的阻挡方块（建造遇不同类型方块挡路时）
	private BlockPos pendingDigPos;
	private boolean pendingDigDrop;
	private boolean paused;

	// 两轮建造
	private boolean phaseTwo;

	// 依附方块表兜底：第二轮扫完后仍有被推迟的方块时回卷重试的上限
	private static final int MAX_RETRY_ROUNDS = 4;

	// 建造工实体缺失多久后触发兜底恢复（10 秒）；期间暂停施工，防"无人也能盖完"
	private static final int WORKER_MISSING_TICKS = 200;

	// 当前轮回卷次数、本轮被推迟的方块数、本轮是否已放置过方块（判断"还有进展"）
	private int retryRound;
	private int deferredThisRound;
	private boolean placedThisRound;

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

		// 建造开始时刷新内容表：改完 NeoSim/Json/ 后下一个建筑盒立刻生效，不依赖轮询时序
		AttachedBlockTable.reloadIfChanged();
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

		// 夜晚 / 休息时间：全体下班，工人可以自由走动
		if (isNightTime())
		{
			resolveBuilderNpc();
			if (builderNpc != null) builderNpc.setStayPut(false);

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

		// 模盒被破坏（含子世界内模盒）：任务取消
		if (!constructorBoxStillThere())
		{
			LOGGER.warn("NeoSim-ConstructionTask: constructor box gone at {}, cancelling task",
					building.getConstructorPos());
			com.wenzai.neosim.building.ConstructionEngine.cancelTaskAt(boxPos(), level);
			return;
		}

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
			if (box != null && builderNpc != null
					&& NpcGoals.MoveToSiteGoal.isAboveSite(builderNpc,
							PhysicsWorld.toWorld(level, box)))
			{
				workerMissingTicks = 0;
				onWorkerArrived();
			}
			else if (builderNpc == null)
			{
				// 累计超时后处理
				workerMissingTicks++;
				if (workerMissingTicks >= WORKER_MISSING_TICKS)
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
			// 待料：工人原地不动（不逃离玩家/怪物）；每 tick 重申，防解冻/重注册后漏掉
			resolveBuilderNpc();
			if (builderNpc != null) builderNpc.setStayPut(true);

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

				// 材料补足：解除待命，恢复工作AI
				resolveBuilderNpc();
				if (builderNpc != null) builderNpc.setStayPut(false);

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

		// 建造中：实体缺失先暂停施工（防"无人也能盖完"），超时后兜底恢复；
		// 恢复不了由 tryRestoreWorker 内部解雇回 WAITING_FOR_WORKER
		resolveBuilderNpc();
		if (builderNpc == null)
		{
			workerMissingTicks++;
			if (workerMissingTicks >= WORKER_MISSING_TICKS)
			{
				workerMissingTicks = 0;
				BlockPos box = building.getConstructorPos();
				if (box == null) box = building.getControlBoxPos();
				String workerName = box != null ? NeoSim.WORKER_MAP.get(box) : null;
				if (workerName != null && !workerName.isEmpty() && !workerExistsInLevel(workerName))
				{
					tryRestoreWorker();
				}
			}
			setBuilderAnim(0.0F);
			clearBuilderHand();
			return;
		}
		workerMissingTicks = 0;

		// 实体在场但不在模盒正上方时，等就位
		if (builderNpc.getPregnancyStage() <= 0.0F)
		{
			BlockPos box = building.getConstructorPos();
			if (box == null) box = building.getControlBoxPos();
			if (box != null && !NpcGoals.MoveToSiteGoal.isAboveSite(builderNpc,
					PhysicsWorld.toWorld(level, box)))
			{
				setBuilderAnim(0.0F);
				clearBuilderHand();
				return;
			}
		}

		// 挖掘优先：正在挖阻挡方块时不建造
		if (pendingDigPos != null)
		{
			tickDig();
			return;
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

		// 建造 flag 按模式降级（创造模式不触发全量光照/邻居更新）
		// 放置后触发完整方块更新（邻居更新/形状传播/onPlace），保证模组方块（机械连接、红石等）正确联动
		int placeFlags = Block.UPDATE_ALL;

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

			// 两轮：第一轮只放实心方块；第二轮放依附性方块与流体（水 / 岩浆）。
			// 流体排在第二轮是为了让池底 / 池壁先围好，水最后落进容器里，不在建造过程中先漫开。
			if (MaterialCalculator.isAttachedBlock(desired) != phaseTwo)
			{
				resumeIndex++;
				continue;
			}

			BlockPos worldPos = building.blueprintToWorld(width, layer, depth);

			// 模盒那一格永远不动：轮廓可能正好压在模盒上，挖掉它会让任务自我取消
			if (worldPos.equals(boxPos()))
			{
				resumeIndex++;
				continue;
			}

			BlockState current = PhysicsWorld.getBlockState(level, worldPos);

			// 朝向与位置同一条落地链（BlueprintPlacement）：格式映射 + 镜像 + 旋转一次算完
			BlockState toPlace = building.placement().state(desired);

			// 依附性方块朝向贴着实际支撑
			if (MaterialCalculator.isAttachedBlock(desired))
			{
				toPlace = PlacementSupport.fixAttachedFacing(level, worldPos, toPlace);
				if (toPlace == null)
				{
					deferredThisRound++;
					if (deferredThisRound <= 8 || deferredThisRound % 64 == 0)
					{
						LOGGER.info("NeoSim-ConstructionTask: defer '{}' at {} — no support yet (mode {})",
								desired.getBlock().getDescriptionId(), worldPos,
								AttachedBlockTable.mode(desired).id());
					}
					resumeIndex++;
					continue;
				}
			}

			// 连接性方块：放置时按实际相邻方块重算连接
			if (PlacementSupport.isConnective(toPlace))
			{
				toPlace = PlacementSupport.fixConnectiveConnections(level, worldPos, toPlace);
			}

			if (current.equals(toPlace))
			{
				resumeIndex++;
				continue;
			}

			// 已有同类型方块（含被手动旋转朝向/修改状态的）：尊重现状，不重放，防止恢复时还原
			if (!current.isAir() && current.getBlock() == toPlace.getBlock())
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

			// 阻挡方块：启动挖掘（延迟后清除 + 扣等级进度），本 tick 结束
			// 例外：植被 / 雪层 / 带流体的格子（水、岩浆、含水方块）不挖，直接盖掉 ——
			// 与"开始建造"按钮的冲突检测用同一份判定（PlacementSupport.isNonBlocking），
			// 否则会出现"能点开始、建的时候被草/雪/水卡住反复挖"。vanilla setBlock 本来就会替换掉它们
			if (!PlacementSupport.isNonBlocking(current))
			{
				startDig(worldPos, currentMode() != 2);
				return;
			}

			// 兜底预检：方块自身判定能否在当前世界存活
			// （表未覆盖的模组依附方块也能正确推迟，且不会白扣一份材料）
			if (AttachedBlockTable.precheck(toPlace) && !PlacementSupport.canSurviveAt(level, worldPos, toPlace))
			{
				deferredThisRound++;
				if (deferredThisRound <= 8 || deferredThisRound % 64 == 0)
				{
					LOGGER.info("NeoSim-ConstructionTask: defer '{}' at {} — cannot survive yet",
							desired.getBlock().getDescriptionId(), worldPos);
				}
				resumeIndex++;
				continue;
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

			// 渲染NPC手持要放置的方块（仅物品变化时更新，避免每块触发装备同步）
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

			// 放置方块
			PhysicsWorld.setBlock(level, worldPos, toPlace, placeFlags);
			placedThisRound = true;

			// 双箱合并：vanilla 的 getStateForPlacement 路径在纯 setBlock 下不触发，放置后手动合并
			PlacementSupport.mergeDoubleChest(level, worldPos, toPlace);

			// 特殊方块放置后生效
			activatePlacedSpecial(toPlace, worldPos);

			// 方块实体数据（告示牌文字、箱子内容、蜂巢…）：蓝图里带的话装回去，
			// 否则建出来的告示牌是空白的。键就是蓝图局部坐标（与容器同一坐标系）
			applyTileEntityData(width, layer, depth, worldPos, toPlace);

			// 双方块补齐：门补另一半并配对双开门；床按容器相邻床格补另一半
			if (toPlace.getBlock() instanceof DoorBlock)
			{
				PlacementSupport.completeDoor(level, worldPos, toPlace);
			}
			else if (toPlace.getBlock() instanceof BedBlock)
			{
				PlacementSupport.completeBed(level, worldPos, toPlace,
						findAdjacentBedCellWorld(width, layer, depth, container, sx, sz));
			}

			// 放置音效（创造模式静音，普通模式随机音高防单调）
			if (currentMode() != 2)
			{
				level.playSound(null, worldPos,
						desired.getSoundType().getPlaceSound(),
						SoundSource.BLOCKS, 1.0F, 0.8F + level.random.nextFloat() * 0.4F);
			}
			resumeIndex++;
			building.setBuildProgress(resumeIndex);

			// 技能成长与信用点扣除：每放一块 = 1 个经验单位（跨级写盘走合并窗口）
			builderLevel = Entity.addJobXp(builderNpc, Entity.JobKind.ARCHITECT, builderLevel, 1);
			updateBuildSpeed(builderLevel);

			// 一次tick只放一个实心方块
			break;
		}

		// 第一轮扫完仍有被推迟的方块（如活塞头等依赖同轮邻居的方块）：先在第一轮回卷补放
		if (resumeIndex >= totalVolume && !phaseTwo
				&& deferredThisRound > 0 && placedThisRound && retryRound < MAX_RETRY_ROUNDS)
		{
			retryRound++;
			resumeIndex = 0;
			building.setBuildProgress(0);
			placedThisRound = false;
			int deferred = deferredThisRound;
			deferredThisRound = 0;
			LOGGER.info("NeoSim-ConstructionTask: phase 1 retry round {}/{} — {} deferred block(s) — {}",
					retryRound, MAX_RETRY_ROUNDS, deferred, building.getSchematicName());
			return;
		}

		if (resumeIndex >= totalVolume && !phaseTwo)
		{
			if (deferredThisRound > 0)
			{
				LOGGER.warn("NeoSim-ConstructionTask: {} block(s) still unsupported after phase 1 retry — {}",
						deferredThisRound, building.getSchematicName());
			}
			phaseTwo = true;
			resumeIndex = 0;
			building.setPhaseTwo(true);
			building.setBuildProgress(0);
			setBuilderAnim(0.0F);
			retryRound = 0;
			deferredThisRound = 0;
			placedThisRound = false;
			LOGGER.info("NeoSim-ConstructionTask: phase 1 done, building attached blocks / fluids — {}",
					building.getSchematicName());
		}

		// 第二轮扫完仍有被推迟的方块：上一轮有进展且未超上限 → 回卷重试（支撑可能刚刚才放好）
		if (resumeIndex >= totalVolume && phaseTwo
				&& deferredThisRound > 0 && placedThisRound && retryRound < MAX_RETRY_ROUNDS)
		{
			retryRound++;
			resumeIndex = 0;
			building.setBuildProgress(0);
			placedThisRound = false;
			int deferred = deferredThisRound;
			deferredThisRound = 0;
			LOGGER.info("NeoSim-ConstructionTask: retry round {}/{} — {} deferred block(s) — {}",
					retryRound, MAX_RETRY_ROUNDS, deferred, building.getSchematicName());
			return;
		}

		// 完工
		if (resumeIndex >= totalVolume && phaseTwo)
		{
			if (deferredThisRound > 0)
			{
				LOGGER.warn("NeoSim-ConstructionTask: {} block(s) still unsupported after {} retry round(s) — {}",
						deferredThisRound, retryRound, building.getSchematicName());
			}
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
		if (!PhysicsWorld.getBlockState(level, worldPos).equals(markerState))
		{
			PhysicsWorld.setBlock(level, worldPos, markerState, Block.UPDATE_ALL);
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

	// 启动挖掘：记录待挖位置，计时从抬手动画开始
	// 把蓝图里的方块实体数据装回刚放下的方块（告示牌文字、箱子内容、蜂巢蜜蜂…）
	private void applyTileEntityData(int bx, int by, int bz, BlockPos worldPos, BlockState placed)
	{
		if (!placed.hasBlockEntity()) return;

		Map<BlockPos, CompoundTag> tileEntities = schematic.getTileEntities();
		if (tileEntities == null) return;

		CompoundTag stored = tileEntities.get(new BlockPos(bx, by, bz));
		if (stored == null) return;

		BlockEntity be = level.getBlockEntity(worldPos);
		if (be == null) return;

		CompoundTag tag = stored.copy();

		// 坐标改成实际落点
		tag.putInt("x", worldPos.getX());
		tag.putInt("y", worldPos.getY());
		tag.putInt("z", worldPos.getZ());

		try
		{
			be.loadWithComponents(tag, level.registryAccess());
			be.setChanged();

			// 让客户端拿到新的方块实体数据（告示牌文字不然只存在服务端）
			level.sendBlockUpdated(worldPos, placed, placed, Block.UPDATE_ALL);
		}
		catch (Throwable t)
		{
			LOGGER.warn("NeoSim-ConstructionTask: failed to load block entity data at {} — {}", worldPos, t.toString());
		}
	}

	private void startDig(BlockPos pos, boolean drop)
	{
		pendingDigPos = pos;
		pendingDigDrop = drop;
		animStartTime = System.currentTimeMillis();

		// 挖掘期间手持"对应工具"：按被挖方块的正确工具选类型（镐/斧/锹/锄），材质随建筑师等级
		// （放置方块时仍显示要放的方块，不受这里影响）
		resolveBuilderNpc();
		if (builderNpc != null)
		{
			BlockState digState = PhysicsWorld.getBlockState(level, pos);
			builderNpc.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
					new net.minecraft.world.item.ItemStack(
							Entity.JobTools.forBlock((int) builderLevel, digState)));
		}
	}

	// 挖掘 tick：计时 + 抬手动画，到时执行清除 + 等级惩罚
	private void tickDig()
	{
		long now = System.currentTimeMillis();
		long digDelay = Math.max(1, (long) buildDelay * DIG_DELAY_MULTIPLIER);
		long elapsed = now - animStartTime;
		if (elapsed < digDelay)
		{
			// 与建造同款抬手动画
			if (elapsed < LOWER_ANIM_MS)
			{
				setBuilderAnim(1.0F - elapsed / (float) LOWER_ANIM_MS);
			}
			else
			{
				long raiseStart = digDelay - RAISE_ANIM_MS;
				if (elapsed < raiseStart)
				{
					setBuilderAnim(0.0F);
				}
				else
				{
					setBuilderAnim((elapsed - raiseStart) / (float) RAISE_ANIM_MS);
				}
			}
			return;
		}

		BlockPos pos = pendingDigPos;
		pendingDigPos = null;

		// 方块还在才算挖掉（期间被他人移除则不扣进度）
		BlockState cur = PhysicsWorld.getBlockState(level, pos);
		if (!cur.isAir())
		{
			PhysicsWorld.destroyBlock(level, pos, pendingDigDrop);
			level.playSound(null, pos, cur.getSoundType().getBreakSound(),
					SoundSource.BLOCKS, 1.0F, 1.0F);
			applyDigPenalty(pos);
		}
	}

	// 挖掘完成：等级进度下降（等价 10 块经验），掉级时同步 NPC 档案
	private void applyDigPenalty(BlockPos pos)
	{
		int before = (int) Math.floor(builderLevel);
		builderLevel = Math.max(1.0F, builderLevel - DIG_PENALTY);
		int after = (int) Math.floor(builderLevel);
		if (after < before)
		{
			resolveBuilderNpc();
			if (builderNpc != null)
			{
				builderNpc.setJobLevel(Entity.JobKind.ARCHITECT, after);
				builderNpc.syncToJson();
			}
		}
		updateBuildSpeed(builderLevel);
		LOGGER.info("NeoSim-ConstructionTask: dug obstacle at {} (Lv.{}, -0.01 progress)",
				pos, builderLevel);
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
		else if (placed.getBlock() instanceof ControlBox)
		{
			// 蓝图里直接摆的控制箱方块（.litematic 常见）：与 .txt 的 $ 标记一样登记记录，
			// 否则控制箱没有记录，右键不出 GUI，也不会被当作住宅管理
			recordControlBox(pos);
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

		// 落地几何一并登记：重建模盒靠它把蓝图还原到世界（旧记录缺省由重建盒做定向探测）
		ControlBoxPersistence.ControlBoxRecord rec = ControlBoxPersistence.ControlBoxRecord.of(
				boxPos, building.getControlBoxPos(), building.getSchematicName(),
				building.getRotation().name(), building.getMirror().name(),
				building.getFacing() != null ? building.getFacing().name() : null,
				building.getPlacerName(), building.getAuthor(),
				livingPointsOf(building));

		// 定价
		if (building.getSchematic() != null)
		{
			rec = rec.withRent(building.getSchematic().getTotalSolidBlocks() * Config.LIFE_RENT_PER_BLOCK.get());
		}
		ControlBoxPersistence.addOrUpdate(level, city, rec);
	}

	// 建筑全部生活点的世界坐标。按列(x,z)去重：入住占用是按列判定的，
	// 同一列上叠着多个生活点（比如两个高度各放一个）只会显示成"同一个住户占了 2 个位置"
	private static List<BlockPos> livingPointsOf(BuildingInstance building)
	{
		List<BlockPos> out = new ArrayList<>();
		Set<Long> columns = new HashSet<>();
		Map<BlockPos, SpecialMarker> markers = building.getSchematic().getSpecialMarkers();
		if (markers != null)
		{
			for (Map.Entry<BlockPos, SpecialMarker> e : markers.entrySet())
			{
				if (e.getValue() == SpecialMarker.LIVING_POINT)
				{
					BlockPos l = e.getKey();
					BlockPos w = building.blueprintToWorld(l.getX(), l.getY(), l.getZ());

					if (columns.add(ControlBoxPersistence.columnKey(w.getX(), w.getZ())))
					{
						out.add(w);
					}
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
						PlacementSupport.fixDoubleDoor(level, worldPos);
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
			BlockState current = PhysicsWorld.getBlockState(level, worldPos);
			if (current.equals(building.placement().state(desired))) continue;
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
						BlueprintName.component(building.getSchematicName()),
						item.getDescription())));
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
				LifeSystem.tpl(Config.ANNOUNCE_BUILDING_COMPLETE,
						BlueprintName.component(building.getSchematicName()))));
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

	// 模盒位置（子世界内模盒为局部坐标，主世界模盒为世界坐标）
	private BlockPos boxPos()
	{
		BlockPos box = building.getConstructorPos();
		if (box == null) box = building.getControlBoxPos();
		return box;
	}

	// 模盒是否还在（主世界或子世界内）：被破坏则任务取消
	private boolean constructorBoxStillThere()
	{
		BlockPos box = boxPos();
		if (box == null) return true;
		return PhysicsWorld.getBlockState(level, box).getBlock()
				instanceof com.wenzai.neosim.block.BuildingConstructor;
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
		return com.wenzai.neosim.Config.isRestTime(level.getDayTime());
	}

	// 下班：回生活点
	private void goOffWork()
	{
		resolveBuilderNpc();
		if (builderNpc != null)
		{
			// 休息时间：解除待命，允许自己走回家
			builderNpc.setStayPut(false);
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
			if (box != null) builderNpc.setMoveTarget(PhysicsWorld.toWorld(level, box));
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

	// 按模盒坐标找雇佣的NPC实体（名字索引 O(1)）
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
				com.wenzai.neosim.npc.Manage.spawnSingle(level, city, workerName,
						PhysicsWorld.toWorld(level, box));
		if (npc != null)
		{
			// 恢复岗位（不是新雇佣）：保留当天休息日
			npc.restoreAssignedSite(PhysicsWorld.toWorld(level, box));
			PhysicsWorld.attachNpc(level, npc, box);
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

	public BuildingInstance.BuildState getState()
	{
		return currentState;
	}

	public void setState(BuildingInstance.BuildState state)
	{
		this.currentState = state;
		building.setState(state);
	}

	// GUI进度：两轮显示
	public int getProgress()
	{
		return phaseTwo ? schematic.getTotalVolume() + resumeIndex : resumeIndex;
	}

	public int getTotal()
	{
		return schematic.getTotalVolume() * 2;
	}

	public BuildingInstance getBuilding()
	{
		return building;
	}

	public boolean isPaused()
	{
		return paused;
	}

	public float getBuilderLevel()
	{
		return builderLevel;
	}

	// GUI状态页读取：当前阻塞建造的缺料
	public Item getLastMissingMaterial()
	{
		return lastMissingMaterial;
	}

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

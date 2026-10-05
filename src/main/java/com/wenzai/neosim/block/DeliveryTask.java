package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.building.ConstructionEngine;
import com.wenzai.neosim.building.ConstructionTask;
import com.wenzai.neosim.building.InventoryManager;
import com.wenzai.neosim.life.LifeSystem;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.npc.NpcGoals;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.SimData;
import com.wenzai.neosim.util.BlueprintName;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

// 快递盒配送任务：自有状态机（不继承 PlotTask——快递员需要走路，PlotTask 会把工人钉在盒子上方）
public class DeliveryTask
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final int WINDOW_REFRESH_TICKS = 20;

	// 工人实体缺失多久后触发兜底恢复（10 秒）
	private static final int WORKER_MISSING_TICKS = 200;

	// 站点窗口重扫间隔（5 秒）：箱链可能变长 / 换位置
	private static final int SITE_REFRESH_TICKS = 100;

	// 快递员经验：每投料 1 个物品 = 1 经验单位（不提供配置项）
	private static final int XP_PER_ITEM = 1;

	public enum DeliveryState
	{
		IDLE, WAITING_WORKER, WORKER_ASSIGNED, WALKING_TO_SITE, DEPOSITING, RETURNING;

		public static DeliveryState valueOfSafe(String name)
		{
			if (name == null || name.isEmpty()) return IDLE;
			for (DeliveryState s : values())
			{
				if (s.name().equals(name)) return s;
			}
			return IDLE;
		}
	}

	protected final ServerLevel level;
	protected final String cityName;
	protected DeliveryBoxPersistence.DeliveryBoxRecord record;
	protected DeliveryState state;
	protected boolean paused;
	protected float jobLevel = 1.0f;
	protected long lastOpTime;

	protected Entity worker;
	protected int workerMissingTicks;
	protected int windowTimer;
	protected int siteRefreshTimer;

	// 站点窗口（盒子 ±1 + 整条箱链）是否已登记
	protected boolean siteWindowRegistered;

	// 本次下班是否已经释放过窗口（避免每 tick 重复释放）
	protected boolean offDutyReleased;

	// 盒子方块已不存在（区块加载后校验得出）：由引擎清理任务与记录
	protected boolean boxGone;

	// 派单扫描节流计数（每 20 tick 扫一次全城缺料工地）
	protected int orderScanTicks;

	// 上次跳单原因（GUI 显示）
	protected Component lastSkipReason = Component.empty();

	// 当前订单（瞬态，不落盘）
	// 认领键：工地控制箱坐标
	protected BlockPos targetControl;

	// 走路目标：模盒 ?: 控制箱
	protected BlockPos targetSite;
	protected Item carryItem;
	protected int carryCount;
	protected long depositStartMs;

	public DeliveryTask(ServerLevel level, String cityName, DeliveryBoxPersistence.DeliveryBoxRecord record)
	{
		this.level = level;
		this.cityName = cityName;
		this.record = record;
		this.paused = record.paused();
		this.state = DeliveryState.valueOfSafe(record.state());

		// 恢复雇佣关系
		if (record.worker() != null && !record.worker().isEmpty())
		{
			NeoSim.WORKER_MAP.put(boxPos(), record.worker());
			DeliveryChunkLoader.registerBox(level, boxPos());
			siteWindowRegistered = true;

			// 等级从 NPC 读回：否则重启后 jobLevel 从 1 起算，会把高等级快递员反向写低
			Entity npc = Entity.findByNpcName(level, record.worker());
			if (npc != null)
			{
				jobLevel = Math.max(1.0F, (float) npc.getJobCourier());
			}
		}
	}

	// GUI 接口
	public BlockPos boxPos()
	{
		return record.boxPos();
	}

	public DeliveryBoxPersistence.DeliveryBoxRecord record()
	{
		return record;
	}

	public String cityName()
	{
		return cityName;
	}

	public DeliveryState getState()
	{
		return state;
	}

	public boolean isPaused()
	{
		return paused;
	}

	public float getJobLevel()
	{
		return jobLevel;
	}

	public String getWorkerName()
	{
		return record.worker() != null ? record.worker() : "";
	}

	public Component getLastSkipReason()
	{
		return lastSkipReason;
	}

	public Item getCarryItem()
	{
		return carryItem;
	}

	public int getCarryCount()
	{
		return carryCount;
	}

	public BlockPos getTargetSite()
	{
		return targetSite;
	}

	// 雇佣快递员
	public void hireWorker(String name)
	{
		if (name == null || name.isEmpty()) return;
		Entity npc = Entity.findByNpcName(level, name);
		if (npc != null)
		{
			NeoSim.WORKER_MAP.put(boxPos(), name);
			record = record.withWorker(name);
			npc.assignToSite(boxPos());
			setState(DeliveryState.WORKER_ASSIGNED);
			worker = npc;
			jobLevel = Math.max(1.0F, (float) npc.getJobCourier());
			updateRecord();
			DeliveryChunkLoader.registerBox(level, boxPos());
			siteWindowRegistered = true;
			LOGGER.info("NeoSim-DeliveryTask: hired courier '{}' for delivery box at {}", name, boxPos());
		}
	}

	// 解雇快递员
	public void fireWorker()
	{
		String name = NeoSim.WORKER_MAP.remove(boxPos());
		if (name != null)
		{
			releaseNpc(name);
		}
		record = record.withWorker(null);
		worker = null;
		setState(DeliveryState.WAITING_WORKER);
		clearHand();
		releaseOrder();
		DeliveryChunkLoader.releaseAll(level, boxPos());
		updateRecord();
		LOGGER.info("NeoSim-DeliveryTask: fired courier for delivery box at {}", boxPos());
	}

	public void setPaused(boolean p)
	{
		this.paused = p;
		record = record.withPaused(p);
		if (p)
		{
			// 暂停即下班：在途物品退回站点、订单释放（非工作不留物品栏）
			if (worker != null) worker.returnCarriage();
			releaseOrder();
			clearHand();
		}
		updateRecord();
	}

	// 盒子被破坏时清理：释放区块/认领、解雇快递员
	public void onBoxDestroyed()
	{
		DeliveryChunkLoader.releaseAll(level, boxPos());
		String name = NeoSim.WORKER_MAP.remove(boxPos());
		if (name != null) releaseNpc(name);
		worker = null;
		releaseOrder();
		clearHand();
	}

	// 每 tick 调度
	public void tick()
	{
		if (paused)
		{
			// 暂停：回站点待命，不接单
			if (hasWorker())
			{
				resolveWorkerNpc();
				if (worker != null && !NpcGoals.MoveToSiteGoal.isAboveSite(worker, boxPos()))
				{
					worker.setMoveTarget(boxPos());
				}
				clearHand();
			}
			return;
		}

		// 休息（夜间 / 抽到休息日）：正在配送则先送完再回家（防认领死锁与材料丢失）；
		// 没有在途货就把站点窗口一起放掉再回家——夜里/休息日不占区块
		if (isOffDuty() && !hasActiveOrder())
		{
			releaseSiteWindowOnce();
			if (worker != null) goOffWork();
			else restNewWorker();
			return;
		}
		offDutyReleased = false;

		// 上班：先确保站点窗口（含箱链）已登记且区块真正加载，再开始判定。
		// 注册→loaded 有 1~2 tick 延迟，跳过去会把"还没加载"误判成"站点不在岗/缺料"
		if (!ensureSiteWindowLoaded()) return;

		ensureWorkerAtSite();

		if (!hasWorker())
		{
			if (state != DeliveryState.WAITING_WORKER)
			{
				setState(DeliveryState.WAITING_WORKER);
				clearHand();
			}
			return;
		}

		if (state == DeliveryState.WAITING_WORKER)
		{
			setState(DeliveryState.WORKER_ASSIGNED);
			return;
		}

		if (state == DeliveryState.WORKER_ASSIGNED)
		{
			resolveWorkerNpc();
			if (worker != null && NpcGoals.MoveToSiteGoal.isAboveSite(worker, boxPos()))
			{
				workerMissingTicks = 0;
				worker.getNavigation().stop();
				worker.clearMoveTarget();
				setState(DeliveryState.IDLE);
			}
			else if (worker == null)
			{
				workerMissingTicks++;
				if (workerMissingTicks >= 200)
				{
					workerMissingTicks = 0;
					tryRestoreWorker();
				}
			}
			else
			{
				workerMissingTicks = 0;
			}
			return;
		}

		resolveWorkerNpc();
		if (worker == null)
		{
			// 工人实体消失（死亡 / 档案被删 / 重生成）：超时后先尝试从档案恢复；
			// 恢复不了就解雇并释放订单，避免工地认领被永久占住
			workerMissingTicks++;
			if (workerMissingTicks >= WORKER_MISSING_TICKS)
			{
				workerMissingTicks = 0;
				tryRestoreWorker();

				// 恢复不了（已死亡/档案被删）：tryRestoreWorker 内部已解雇回等待，
				// 这里必须再把订单释放掉，否则工地认领会被永久占住、其它快递盒接不了这一单
				if (worker == null)
				{
					releaseOrder();
					if (state != DeliveryState.WAITING_WORKER)
					{
						setState(DeliveryState.WAITING_WORKER);
					}
					clearHand();
					updateRecord();
					LOGGER.warn("NeoSim-DeliveryTask: worker missing, order released at {}", boxPos());
				}
			}
			return;
		}
		workerMissingTicks = 0;

		updateWindow();

		switch (state)
		{
			case IDLE ->
			{
				if (NpcGoals.MoveToSiteGoal.isAboveSite(worker, boxPos()))
				{
					worker.getNavigation().stop();
					worker.clearMoveTarget();
					takeOrderIfAny();
				}
				else
				{
					worker.setMoveTarget(boxPos());
				}
			}
			case WALKING_TO_SITE ->
			{
				if (targetSite == null)
				{
					// 异常恢复：回站点
					releaseOrder();
					setState(DeliveryState.RETURNING);
					worker.setMoveTarget(boxPos());
					return;
				}
				if (NpcGoals.MoveToSiteGoal.isAboveSite(worker, targetSite))
				{
					worker.getNavigation().stop();
					worker.clearMoveTarget();
					depositStartMs = System.currentTimeMillis();
					setState(DeliveryState.DEPOSITING);
				}
				else if (worker.getNavigation().isDone())
				{
					// 实体被卸载重生成后寻路目标会丢：这里补下达，避免订单永久卡在认领状态
					worker.setMoveTarget(targetSite);
				}
			}
			case DEPOSITING ->
			{
				long depositDelay = Math.max(200, (int) (2000 / Math.max(1.0F, jobLevel)));
				long elapsed = System.currentTimeMillis() - depositStartMs;
				if (elapsed < depositDelay)
				{
					worker.setBuildAnim(Math.min(1.0F, elapsed / (float) depositDelay));
					return;
				}
				worker.setBuildAnim(0.0F);
				performDeposit();
			}
			case RETURNING ->
			{
				if (NpcGoals.MoveToSiteGoal.isAboveSite(worker, boxPos()))
				{
					worker.getNavigation().stop();
					worker.clearMoveTarget();

					// 有单立即取料出发，无单转 IDLE
					takeOrderIfAny();
				}
			}
			default -> { }
		}
	}

	// 扫描全城缺料工地，取最近且未被认领的订单（每 20 tick 节流）
	private void takeOrderIfAny()
	{
		if (++orderScanTicks < WINDOW_REFRESH_TICKS) return;
		orderScanTicks = 0;

		List<ConstructionTask> waiting = ConstructionEngine.getWaitingTasks();
		if (waiting.isEmpty())
		{
			lastSkipReason = Component.empty();
			setState(DeliveryState.IDLE);
			return;
		}

		ConstructionTask best = null;
		BlockPos bestControl = null;
		double bestDist = Double.MAX_VALUE;
		for (ConstructionTask t : waiting)
		{
			BlockPos control = t.getBuilding().getControlBoxPos();
			if (control == null) continue;
			if (!cityName.equals(t.getBuilding().getCachedCity(level))) continue;
			if (DeliveryEngine.isClaimed(control)) continue;

			// 工地旁必须有箱子，否则投无可投
			if (siteChests(t.getBuilding().getControlBoxPos(),
					t.getBuilding().getConstructorPos()).isEmpty())
			{
				lastSkipReason = Component.translatable("msg.neosim.delivery.noChest");
				continue;
			}

			double d = boxPos().distSqr(control);
			if (d < bestDist)
			{
				bestDist = d;
				best = t;
				bestControl = control;
			}
		}
		if (best == null || bestControl == null)
		{
			setState(DeliveryState.IDLE);
			return;
		}

		Item item = best.getNextBlockItem();
		if (item == null)
		{
			setState(DeliveryState.IDLE);
			return;
		}

		// 单趟运力 = 快递员等级格数 × 64（非快递工作状态没有物品栏 → 不接单）
		int slots = worker.carriageSlots();
		if (slots <= 0)
		{
			setState(DeliveryState.IDLE);
			return;
		}

		// 站点库存、工地缺口、单趟运力三者取最小
		// 站点库存：读取与快递盒相连的整条箱链
		List<ChestBlockEntity> stationChests = InventoryManager.findChainedChests(level, boxPos());
		int stock = InventoryManager.countItems(stationChests, item);
		if (stock <= 0)
		{
			lastSkipReason = Component.translatable("msg.neosim.delivery.stationMissing", item.getDescription());
			setState(DeliveryState.IDLE);
			return;
		}
		int need = Math.max(1, best.getMissingCount(item));
		int batch = Math.min(Math.min(stock, need), slots * Entity.CARRIAGE_SLOT_SIZE);

		DeliveryEngine.claim(bestControl, boxPos());
		int taken = InventoryManager.extractItem(stationChests, item, batch);
		if (taken <= 0)
		{
			DeliveryEngine.releaseClaim(bestControl, boxPos());
			setState(DeliveryState.IDLE);
			return;
		}

		// 装进快递员物品栏；格数不足的差额塞回站点箱子（batch 已按运力裁剪，这里只是保险）
		int loaded = worker.loadCarriage(new ItemStack(item, taken), slots);
		if (loaded < taken)
		{
			InventoryManager.depositItems(stationChests, new ItemStack(item, taken - loaded));
		}
		if (loaded <= 0)
		{
			DeliveryEngine.releaseClaim(bestControl, boxPos());
			setState(DeliveryState.IDLE);
			return;
		}

		targetControl = bestControl;
		targetSite = best.getBuilding().getConstructorPos() != null
				? best.getBuilding().getConstructorPos() : bestControl;
		carryItem = item;
		carryCount = loaded;
		lastSkipReason = Component.empty();

		// 手持形象：显示物品栏第一格
		worker.setItemInHand(InteractionHand.MAIN_HAND, worker.getCarriageDisplay());

		// 城市公告：XXX 正前往 XXX 运送 XX 个 XXX
		Component buildingName = BlueprintName.component(best.getBuilding().getSchematicName());
		LifeSystem.announce(level, cityName,
				LifeSystem.tpl(Config.ANNOUNCE_DELIVERY_DISPATCH,
						worker.getNpcName(), buildingName, loaded,
						item.getDescription()));

		worker.setMoveTarget(targetSite);
		setState(DeliveryState.WALKING_TO_SITE);
		LOGGER.info("NeoSim-DeliveryTask: '{}' delivering {}x{} to {} (city {})",
				worker.getNpcName(), loaded, item.getDescription().getString(),
				buildingName, cityName);
	}

	// 投料完成：物品栏入箱 → 按件扣款 → 按件给经验 → 释放认领 → 回站点
	private void performDeposit()
	{
		int delivered = 0;
		if (worker != null && !worker.isCarriageEmpty())
		{
			List<ChestBlockEntity> chests = siteChests(targetControl, targetSite);
			if (!chests.isEmpty())
			{
				for (ItemStack stack : worker.getCarriage())
				{
					if (stack.isEmpty()) continue;
					InventoryManager.depositItems(chests, stack.copy());
					delivered += stack.getCount();
				}
				worker.clearCarriage();
			}
			else
			{
				// 工地旁没有箱子：原路退回站点，不掉落
				worker.returnCarriage();
			}
		}

		if (delivered > 0)
		{
			// 按件扣款（非创造）+ 每投料 1 个物品 = 1 经验单位
			deductCredits(delivered * Config.DELIVERY_CREDIT_PER_UNIT.get());
			gainXp(delivered * XP_PER_ITEM);
		}

		if (worker != null) worker.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		releaseOrder();
		setState(DeliveryState.RETURNING);
		worker.setMoveTarget(boxPos());
	}

	// 工地旁的箱子：控制箱 6 邻面 ∪ 模盒 6 邻面（去重）
	// 注意：这里刻意不用箱链——工地侧是建筑控制箱/模盒，非快递盒方块，按规则只能读紧邻 6 面的箱子
	private List<ChestBlockEntity> siteChests(BlockPos control, BlockPos constructor)
	{
		List<ChestBlockEntity> chests = new ArrayList<>(InventoryManager.findNearbyChests(level, control));
		if (constructor != null && !constructor.equals(control))
		{
			for (ChestBlockEntity chest : InventoryManager.findNearbyChests(level, constructor))
			{
				if (!chests.contains(chest))
				{
					chests.add(chest);
				}
			}
		}
		return chests;
	}

	// 释放当前订单（认领 + 字段清零）；在途物品原路退回站点，不掉落
	private void releaseOrder()
	{
		if (targetControl != null)
		{
			DeliveryEngine.releaseClaim(targetControl, boxPos());
		}
		if (worker != null) worker.returnCarriage();
		targetControl = null;
		targetSite = null;
		carryItem = null;
		carryCount = 0;
	}

	// 是否有进行中的订单（认领未释放）
	private boolean hasActiveOrder()
	{
		return targetControl != null;
	}

	// 通用辅助
	protected void setState(DeliveryState s)
	{
		if (this.state == s) return;
		this.state = s;
		record = record.withState(s.name());
	}

	protected void updateRecord()
	{
		DeliveryBoxPersistence.updateRecord(level, cityName, record);
	}

	protected boolean hasWorker()
	{
		String name = NeoSim.WORKER_MAP.get(boxPos());
		return name != null && !name.isEmpty();
	}

	protected boolean isNightTime()
	{
		return com.wenzai.neosim.Config.isRestTime(level.getDayTime());
	}

	// 现在是否该下班：夜间或抽到休息日都算；工人实体不可见时退回按时间判断
	private boolean isOffDuty()
	{
		resolveWorkerNpc();
		return worker != null ? worker.isRestingNow() : isNightTime();
	}

	// 盒子方块是否已不存在（引擎据此清理任务）
	public boolean isBoxGone()
	{
		return boxGone;
	}

	// 确保站点窗口（盒子 ±1 + 整条箱链）已登记、已真正加载，并校验盒子方块还在
	// - 返回 false 表示本 tick 还不能开始判定（等下一 tick）
	// - 注册 → loaded 有 1~2 tick 延迟：先等加载再读方块，既不触发同步加载，
	//   也不会把"还没加载"误判成"盒子没了"
	// - 每 SITE_REFRESH_TICKS 重扫一次：箱链变长 / 换位置后窗口跟上（顺带复验盒子）
	private boolean ensureSiteWindowLoaded()
	{
		boolean revalidated = false;
		if (!siteWindowRegistered || ++siteRefreshTimer >= SITE_REFRESH_TICKS)
		{
			siteRefreshTimer = 0;
			DeliveryChunkLoader.registerBox(level, boxPos());
			siteWindowRegistered = true;
			revalidated = true;
		}

		if (!level.hasChunkAt(boxPos())) return false;

		if (revalidated && !(level.getBlockState(boxPos()).getBlock() instanceof DeliveryBox))
		{
			boxGone = true;
			LOGGER.warn("NeoSim-DeliveryTask: delivery box gone at {}", boxPos());
			return false;
		}
		return true;
	}

	// 下班：站点窗口与滚动窗口一起放掉（重复调用无副作用）
	private void releaseSiteWindowOnce()
	{
		if (offDutyReleased) return;
		offDutyReleased = true;
		DeliveryChunkLoader.releaseAll(level, boxPos());
		siteWindowRegistered = false;
		siteRefreshTimer = 0;
	}

	// 下班：回生活点
	private void goOffWork()
	{
		resolveWorkerNpc();
		if (worker != null)
		{
			BlockPos home = worker.getHomePos();
			worker.setMoveTarget(home != null ? home : boxPos());
		}
		clearHand();
	}

	// 夜晚入职的快递员：当晚不前往
	private void restNewWorker()
	{
		String name = NeoSim.WORKER_MAP.get(boxPos());
		if (name == null || name.isEmpty()) return;
		Entity npc = Entity.findByNpcName(level, name);
		if (npc != null)
		{
			BlockPos home = npc.getHomePos();
			if (home != null) npc.setMoveTarget(home);
			else npc.clearMoveTarget();
		}
	}

	// 让快递员回站点（仅无进行中订单时；配送腿目标由订单控制，不得覆盖）
	private void ensureWorkerAtSite()
	{
		resolveWorkerNpc();
		if (worker != null && !hasActiveOrder())
		{
			// 产假：孕期NPC白天不返工
			if (worker.getPregnancyStage() > 0.0F) return;
			worker.setMoveTarget(boxPos());
		}
	}

	// 按盒子坐标找雇佣的快递员实体
	private void resolveWorkerNpc()
	{
		if (worker != null && worker.isAlive()) return;
		worker = null;
		String name = NeoSim.WORKER_MAP.get(boxPos());
		if (name == null || name.isEmpty()) return;

		worker = Entity.findByNpcName(level, name);
		if (worker != null)
		{
			// 重新解析工人时同步等级（与农业盒/矿业盒同款，避免用旧等级继续作业）
			jobLevel = Math.max(1.0F, (float) worker.getJobCourier());
		}
	}

	private void tryRestoreWorker()
	{
		String name = NeoSim.WORKER_MAP.get(boxPos());
		if (name == null || name.isEmpty()) return;
		Entity npc = Manage.spawnSingle(level, cityName, name, boxPos());
		if (npc != null)
		{
			// 恢复岗位（不是新雇佣）：保留当天休息日
			npc.restoreAssignedSite(boxPos());
			worker = npc;
			LOGGER.info("NeoSim-DeliveryTask: courier '{}' restored to delivery box at {}", name, boxPos());
		}
		else
		{
			// 已死亡：解雇，回到等待
			NeoSim.WORKER_MAP.remove(boxPos());
			record = record.withWorker(null);
			setState(DeliveryState.WAITING_WORKER);
			updateRecord();
			LOGGER.warn("NeoSim-DeliveryTask: courier '{}' gone (file deleted), box back to waiting", name);
		}
	}

	protected void releaseNpc(String name)
	{
		// 全图按名查找：限半径会漏掉离家/远走的快递员，导致其AI永不恢复
		Entity npc = Entity.findByNpcName(level, name);
		if (npc != null)
		{
			npc.releaseFromSite();
			npc.setBuildAnim(0.0F);
			npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		}
	}

	// 手持
	protected void clearHand()
	{
		resolveWorkerNpc();
		if (worker != null)
		{
			worker.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			worker.setBuildAnim(0.0F);
		}
	}

	// 滚动区块窗口：每 20 tick 跟随快递员刷新
	private void updateWindow()
	{
		windowTimer++;
		if (windowTimer >= WINDOW_REFRESH_TICKS)
		{
			windowTimer = 0;
			DeliveryChunkLoader.setWindow(level, boxPos(),
					worker != null ? worker.blockPosition() : null);
		}
	}

	// 从城市资金中按件扣款
	protected void deductCredits(double amount)
	{
		if (amount <= 0 || level.getServer() == null) return;
		if (ModSavedData.get(level).getMode() == 2) return;
		SimData.CityData data = SimData.CityData.read(level, cityName);
		double now = data.credit() - amount;
		if (now < 0) now = 0;
		SimData.CityData.write(level, cityName, data.withCredit(now));
		ModSavedData.get(level).syncCityToClients(level, cityName);
	}

	// 快递员技能成长：每投料 1 件 = 1 个经验单位；跨级时刷新工作速度加成（曲线 A 只作用于快递员工作时）
	protected void gainXp(int units)
	{
		if (units <= 0) return;
		int before = (int) Math.floor(jobLevel);
		jobLevel = Entity.addJobXp(worker, Entity.JobKind.COURIER, jobLevel, units);
		if ((int) Math.floor(jobLevel) > before && worker != null)
		{
			worker.refreshWalkSpeed();
		}
	}
}

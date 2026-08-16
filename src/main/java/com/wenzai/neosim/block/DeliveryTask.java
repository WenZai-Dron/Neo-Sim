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
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

// 快递盒配送任务：自有状态机（不继承 PlotTask——快递员需要走路，PlotTask 会把工人钉在盒子上方）
public class DeliveryTask
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final float MAX_LEVEL = 10.0f;
	private static final int WINDOW_REFRESH_TICKS = 20;

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

	// 上次跳单原因（GUI 显示）
	protected String lastSkipReason = "";

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
		}
	}

	// GUI 接口
	public BlockPos boxPos() { return record.boxPos(); }
	public DeliveryBoxPersistence.DeliveryBoxRecord record() { return record; }
	public String cityName() { return cityName; }
	public DeliveryState getState() { return state; }
	public boolean isPaused() { return paused; }
	public float getJobLevel() { return jobLevel; }
	public String getWorkerName() { return record.worker() != null ? record.worker() : ""; }
	public String getLastSkipReason() { return lastSkipReason; }
	public Item getCarryItem() { return carryItem; }
	public int getCarryCount() { return carryCount; }
	public BlockPos getTargetSite() { return targetSite; }

	// 雇佣快递员
	public void hireWorker(String name)
	{
		if (name == null || name.isEmpty()) return;
		for (Entity npc : level.getEntitiesOfClass(Entity.class, new AABB(boxPos()).inflate(64.0D)))
		{
			if (name.equals(npc.getNpcName()))
			{
				NeoSim.WORKER_MAP.put(boxPos(), name);
				record = record.withWorker(name);
				npc.assignToSite(boxPos());
				setState(DeliveryState.WORKER_ASSIGNED);
				worker = npc;
				jobLevel = Math.max(1.0F, (float) npc.getJobCourier());
				updateRecord();
				DeliveryChunkLoader.registerBox(level, boxPos());
				LOGGER.info("NeoSim-DeliveryTask: hired courier '{}' for delivery box at {}", name, boxPos());
				return;
			}
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
		if (p) clearHand();
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

		// 夜间：正在配送则先送完再回家（防认领死锁与材料丢失），否则回家休息
		if (isNightTime() && !hasActiveOrder())
		{
			if (worker != null) goOffWork();
			else restNewWorker();
			return;
		}
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
		if (worker == null) return;

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

	// 扫描全城缺料工地，取最近且未被认领的订单
	private void takeOrderIfAny()
	{
		List<ConstructionTask> waiting = ConstructionEngine.getWaitingTasks();
		if (waiting.isEmpty())
		{
			lastSkipReason = "";
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
			if (!cityName.equals(ConstructionTask.cityOf(t.getBuilding(), level))) continue;
			if (DeliveryEngine.isClaimed(control)) continue;

			// 工地旁必须有箱子，否则投无可投
			if (siteChests(t.getBuilding().getControlBoxPos(),
					t.getBuilding().getConstructorPos()).isEmpty())
			{
				lastSkipReason = "工地无箱子";
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

		// 站点库存与剩余缺口取较小值：当下需要多少就拿多少
		List<ChestBlockEntity> stationChests = InventoryManager.findNearbyChests(level, boxPos());
		int stock = InventoryManager.countItems(stationChests, item);
		if (stock <= 0)
		{
			lastSkipReason = "站点缺少 " + item.getDescription().getString();
			setState(DeliveryState.IDLE);
			return;
		}
		int need = Math.max(1, best.getMissingCount(item));
		int batch = Math.min(stock, need);

		DeliveryEngine.claim(bestControl, boxPos());
		int taken = InventoryManager.extractItem(stationChests, item, batch);
		if (taken <= 0)
		{
			DeliveryEngine.releaseClaim(bestControl, boxPos());
			setState(DeliveryState.IDLE);
			return;
		}

		targetControl = bestControl;
		targetSite = best.getBuilding().getConstructorPos() != null
				? best.getBuilding().getConstructorPos() : bestControl;
		carryItem = item;
		carryCount = taken;
		lastSkipReason = "";

		// 手持形象：超一组只拿前 64 个（投料按实际数量）
		worker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item, Math.min(taken, 64)));

		// 城市公告：XXX 正前往 XXX 运送 XX 个 XXX
		String buildingName = best.getBuilding().getSchematicName();
		LifeSystem.announce(level, cityName,
				LifeSystem.tpl(Config.ANNOUNCE_DELIVERY_DISPATCH,
						worker.getNpcName(), buildingName, taken,
						item.getDescription().getString()));

		worker.setMoveTarget(targetSite);
		setState(DeliveryState.WALKING_TO_SITE);
		LOGGER.info("NeoSim-DeliveryTask: '{}' delivering {}x{} to {} (city {})",
				worker.getNpcName(), taken, item.getDescription().getString(),
				buildingName, cityName);
	}

	// 投料完成：入箱 → 扣款 → 经验 → 释放认领 → 回站点
	private void performDeposit()
	{
		if (carryItem != null && carryCount > 0)
		{
			List<ChestBlockEntity> chests = siteChests(targetControl, targetSite);
			if (!chests.isEmpty())
			{
				int remaining = carryCount;
				while (remaining > 0)
				{
					int chunk = Math.min(64, remaining);
					InventoryManager.depositItems(chests, new ItemStack(carryItem, chunk));
					remaining -= chunk;
				}
				// 按件扣款（非创造）
				deductCredits(carryCount * Config.DELIVERY_CREDIT_PER_UNIT.get());
				gainXp();
			}
		}
		releaseOrder();
		setState(DeliveryState.RETURNING);
		worker.setMoveTarget(boxPos());
	}

	// 工地旁的箱子：控制箱 6 邻面 ∪ 模盒 6 邻面（去重）
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

	// 释放当前订单（认领 + 字段清零）
	private void releaseOrder()
	{
		if (targetControl != null)
		{
			DeliveryEngine.releaseClaim(targetControl, boxPos());
		}
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
		return level.getDayTime() % 24000 >= 12000;
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
		for (net.minecraft.world.entity.Entity e : level.getAllEntities())
		{
			if (e instanceof Entity npc && name.equals(npc.getNpcName()))
			{
				BlockPos home = npc.getHomePos();
				if (home != null) npc.setMoveTarget(home);
				else npc.clearMoveTarget();
				return;
			}
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

		for (Entity npc : level.getEntitiesOfClass(Entity.class, new AABB(boxPos()).inflate(64.0D)))
		{
			if (name.equals(npc.getNpcName()))
			{
				worker = npc;
				return;
			}
		}
	}

	private boolean workerExistsInLevel(String name)
	{
		for (net.minecraft.world.entity.Entity e : level.getAllEntities())
		{
			if (e instanceof Entity npc && name.equals(npc.getNpcName()))
			{
				return true;
			}
		}
		return false;
	}

	private void tryRestoreWorker()
	{
		String name = NeoSim.WORKER_MAP.get(boxPos());
		if (name == null || name.isEmpty()) return;
		Entity npc = Manage.spawnSingle(level, cityName, name, boxPos());
		if (npc != null)
		{
			npc.assignToSite(boxPos());
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

	// 快递员技能成长（等级越高投料越快，配送经验跨级写 job.courier）
	protected void gainXp()
	{
		int b4 = (int) Math.floor(jobLevel);
		if (jobLevel < MAX_LEVEL)
		{
			jobLevel += 0.001f / Math.max(1, b4);
		}
		int aft = (int) Math.floor(jobLevel);
		if (aft > b4)
		{
			resolveWorkerNpc();
			if (worker != null)
			{
				worker.setJobCourier((byte) Math.min(aft, (int) MAX_LEVEL));
				worker.syncToJson();
			}
		}
	}
}

package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.npc.NpcGoals;
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

// 工作盒任务抽象基类：工人调度/扣款/技能成长/手持工具/区块加载
public abstract class PlotTask
{
	private static final Logger LOGGER = LogUtils.getLogger();

	protected static final int BASE_DELAY = 2000;
	protected static final float MAX_LEVEL = 10.0f;

	// 抬手动画时长
	private static final int RAISE_ANIM_MS = 400;
	private static final int LOWER_ANIM_MS = 400;

	// 作业阶段
	public enum PlotState
	{
		IDLE, WORKER_ASSIGNED, WAITING_WORKER, ARRIVED, CHECKING_CHESTS,
		HARVEST, TILL, PLANT, WAITING_SEED,
		RAISE, WAITING_FEED,
		MINING, WAITING_FOR_CHEST, DEPLETED;

		// 从文件读取状态名，非法名回退IDLE（好像有问题）
		public static PlotState valueOfSafe(String name)
		{
			if (name == null || name.isEmpty()) return IDLE;
			for (PlotState s : values())
			{
				if (s.name().equals(name)) return s;
			}
			return IDLE;
		}
	}

	protected final ServerLevel level;
	protected final String cityName;
	protected WorkBoxPersistence.WorkBoxRecord record;
	protected PlotState state;
	protected boolean paused;
	protected float jobLevel = 1.0f;
	protected int runDelay = BASE_DELAY;
	protected long lastOpTime;

	// 抬手动画计时
	private long animStartTime;

	protected Entity worker;
	protected List<ChestBlockEntity> nearbyChests = new ArrayList<>();
	protected int workerMissingTicks;
	protected boolean chunksLoaded;

	public PlotTask(ServerLevel level, String cityName, WorkBoxPersistence.WorkBoxRecord record)
	{
		this.level = level;
		this.cityName = cityName;
		this.record = record;
		this.paused = record.paused();
		this.state = PlotState.valueOfSafe(record.state());

		// 恢复雇佣关系
		if (record.worker() != null && !record.worker().isEmpty())
		{
			NeoSim.WORKER_MAP.put(boxPos(), record.worker());
		}
	}

	// GUI调用：接口
	public BlockPos boxPos() { return record.boxPos(); }
	public WorkBoxPersistence.WorkBoxRecord record() { return record; }
	public String cityName() { return cityName; }
	public PlotState getState() { return state; }
	public boolean isPaused() { return paused; }
	public float getJobLevel() { return jobLevel; }
	public String getWorkerName() { return record.worker() != null ? record.worker() : ""; }

	// 雇佣工人
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
				setState(PlotState.WORKER_ASSIGNED);
				worker = npc;
				jobLevel = Math.max(1.0F, jobLevelOf(npc));
				updateSpeed(jobLevel);
				updateRecord();
				LOGGER.info("NeoSim-PlotTask: hired '{}' for work box at {}", name, boxPos());
				return;
			}
		}
	}

	// 解雇工人
	public void fireWorker()
	{
		String name = NeoSim.WORKER_MAP.remove(boxPos());
		if (name != null)
		{
			releaseNpc(name);
		}
		record = record.withWorker(null);
		worker = null;
		setState(PlotState.WAITING_WORKER);
		clearHand();
		updateRecord();
		LOGGER.info("NeoSim-PlotTask: fired worker for work box at {}", boxPos());
	}

	public void setPaused(boolean p)
	{
		this.paused = p;
		record = record.withPaused(p);
		if (p) clearHand();
		else setHandTool();
		updateRecord();
	}

	// 盒子被破坏时清理：释放区块、解雇工人
	public void onBoxDestroyed()
	{
		releaseChunks();
		String name = NeoSim.WORKER_MAP.remove(boxPos());
		if (name != null) releaseNpc(name);
		worker = null;
		clearHand();
	}

	// 每tick调度
	public void tick()
	{
		if (paused) return;
		updateChunkLoading();

		if (state == PlotState.DEPLETED) return;

		if (isNightTime())
		{
			if (isWorkerOnShift()) goOffWork();
			else restNewWorker();
			return;
		}
		ensureWorkerAtSite();

		if (!hasWorker())
		{
			if (state != PlotState.WAITING_WORKER)
			{
				setState(PlotState.WAITING_WORKER);
				clearHand();
			}
			return;
		}

		if (state == PlotState.WAITING_WORKER)
		{
			setState(PlotState.WORKER_ASSIGNED);
			return;
		}

		if (state == PlotState.WORKER_ASSIGNED)
		{
			resolveWorkerNpc();
			if (worker != null && NpcGoals.MoveToSiteGoal.isAboveSite(worker, boxPos()))
			{
				workerMissingTicks = 0;
				setState(PlotState.ARRIVED);
				onArrived();
			}
			else if (worker == null)
			{
				workerMissingTicks++;
				if (workerMissingTicks >= 200)
				{
					workerMissingTicks = 0;
					String name = NeoSim.WORKER_MAP.get(boxPos());
					if (name != null && !name.isEmpty() && !workerExistsInLevel(name))
					{
						tryRestoreWorker();
					}
				}
			}
			else
			{
				workerMissingTicks = 0;
			}
			return;
		}

		// 工作阶段
		if (state == PlotState.CHECKING_CHESTS)
		{
			nearbyChests = com.wenzai.neosim.building.InventoryManager.findNearbyChests(level, boxPos());
			if (nearbyChests.isEmpty())
			{
				clearHand();
				return;
			}
			onChestsReady();
			return;
		}

		// 工作时站立在盒子正上方，每tick停住导航
		if (hasWorker())
		{
			resolveWorkerNpc();
			if (worker != null)
			{
				if (NpcGoals.MoveToSiteGoal.isAboveSite(worker, boxPos()))
				{
					worker.getNavigation().stop();

					// 回到正上方
					if (isWorkingState() && worker.getMainHandItem().isEmpty())
					{
						setHandTool();
					}
				}
				else
				{
					// 非工作：清空手持、放下手臂
					clearHand();
					return;
				}
			}
		}
		subclassWorkTick();
	}

	// 到达站点后的首个状态
	protected void onArrived()
	{
		setState(PlotState.CHECKING_CHESTS);
		setHandTool();
	}

	// 查箱子通过后进入的具体工作状态
	protected abstract void onChestsReady();

	// 工作阶段状态机
	protected abstract void subclassWorkTick();

	// 工作时手持工具
	protected abstract Item handItem();

	// 从NPC读取本职业等级
	protected abstract byte jobLevelOf(Entity npc);

	// 升级写回NPC职业等级
	protected abstract void setNpcJobLevel(Entity npc, int lvl);

	// 通用辅助
	protected void setState(PlotState s)
	{
		if (this.state == s) return;
		this.state = s;
		record = record.withState(s.name());
	}

	protected void updateRecord()
	{
		WorkBoxPersistence.updateRecord(level, cityName, record);
	}

	// 防删改：格子Y越出本维度世界高度（setBlock越界会崩服），调用方应跳过该格
	protected boolean cellOutsideBuildHeight(BlockPos pos)
	{
		return pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight();
	}

	// 抬手动画驱动。返回true表示未到出手时刻，本tick不做工
	protected boolean animateHand(long now)
	{
		resolveWorkerNpc();
		if (worker != null && !NpcGoals.MoveToSiteGoal.isAboveSite(worker, boxPos()))
		{
			clearHand();
			return true;
		}
		// 创造模式：不等待动作延迟，每 tick 执行一个动作（对齐建筑模盒）
		if (currentMode() == 2)
		{
			setHandAnim(1.0F);
			return false;
		}
		long elapsed = now - animStartTime;
		if (elapsed < runDelay)
		{
			if (elapsed < LOWER_ANIM_MS)
			{
				setHandAnim(1.0F - elapsed / (float) LOWER_ANIM_MS);
			}
			else
			{
				long raiseStart = Math.max(0, runDelay - RAISE_ANIM_MS);
				if (elapsed < raiseStart)
				{
					setHandAnim(0.0F);
				}
				else
				{
					setHandAnim(Math.min(1.0F, (elapsed - raiseStart) / (float) RAISE_ANIM_MS));
				}
			}
			return true;
		}
		animStartTime = now;
		lastOpTime = now;
		setHandAnim(1.0F);
		return false;
	}

	// 设置NPC手臂抬起程度
	protected void setHandAnim(float value)
	{
		resolveWorkerNpc();
		if (worker != null)
		{
			worker.setBuildAnim(value);
		}
	}

	protected boolean hasWorker()
	{
		String name = NeoSim.WORKER_MAP.get(boxPos());
		return name != null && !name.isEmpty();
	}

	// 是否处于实际作业状态
	protected boolean isWorkingState()
	{
		return state == PlotState.HARVEST
				|| state == PlotState.TILL
				|| state == PlotState.PLANT
				|| state == PlotState.RAISE
				|| state == PlotState.MINING;
	}

	protected boolean isWorkerOnShift()
	{
		return state == PlotState.ARRIVED
				|| state == PlotState.CHECKING_CHESTS
				|| state == PlotState.HARVEST
				|| state == PlotState.TILL
				|| state == PlotState.PLANT
				|| state == PlotState.WAITING_SEED
				|| state == PlotState.RAISE
				|| state == PlotState.WAITING_FEED
				|| state == PlotState.MINING
				|| state == PlotState.WAITING_FOR_CHEST;
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
			if (home != null) worker.setMoveTarget(home);
			else worker.setMoveTarget(boxPos());
		}

		// 非工作：清空手持
		clearHand();
	}

	// 夜晚入职的工人：当晚不前往
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

	private void ensureWorkerAtSite()
	{
		resolveWorkerNpc();
		if (worker != null)
		{
			// 产假：孕期NPC白天不返工
			if (worker.getPregnancyStage() > 0.0F) return;
			worker.setMoveTarget(boxPos());
		}
	}

	// 按盒子坐标找雇佣的NPC实体，找到后从NPC读取职业等级
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
				float lvl = Math.max(1.0F, (float) jobLevelOf(npc));
				if (lvl != jobLevel)
				{
					updateSpeed(lvl);
				}
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
			LOGGER.info("NeoSim-PlotTask: worker '{}' restored to work box at {}", name, boxPos());
		}
		else
		{
			// 已死亡：解雇，回到等待
			NeoSim.WORKER_MAP.remove(boxPos());
			record = record.withWorker(null);
			setState(PlotState.WAITING_WORKER);
			updateRecord();
			LOGGER.warn("NeoSim-PlotTask: worker '{}' gone (file deleted), work box back to waiting", name);
		}
	}

	protected void releaseNpc(String name)
	{
		// 全图按名查找：限半径会漏掉离家/远走的工人，导致其AI永不恢复
		Entity npc = Entity.findByNpcName(level, name);
		if (npc != null)
		{
			npc.releaseFromSite();
			npc.setBuildAnim(0.0F);
			npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		}
	}

	// 区块加载：有NPC作业即加载，解雇/触底/拆除后释放
	private void updateChunkLoading()
	{
		boolean shouldLoad = hasWorker() && state != PlotState.DEPLETED && record.bound();
		if (shouldLoad && !chunksLoaded)
		{
			PlotChunkLoader.registerForPlot(level, record);
			chunksLoaded = true;
		}
		else if (!shouldLoad && chunksLoaded)
		{
			PlotChunkLoader.releaseForPlot(level, record);
			chunksLoaded = false;
		}
	}

	protected void releaseChunks()
	{
		if (chunksLoaded)
		{
			PlotChunkLoader.releaseForPlot(level, record);
			chunksLoaded = false;
		}
	}

	// 手持工具
	protected void setHandTool()
	{
		Item item = handItem();
		if (item == null) return;
		resolveWorkerNpc();
		if (worker != null)
		{
			worker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
		}
	}

	protected void clearHand()
	{
		resolveWorkerNpc();
		if (worker != null)
		{
			worker.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			worker.setBuildAnim(0.0F);
		}
	}

	// 作业速度随职业等级
	protected void updateSpeed(float lvl)
	{
		this.jobLevel = lvl;
		this.runDelay = Math.max(1, (int) (BASE_DELAY / lvl));
	}

	// 从城市资金中扣除作业工资
	protected void deductCredits(double amount)
	{
		if (amount <= 0 || level.getServer() == null) return;
		if (com.wenzai.neosim.storage.ModSavedData.get(level).getMode() == 2) return;
		com.wenzai.neosim.storage.SimData.CityData data = com.wenzai.neosim.storage.SimData.CityData.read(level, cityName);
		double now = data.credit() - amount;
		if (now < 0) now = 0;
		com.wenzai.neosim.storage.SimData.CityData.write(level, cityName, data.withCredit(now));
		com.wenzai.neosim.storage.ModSavedData.get(level).syncCityToClients(level, cityName);
	}

	// 技能成长
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
				setNpcJobLevel(worker, Math.min(aft, (int) MAX_LEVEL));
				worker.syncToJson();
			}
		}
		updateSpeed(jobLevel);
	}

	// 当前运行模式
	protected byte currentMode()
	{
		return com.wenzai.neosim.storage.ModSavedData.get(level).getMode();
	}
}

package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.building.InventoryManager;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.npc.NpcGoals;
import com.wenzai.neosim.storage.FileCreater;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

// 整地任务状态机：复用模盒已雇佣的建筑师，逐块整地
public class TerraformTask
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final int BASE_DELAY = 2000;
	private static final float MAX_LEVEL = 10.0f;
	private static final int RAISE_ANIM_MS = 400;
	private static final int LOWER_ANIM_MS = 400;
	private static final int RESOURCE_RECHECK_TICKS = 60; // 3 秒

	public enum TerraformState
	{
		IDLE, WAITING_WORKER, WORKER_ASSIGNED, ARRIVED, CHECKING_CHESTS,
		SCAN, TERRAFORMING, WAITING_RESOURCE, COMPLETE;

		// 从文件读取状态名，非法名回退 IDLE
		public static TerraformState valueOfSafe(String name)
		{
			if (name == null || name.isEmpty()) return IDLE;
			for (TerraformState s : values())
			{
				if (s.name().equals(name)) return s;
			}
			return IDLE;
		}
	}

	private final ServerLevel level;
	private final String cityName;
	private TerraformPersistence.TerraformRecord record;
	private final List<BlockPos> targets = new ArrayList<>();
	private TerraformState state;
	private boolean paused;
	private float jobLevel = 1.0f;
	private int runDelay = BASE_DELAY;
	private long animStartTime;
	private long lastOpTime;
	private int processedIndex;
	private int totalTargets;
	private Entity worker;
	private List<ChestBlockEntity> nearbyChests = new ArrayList<>();
	private int workerMissingTicks;
	private boolean chunksLoaded;
	private Item lastMissingItem;
	private int resourceWaitTicks;
	private boolean chestNoticeSent;

	public TerraformTask(ServerLevel level, String cityName,
			TerraformPersistence.TerraformRecord record, List<BlockPos> targets)
	{
		this.level = level;
		this.cityName = cityName;
		this.record = record;
		this.state = TerraformState.valueOfSafe(record.state());
		this.paused = record.paused();
		this.processedIndex = record.progress();
		this.totalTargets = targets.size();
		this.targets.addAll(targets);

		// 恢复雇佣关系
		if (record.worker() != null && !record.worker().isEmpty())
		{
			NeoSim.WORKER_MAP.put(boxPos(), record.worker());
		}
	}

	// GUI 读接口
	public BlockPos boxPos()
	{
		return record.boxPos();
	}

	public TerraformPersistence.TerraformRecord record()
	{
		return record;
	}

	public String cityName()
	{
		return cityName;
	}

	public TerraformState getState()
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

	public int getProgress()
	{
		return processedIndex;
	}

	public int getTotal()
	{
		return totalTargets;
	}

	public TerraformPlan getPlan()
	{
		return TerraformPlan.valueOfSafe(record.plan());
	}

	// 重启恢复：重新扫描目标并从头开始（扫描天然只收集剩余目标）
	public void resetForRestore()
	{
		this.processedIndex = 0;
		this.state = TerraformState.SCAN;
		updateRecord();
	}

	public void setPaused(boolean p)
	{
		this.paused = p;
		record = record.withPaused(p);
		if (p) clearHand();
		updateRecord();
	}

	// 盒子被破坏时清理：释放区块（工人由 BreakHandler 统一解雇）
	public void onBoxDestroyed()
	{
		releaseChunks();
		clearHand();
	}

	// 每 tick 调度
	public void tick()
	{
		if (paused) return;
		updateChunkLoading();
		if (state == TerraformState.COMPLETE) return;

		if (isNightTime())
		{
			if (isWorkerOnShift()) goOffWork();
			else restNewWorker();
			return;
		}
		ensureWorkerAtSite();

		if (!hasWorker())
		{
			if (state != TerraformState.WAITING_WORKER)
			{
				setState(TerraformState.WAITING_WORKER);
				clearHand();
			}
			return;
		}

		// 新任务创建时工人已就位：IDLE 直接进入 WORKER_ASSIGNED（否则永远卡在 IDLE 无行动）
		if (state == TerraformState.IDLE)
		{
			setState(TerraformState.WORKER_ASSIGNED);
			return;
		}

		if (state == TerraformState.WAITING_WORKER)
		{
			setState(TerraformState.WORKER_ASSIGNED);
			return;
		}

		if (state == TerraformState.WORKER_ASSIGNED)
		{
			resolveWorkerNpc();
			if (worker != null && NpcGoals.MoveToSiteGoal.isAboveSite(worker, boxPos()))
			{
				workerMissingTicks = 0;
				setState(TerraformState.ARRIVED);
				onArrived();
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
			return;
		}

		// 工作阶段：工人需在模盒上方
		if (hasWorker())
		{
			resolveWorkerNpc();
			if (worker != null)
			{
				if (NpcGoals.MoveToSiteGoal.isAboveSite(worker, boxPos()))
				{
					worker.getNavigation().stop();
				}
				else
				{
					clearHand();
					return;
				}
			}
		}

		switch (state)
		{
			case CHECKING_CHESTS -> doCheckChests();
			case SCAN -> doScan();
			case TERRAFORMING -> doTerraform();
			case WAITING_RESOURCE -> doWaitResource();
			default -> {}
		}
	}

	// 到达站点后：先查箱子（掉落/材料都依赖箱子）
	private void onArrived()
	{
		setState(TerraformState.CHECKING_CHESTS);
		setHandTool();
	}

	private void doCheckChests()
	{
		nearbyChests = InventoryManager.findNearbyChests(level, boxPos());
		if (nearbyChests.isEmpty())
		{
			// 一次性提示（避免每 tick 刷屏）
			if (!chestNoticeSent)
			{
				chestNoticeSent = true;
				sendPacketToCityPlayers(new com.wenzai.neosim.network.ServerToClientPayloads.ResourceShortagePacket(
						"§e整地需要箱子：请在建筑模盒旁放置一个箱子"));
			}
			clearHand();
			return; // 等待玩家放箱子
		}
		chestNoticeSent = false;
		setState(TerraformState.SCAN);
	}

	private void doScan()
	{
		targets.clear();
		TerraformPlan plan = getPlan();
		if (plan == null)
		{
			finishComplete("§e整地任务已失效（方案不存在）");
			return;
		}
		plan.scan(level, record.minX(), record.minZ(), record.maxX(), record.maxZ(),
				record.baselineY(), targets);
		totalTargets = targets.size();
		processedIndex = 0;
		updateRecord();

		if (targets.isEmpty())
		{
			finishComplete("§e该地块内没有可整地的目标");
			return;
		}
		setState(TerraformState.TERRAFORMING);
	}

	private void doTerraform()
	{
		if (processedIndex >= targets.size())
		{
			finishComplete("§e整地完成！");
			return;
		}
		if (animateHand(System.currentTimeMillis())) return;

		TerraformPlan plan = getPlan();
		BlockPos pos = targets.get(processedIndex);

		// 取料（若该方案需要）
		ItemStack material = ItemStack.EMPTY;
		if (plan.needsMaterial(processedIndex))
		{
			Item item = plan.materialItem();
			if (item != null)
			{
				if (InventoryManager.extractItem(nearbyChests, item, 1) < 1)
				{
					enterWaitingResource(item);
					return;
				}
				material = new ItemStack(item);
			}
			else
			{
				// NATURE：从箱子取任意树苗
				Item sapling = extractAnySapling(nearbyChests);
				if (sapling == null)
				{
					enterWaitingResource(Items.OAK_SAPLING);
					return;
				}
				material = new ItemStack(sapling);
			}
		}

		// 复核判定：物理联动后可能已失效，失效则跳过且不扣款
		if (!plan.matches(level, pos))
		{
			processedIndex++;
			updateRecord();
			return;
		}

		boolean changed = plan.apply(level, pos, processedIndex, material, nearbyChests);
		processedIndex++;
		if (changed)
		{
			deductCredits(Config.TERRAFORM_CREDIT_PER_BLOCK.get());
			gainXp();
		}
		updateRecord();
	}

	private void enterWaitingResource(Item item)
	{
		if (item != null && item != lastMissingItem)
		{
			lastMissingItem = item;
			sendPacketToCityPlayers(new com.wenzai.neosim.network.ServerToClientPayloads.ResourceShortagePacket(
					"§c整地缺料：" + item.getDescription().getString() + "，请放入模盒旁的箱子"));
		}
		setState(TerraformState.WAITING_RESOURCE);
		resourceWaitTicks = 0;
		clearHand();
	}

	private void doWaitResource()
	{
		resourceWaitTicks++;
		if (resourceWaitTicks < RESOURCE_RECHECK_TICKS) return;
		resourceWaitTicks = 0;

		nearbyChests = InventoryManager.findNearbyChests(level, boxPos());
		if (nearbyChests.isEmpty()) return;

		TerraformPlan plan = getPlan();
		if (plan.needsMaterial(processedIndex))
		{
			Item item = plan.materialItem();
			if (item != null)
			{
				if (InventoryManager.countItems(nearbyChests, item) < 1) return;
			}
			else if (countAnySapling(nearbyChests) < 1)
			{
				return;
			}
		}
		lastMissingItem = null;
		setState(TerraformState.TERRAFORMING);
		setHandTool();
	}

	// 完工：公告 + 清理（记录与任务由 TerraformEngine 移除）
	private void finishComplete(String message)
	{
		setState(TerraformState.COMPLETE);
		clearHand();
		releaseChunks();
		sendPacketToCityPlayers(new com.wenzai.neosim.network.ServerToClientPayloads.TerraformCompletePacket(message));
		LOGGER.info("NeoSim-TerraformTask: terraform complete at {}", boxPos());
	}

	// 只发给任务所属城市的在线玩家
	private void sendPacketToCityPlayers(CustomPacketPayload payload)
	{
		if (level.getServer() == null) return;
		boolean dedicated = level.getServer().isDedicatedServer();
		String saveName = dedicated ? null : level.getServer().getWorldData().getLevelName();
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers())
		{
			String pname = player.getName().getString();
			boolean inCity = dedicated
					? FileCreater.isPlayerInCity(cityName, pname)
					: FileCreater.isPlayerInCity(cityName, saveName, pname);
			if (inCity)
			{
				PacketDistributor.sendToPlayer(player, payload);
			}
		}
	}

	// ---- 工人/动画/夜班/经济：对齐 PlotTask 模式 ----

	protected void setState(TerraformState s)
	{
		if (this.state == s) return;
		this.state = s;
		record = record.withState(s.name());
	}

	// 游标持久化节流计数（D2：进度仅内存累积，每 100 次调用合并写一次盘）
	private int persistCounter;

	protected void updateRecord()
	{
		record = record.withCursor(processedIndex, totalTargets);
		if (++persistCounter % 100 == 0)
		{
			TerraformPersistence.updateRecord(level, cityName, record);
		}
	}

	// 抬手动画驱动。返回 true 表示未到出手时刻，本 tick 不做工
	protected boolean animateHand(long now)
	{
		resolveWorkerNpc();
		if (worker != null && !NpcGoals.MoveToSiteGoal.isAboveSite(worker, boxPos()))
		{
			clearHand();
			return true;
		}
		// 创造模式：不等待动作延迟，每 tick 执行一个动作
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

	protected boolean isWorkerOnShift()
	{
		return state == TerraformState.ARRIVED
				|| state == TerraformState.CHECKING_CHESTS
				|| state == TerraformState.SCAN
				|| state == TerraformState.TERRAFORMING
				|| state == TerraformState.WAITING_RESOURCE;
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
		clearHand();
	}

	// 夜晚入职的工人：当晚不前往
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

	private void ensureWorkerAtSite()
	{
		resolveWorkerNpc();
		if (worker != null)
		{
			// 产假：孕期 NPC 白天不返工
			if (worker.getPregnancyStage() > 0.0F) return;
			worker.setMoveTarget(boxPos());
		}
	}

	// 按模盒坐标找雇佣的 NPC 实体，找到后读取建筑师等级（C1：名字索引 O(1)）
	private void resolveWorkerNpc()
	{
		if (worker != null && worker.isAlive()) return;
		worker = null;
		String name = NeoSim.WORKER_MAP.get(boxPos());
		if (name == null || name.isEmpty()) return;

		worker = Entity.findByNpcName(level, name);
		if (worker != null)
		{
			float lvl = Math.max(1.0F, (float) worker.getJobArchitect());
			if (lvl != jobLevel)
			{
				updateSpeed(lvl);
			}
		}
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
			LOGGER.info("NeoSim-TerraformTask: worker '{}' restored at {}", name, boxPos());
		}
		else
		{
			// 已死亡：解雇，回到等待
			NeoSim.WORKER_MAP.remove(boxPos());
			record = record.withWorker(null);
			setState(TerraformState.WAITING_WORKER);
			updateRecord();
			LOGGER.warn("NeoSim-TerraformTask: worker '{}' gone, task back to waiting", name);
		}
	}

	// 区块加载：有工人即加载，完成/拆除后释放
	private void updateChunkLoading()
	{
		boolean shouldLoad = hasWorker() && state != TerraformState.COMPLETE;
		if (shouldLoad && !chunksLoaded)
		{
			TerraformChunkLoader.registerForPlot(level, record);
			chunksLoaded = true;
		}
		else if (!shouldLoad && chunksLoaded)
		{
			TerraformChunkLoader.releaseForPlot(level, record);
			chunksLoaded = false;
		}
	}

	protected void releaseChunks()
	{
		if (chunksLoaded)
		{
			TerraformChunkLoader.releaseForPlot(level, record);
			chunksLoaded = false;
		}
	}

	// 手持铁锹
	private void setHandTool()
	{
		resolveWorkerNpc();
		if (worker != null)
		{
			worker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SHOVEL));
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

	// 作业速度随建筑师等级
	protected void updateSpeed(float lvl)
	{
		this.jobLevel = lvl;
		this.runDelay = Math.max(1, (int) (BASE_DELAY / lvl));
	}

	// 从城市资金中扣除整地费用（创造模式不扣）
	protected void deductCredits(double amount)
	{
		if (amount <= 0 || level.getServer() == null) return;
		if (currentMode() == 2) return;
		com.wenzai.neosim.storage.SimData.CityData data = com.wenzai.neosim.storage.SimData.CityData.read(level, cityName);
		double now = data.credit() - amount;
		if (now < 0) now = 0;
		com.wenzai.neosim.storage.SimData.CityData.write(level, cityName, data.withCredit(now));
		com.wenzai.neosim.storage.ModSavedData.get(level).syncCityToClients(level, cityName);
	}

	// 建筑师技能成长（写入 job.architect）
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
				worker.setJobArchitect((byte) Math.min(aft, (int) MAX_LEVEL));
				worker.syncToJson();
			}
		}
		updateSpeed(jobLevel);
	}

	protected byte currentMode()
	{
		return com.wenzai.neosim.storage.ModSavedData.get(level).getMode();
	}

	// 从箱子取任意树苗，返回取到的树苗物品（未取到返回 null）
	private Item extractAnySapling(List<ChestBlockEntity> chests)
	{
		for (ChestBlockEntity chest : chests)
		{
			for (int i = 0; i < chest.getContainerSize(); i++)
			{
				ItemStack stack = chest.getItem(i);
				if (!stack.isEmpty() && stack.is(net.minecraft.tags.ItemTags.SAPLINGS))
				{
					stack.shrink(1);
					chest.setChanged();
					return stack.getItem();
				}
			}
		}
		return null;
	}

	// 统计箱子中树苗总数量
	private int countAnySapling(List<ChestBlockEntity> chests)
	{
		int total = 0;
		for (ChestBlockEntity chest : chests)
		{
			for (int i = 0; i < chest.getContainerSize(); i++)
			{
				ItemStack stack = chest.getItem(i);
				if (!stack.isEmpty() && stack.is(net.minecraft.tags.ItemTags.SAPLINGS))
				{
					total += stack.getCount();
				}
			}
		}
		return total;
	}
}

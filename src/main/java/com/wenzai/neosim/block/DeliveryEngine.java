package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.building.ConstructionEngine;
import com.wenzai.neosim.building.ConstructionTask;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.storage.FileCreater;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@EventBusSubscriber(modid = NeoSim.MOD_ID)
public class DeliveryEngine
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final List<DeliveryTask> tasks = new ArrayList<>();
	private static boolean restoredFromDisk;
	private static int saveTimer;

	// 派单认领互斥：工地控制箱坐标 → 快递盒坐标（瞬态，不落盘）
	private static final Map<BlockPos, BlockPos> claimedSites = new HashMap<>();

	// 放置快递盒：建记录 + 建任务
	public static void createDeliveryBox(ServerLevel level, BlockPos pos, String placer)
	{
		if (level.getServer() == null) return;
		String city = cityOf(level, placer);
		if (city.isEmpty())
		{
			LOGGER.warn("NeoSim-DeliveryEngine: skip delivery box {} — placer has no city", pos);
			return;
		}
		DeliveryBoxPersistence.DeliveryBoxRecord record =
				DeliveryBoxPersistence.DeliveryBoxRecord.of(pos, placer);
		DeliveryBoxPersistence.updateRecord(level, city, record);

		DeliveryTask task = new DeliveryTask(level, city, record);
		synchronized (tasks)
		{
			tasks.add(task);
		}
		saveAll(level);
		LOGGER.info("NeoSim-DeliveryEngine: delivery box created at {}", pos);
	}

	// 被破坏时清理任务/区块/工人/记录
	public static void removeBoxAt(ServerLevel level, BlockPos pos)
	{
		DeliveryTask removed = null;
		synchronized (tasks)
		{
			for (DeliveryTask t : tasks)
			{
				if (t.boxPos().equals(pos))
				{
					removed = t;
					break;
				}
			}
			if (removed != null) tasks.remove(removed);
		}
		if (removed != null)
		{
			removed.onBoxDestroyed();
			LOGGER.info("NeoSim-DeliveryEngine: delivery box task removed at {}", pos);
		}
		DeliveryBoxPersistence.removeAt(level, pos);
	}

	// 按盒子坐标查找任务（GUI 渲染线程读取，需同步）
	public static DeliveryTask findTask(BlockPos pos)
	{
		synchronized (tasks)
		{
			for (DeliveryTask t : tasks)
			{
				if (t.boxPos().equals(pos)) return t;
			}
		}
		return null;
	}

	// 认领互斥接口
	public static boolean isClaimed(BlockPos site)
	{
		synchronized (claimedSites)
		{
			return claimedSites.containsKey(site);
		}
	}

	public static void claim(BlockPos site, BlockPos box)
	{
		synchronized (claimedSites)
		{
			claimedSites.put(site, box);
		}
	}

	public static void releaseClaim(BlockPos site, BlockPos box)
	{
		synchronized (claimedSites)
		{
			if (box.equals(claimedSites.get(site)))
			{
				claimedSites.remove(site);
			}
		}
	}

	// 每 tick 轮询调度（M14：快照列表后在锁外 tick）
	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event)
	{
		ServerLevel level = event.getServer().overworld();
		maybeRestoreTasks(level);
		cleanupClaims();
		com.wenzai.neosim.building.ConstructionEngine.invalidateWaitingCache();

		List<DeliveryTask> snapshot;
		synchronized (tasks)
		{
			snapshot = new ArrayList<>(tasks);
		}
		for (DeliveryTask task : snapshot)
		{
			try
			{
				task.tick();
			}
			catch (Exception e)
			{
				LOGGER.error("NeoSim-DeliveryEngine: task tick error at {}, skipped", task.boxPos(), e);
			}
		}

		// 定期落盘状态
		saveTimer++;
		if (saveTimer >= 100)
		{
			saveTimer = 0;
			saveAll(level);
		}
	}

	// 服务器停止时保存并清空静态表
	@SubscribeEvent
	public static void onServerStopping(ServerStoppingEvent event)
	{
		saveAll(event.getServer().overworld());
		synchronized (tasks)
		{
			tasks.clear();
		}
		synchronized (claimedSites)
		{
			claimedSites.clear();
		}
		restoredFromDisk = false;
		DeliveryChunkLoader.clear();
		LOGGER.info("NeoSim-DeliveryEngine: tasks saved & cleared on server stopping");
	}

	// 按城市批量写盘所有任务的当前记录（D3：每城一次整文件写，替代逐任务读+写）
	public static void saveAll(ServerLevel level)
	{
		List<DeliveryTask> snapshot;
		synchronized (tasks)
		{
			snapshot = new ArrayList<>(tasks);
		}
		Map<String, List<DeliveryBoxPersistence.DeliveryBoxRecord>> byCity = new HashMap<>();
		for (DeliveryTask task : snapshot)
		{
			byCity.computeIfAbsent(task.cityName(), k -> new ArrayList<>()).add(task.record());
		}
		for (Map.Entry<String, List<DeliveryBoxPersistence.DeliveryBoxRecord>> e : byCity.entrySet())
		{
			DeliveryBoxPersistence.save(level, e.getKey(), e.getValue());
		}
	}

	// 启动恢复：重建各城市的快递盒任务
	private static void maybeRestoreTasks(ServerLevel level)
	{
		if (restoredFromDisk) return;
		restoredFromDisk = true;

		Path dataDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		if (!level.getServer().isDedicatedServer())
		{
			dataDir = dataDir.resolve(level.getServer().getWorldData().getLevelName());
		}
		if (!Files.isDirectory(dataDir)) return;

		try (Stream<Path> dirs = Files.list(dataDir))
		{
			for (Path dir : dirs.filter(Files::isDirectory).toList())
			{
				String city = dir.getFileName().toString();
				for (DeliveryBoxPersistence.DeliveryBoxRecord rec : DeliveryBoxPersistence.load(level, city))
				{
					// 盒子方块没了：释放工人并跳过
					Block block = level.getBlockState(rec.boxPos()).getBlock();
					if (!(block instanceof DeliveryBox))
					{
						releaseWorker(level, rec);
						LOGGER.warn("NeoSim-DeliveryEngine: skip restore delivery box at {} — block gone", rec.boxPos());
						continue;
					}

					// 重建雇佣关系
					if (rec.worker() != null && !rec.worker().isEmpty())
					{
						NeoSim.WORKER_MAP.put(rec.boxPos(), rec.worker());
					}
					DeliveryTask task = new DeliveryTask(level, city, rec);
					synchronized (tasks)
					{
						tasks.add(task);
					}
					LOGGER.info("NeoSim-DeliveryEngine: restored delivery box task at {} (state {})",
							rec.boxPos(), rec.state());
				}
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-DeliveryEngine: restore failed", e);
		}
	}

	// 失效认领清理：工地任务不存在或不再缺料时释放
	private static void cleanupClaims()
	{
		List<BlockPos> stale = new ArrayList<>();
		synchronized (claimedSites)
		{
			for (BlockPos site : claimedSites.keySet())
			{
				boolean alive = false;
				for (ConstructionTask t : ConstructionEngine.getWaitingTasks())
				{
					if (site.equals(t.getBuilding().getControlBoxPos()))
					{
						alive = true;
						break;
					}
				}
				if (!alive) stale.add(site);
			}
			for (BlockPos s : stale)
			{
				claimedSites.remove(s);
			}
		}
	}

	// 盒子没了时解雇对应NPC
	private static void releaseWorker(ServerLevel level, DeliveryBoxPersistence.DeliveryBoxRecord rec)
	{
		if (rec.worker() == null || rec.worker().isEmpty()) return;
		NeoSim.WORKER_MAP.remove(rec.boxPos());
		// 全图按名查找：限半径会漏掉离家/远走的快递员，导致其AI永不恢复
		Entity npc = Entity.findByNpcName(level, rec.worker());
		if (npc != null)
		{
			npc.releaseFromSite();
			npc.setBuildAnim(0.0F);
		}
	}

	// 盒子放置者所属城市（放置者未入城返回空）
	private static String cityOf(ServerLevel level, String placer)
	{
		if (level.getServer() == null || placer == null || placer.isEmpty()) return "";
		return level.getServer().isDedicatedServer()
				? FileCreater.findPlayerCity(placer)
				: FileCreater.findPlayerCity(level.getServer().getWorldData().getLevelName(), placer);
	}
}

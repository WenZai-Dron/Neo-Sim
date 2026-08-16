package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
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
import java.util.List;
import java.util.stream.Stream;

@EventBusSubscriber(modid = NeoSim.MOD_ID)
public class WorkPlotEngine
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final List<PlotTask> tasks = new ArrayList<>();
	private static boolean restoredFromDisk;
	private static int saveTimer;

	// 放置农业盒
	public static void createFarmPlot(ServerLevel level, BlockPos pos, String placer)
	{
		createPlot(level, pos, placer, "farming");
	}

	// 放置矿业盒
	public static void createMinePlot(ServerLevel level, BlockPos pos, String placer)
	{
		createPlot(level, pos, placer, "mining");
	}

	// 绑定最近的可用矩形；无矩形/矩形被占用则只留未绑定记录
	private static void createPlot(ServerLevel level, BlockPos pos, String placer, String type)
	{
		if (level.getServer() == null) return;
		String city = cityOf(level, placer);
		if (city.isEmpty())
		{
			LOGGER.warn("NeoSim-WorkPlotEngine: skip work box {} — placer has no city", pos);
			return;
		}

		// 盒子紧邻标记棒
		MarkerManager.MarkerRect rect =
				MarkerManager.findRectAdjacentToMarker(level, pos);
		WorkBoxPersistence.WorkBoxRecord record;
		if (rect == null || WorkBoxPersistence.rectInUse(level, city, rect))
		{
			record = WorkBoxPersistence.WorkBoxRecord.ofUnbound(type, pos, placer);
		}
		else
		{
			record = WorkBoxPersistence.WorkBoxRecord.of(type, pos, rect, placer);
		}
		WorkBoxPersistence.addOrUpdate(level, city, record);

		if (record.bound())
		{
			PlotTask task = newTask(level, city, record);
			synchronized (tasks)
			{
				tasks.add(task);
			}
			saveAll(level);
			LOGGER.info("NeoSim-WorkPlotEngine: work box bound rect {}x{} at {} (type={})",
					record.rx2() - record.rx1() + 1, record.rz2() - record.rz1() + 1, pos, type);
		}
		else
		{
			LOGGER.info("NeoSim-WorkPlotEngine: work box {} unbound (no free rect)", pos);
		}
	}

	// 被破坏时清理任务/区块/工人/记录
	public static void removePlotAt(ServerLevel level, BlockPos pos)
	{
		PlotTask removed = null;
		synchronized (tasks)
		{
			for (PlotTask t : tasks)
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
			LOGGER.info("NeoSim-WorkPlotEngine: work box task removed at {}", pos);
		}
		WorkBoxPersistence.removeAt(level, pos);
	}

	// 按盒子坐标查找任务（GUI渲染线程读取，需同步）
	public static PlotTask findTask(BlockPos pos)
	{
		synchronized (tasks)
		{
			for (PlotTask t : tasks)
			{
				if (t.boxPos().equals(pos)) return t;
			}
		}
		return null;
	}

	// 每tick轮询调度
	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event)
	{
		ServerLevel level = event.getServer().overworld();
		maybeRestoreTasks(level);

		synchronized (tasks)
		{
			for (PlotTask task : tasks)
			{
				try
				{
					task.tick();
				}
				catch (Exception e)
				{
					// 单任务异常
					LOGGER.error("NeoSim-WorkPlotEngine: task tick error at {}, skipped", task.boxPos(), e);
				}
			}
		}

		// 定期落盘游标/状态
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
		restoredFromDisk = false;
		PlotChunkLoader.clear();
		LOGGER.info("NeoSim-WorkPlotEngine: tasks saved & cleared on server stopping");
	}

	// 按城市写盘所有任务的当前记录
	public static void saveAll(ServerLevel level)
	{
		List<PlotTask> snapshot;
		synchronized (tasks)
		{
			snapshot = new ArrayList<>(tasks);
		}
		for (PlotTask task : snapshot)
		{
			WorkBoxPersistence.updateRecord(level, task.cityName(), task.record());
		}
	}

	// 启动恢复：重建各城市已绑定的工作盒任务
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
				for (WorkBoxPersistence.WorkBoxRecord rec : WorkBoxPersistence.load(level, city))
				{
					if (!rec.bound()) continue;

					// 盒子方块没了：释放工人并跳过
					Block block = level.getBlockState(rec.boxPos()).getBlock();
					boolean typeOk = ("farming".equals(rec.type()) && block instanceof FarmingBox)
							|| ("mining".equals(rec.type()) && block instanceof MiningBox);
					if (!typeOk)
					{
						releaseWorker(level, rec);
						LOGGER.warn("NeoSim-WorkPlotEngine: skip restore work box at {} — block gone", rec.boxPos());
						continue;
					}

					// 重建雇佣关系
					if (rec.worker() != null && !rec.worker().isEmpty())
					{
						NeoSim.WORKER_MAP.put(rec.boxPos(), rec.worker());
					}
					PlotTask task = newTask(level, city, rec);
					synchronized (tasks)
					{
						tasks.add(task);
					}
					LOGGER.info("NeoSim-WorkPlotEngine: restored work box task at {} (state {})",
							rec.boxPos(), rec.state());
				}
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-WorkPlotEngine: restore failed", e);
		}
	}

	private static PlotTask newTask(ServerLevel level, String city, WorkBoxPersistence.WorkBoxRecord record)
	{
		return "mining".equals(record.type())
				? new MineTask(level, city, record)
				: new FarmTask(level, city, record);
	}

	// 补上矩形快照
	public static void tryBindAfterMarkerPlacement(ServerLevel level)
	{
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
				for (WorkBoxPersistence.WorkBoxRecord rec : WorkBoxPersistence.load(level, city))
				{
					if (rec.bound()) continue;

					// 盒子方块没了则跳过
					Block block = level.getBlockState(rec.boxPos()).getBlock();
					boolean typeOk = ("farming".equals(rec.type()) && block instanceof FarmingBox)
							|| ("mining".equals(rec.type()) && block instanceof MiningBox);
					if (!typeOk) continue;

					MarkerManager.MarkerRect rect =
							MarkerManager.findRectAdjacentToMarker(level, rec.boxPos());
					if (rect == null || WorkBoxPersistence.rectInUse(level, city, rect)) continue;

					WorkBoxPersistence.WorkBoxRecord bound = rec.withRect(rect);
					WorkBoxPersistence.updateRecord(level, city, bound);
					PlotTask task = newTask(level, city, bound);
					synchronized (tasks)
					{
						tasks.add(task);
					}
					LOGGER.info("NeoSim-WorkPlotEngine: re-bound work box at {} to rect {}x{} (type={})",
							rec.boxPos(), rect.maxX() - rect.minX() + 1, rect.maxZ() - rect.minZ() + 1,
							rec.type());
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-WorkPlotEngine: tryBindAfterMarkerPlacement failed", e);
		}
	}

	// 盒子没了时解雇对应NPC
	private static void releaseWorker(ServerLevel level, WorkBoxPersistence.WorkBoxRecord rec)
	{
		if (rec.worker() == null || rec.worker().isEmpty()) return;
		NeoSim.WORKER_MAP.remove(rec.boxPos());
		// 全图按名查找：限半径会漏掉离家/远走的工人，导致其AI永不恢复
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

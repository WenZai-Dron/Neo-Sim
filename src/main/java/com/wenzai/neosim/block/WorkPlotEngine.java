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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@EventBusSubscriber(modid = NeoSim.MOD_ID)
public class WorkPlotEngine
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final List<PlotTask> tasks = new ArrayList<>();
	private static boolean restoredFromDisk;
	private static int saveTimer;

	// 绑定矩形校验计时（每 40 tick 对账一次，与 MarkerManager.tick 同步）
	private static int rectCheckTimer;

	// C6c：未绑定工作盒内存表（boxPos → 城市）。放标记棒时只查内存，不再全盘重扫各城市记录文件
	private static final java.util.Map<BlockPos, String> UNBOUND_BOXES = new java.util.HashMap<>();

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
			UNBOUND_BOXES.remove(pos);
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
			UNBOUND_BOXES.put(pos, city);
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
			removed.releaseResources();
			LOGGER.info("NeoSim-WorkPlotEngine: work box task removed at {}", pos);
		}
		UNBOUND_BOXES.remove(pos);
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

	// 绑定矩形校验：矩形对应的标记棒全部消失（光幕消失）时，任务失效——
	// 释放工人与区块，记录退回未绑定（盒子保留，标记恢复后 tryBindAfterMarkerPlacement 自动重绑）
	private static void validateRects(ServerLevel level)
	{
		List<PlotTask> invalid = new ArrayList<>();
		synchronized (tasks)
		{
			for (PlotTask t : tasks)
			{
				if (!t.record().bound()) continue;
				if (!rectStillExists(level, t.record()))
				{
					invalid.add(t);
				}
			}
			for (PlotTask t : invalid)
			{
				tasks.remove(t);
			}
		}
		for (PlotTask t : invalid)
		{
			t.releaseResources();
			WorkBoxPersistence.WorkBoxRecord unbound = WorkBoxPersistence.WorkBoxRecord.ofUnbound(
					t.record().type(), t.boxPos(), t.record().placer());
			WorkBoxPersistence.updateRecord(level, t.cityName(), unbound);
			UNBOUND_BOXES.put(t.boxPos(), t.cityName());
			LOGGER.warn("NeoSim-WorkPlotEngine: work box at {} — marker rect gone, plot deactivated (unbound)",
					t.boxPos());
		}
	}

	// 绑定矩形是否仍存在于标记管理器（四角标记棒仍在、面积一致）
	private static boolean rectStillExists(ServerLevel level, WorkBoxPersistence.WorkBoxRecord rec)
	{
		for (MarkerManager.MarkerRect r : MarkerManager.getActiveRects(level))
		{
			if (r.minX() == rec.rx1() && r.minZ() == rec.rz1()
					&& r.maxX() == rec.rx2() && r.maxZ() == rec.rz2()
					&& r.minY() == rec.ry())
			{
				return true;
			}
		}
		return false;
	}

	// 每tick轮询调度（M14：快照列表后在锁外 tick，避免锁内含 setBlock/音效拉长临界区）
	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event)
	{
		ServerLevel level = event.getServer().overworld();
		maybeRestoreTasks(level);

		List<PlotTask> snapshot;
		synchronized (tasks)
		{
			snapshot = new ArrayList<>(tasks);
		}
		for (PlotTask task : snapshot)
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

		// 定时校验：绑定矩形对应的标记棒全部消失时停任务
		rectCheckTimer++;
		if (rectCheckTimer >= 40)
		{
			rectCheckTimer = 0;
			validateRects(level);
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
		UNBOUND_BOXES.clear();
		restoredFromDisk = false;
		PlotChunkLoader.clear();
		LOGGER.info("NeoSim-WorkPlotEngine: tasks saved & cleared on server stopping");
	}

	// 按城市批量写盘所有任务的当前记录（D3：每城一次整文件写，替代逐任务读+写）
	public static void saveAll(ServerLevel level)
	{
		List<PlotTask> snapshot;
		synchronized (tasks)
		{
			snapshot = new ArrayList<>(tasks);
		}
		Map<String, List<WorkBoxPersistence.WorkBoxRecord>> byCity = new HashMap<>();
		for (PlotTask task : snapshot)
		{
			byCity.computeIfAbsent(task.cityName(), k -> new ArrayList<>()).add(task.record());
		}
		for (Map.Entry<String, List<WorkBoxPersistence.WorkBoxRecord>> e : byCity.entrySet())
		{
			WorkBoxPersistence.save(level, e.getKey(), e.getValue());
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
					if (!rec.bound())
					{
						// C6c：未绑定记录入内存表，放标记棒时只查内存
						UNBOUND_BOXES.put(rec.boxPos(), city);
						continue;
					}

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

					// 绑定矩形不存在（标记棒已移除）：释放工人，记录退回未绑定，跳过
					if (!rectStillExists(level, rec))
					{
						releaseWorker(level, rec);
						WorkBoxPersistence.updateRecord(level, city,
								WorkBoxPersistence.WorkBoxRecord.ofUnbound(rec.type(), rec.boxPos(), rec.placer()));
						LOGGER.warn("NeoSim-WorkPlotEngine: skip restore work box at {} — marker rect gone",
								rec.boxPos());
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

	// 补上矩形快照（C6c：只查内存未绑定表，不再全盘扫描各城市记录文件）
	public static void tryBindAfterMarkerPlacement(ServerLevel level)
	{
		if (UNBOUND_BOXES.isEmpty()) return;
		for (java.util.Map.Entry<BlockPos, String> entry :
				new java.util.ArrayList<>(UNBOUND_BOXES.entrySet()))
		{
			BlockPos boxPos = entry.getKey();
			String city = entry.getValue();
			WorkBoxPersistence.WorkBoxRecord rec = WorkBoxPersistence.findRecord(level, city, boxPos);
			if (rec == null || rec.bound())
			{
				UNBOUND_BOXES.remove(boxPos);
				continue;
			}

			// 盒子方块没了则移除
			Block block = level.getBlockState(boxPos).getBlock();
			boolean typeOk = ("farming".equals(rec.type()) && block instanceof FarmingBox)
					|| ("mining".equals(rec.type()) && block instanceof MiningBox);
			if (!typeOk)
			{
				UNBOUND_BOXES.remove(boxPos);
				continue;
			}

			MarkerManager.MarkerRect rect =
					MarkerManager.findRectAdjacentToMarker(level, boxPos);
			if (rect == null || WorkBoxPersistence.rectInUse(level, city, rect)) continue;

			WorkBoxPersistence.WorkBoxRecord bound = rec.withRect(rect);
			WorkBoxPersistence.updateRecord(level, city, bound);
			UNBOUND_BOXES.remove(boxPos);
			PlotTask task = newTask(level, city, bound);
			synchronized (tasks)
			{
				tasks.add(task);
			}
			LOGGER.info("NeoSim-WorkPlotEngine: re-bound work box at {} to rect {}x{} (type={})",
					boxPos, rect.maxX() - rect.minX() + 1, rect.maxZ() - rect.minZ() + 1,
					rec.type());
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

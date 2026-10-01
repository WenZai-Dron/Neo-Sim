package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.building.BuildingInstance;
import com.wenzai.neosim.building.ConstructionEngine;
import com.wenzai.neosim.building.ConstructionTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

import javax.annotation.Nullable;

// 整地任务调度：创建/取消/恢复/互斥校验
@EventBusSubscriber(modid = NeoSim.MOD_ID)
public class TerraformEngine
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final List<TerraformTask> tasks = new ArrayList<>();
	private static boolean restoredFromDisk;
	private static int saveTimer;

	// 创建整地任务：返回 null=成功，否则为按客户端语言显示的提示组件
	@Nullable
	public static Component start(ServerLevel level, String cityName, BlockPos boxPos,
			TerraformPlan plan, int minX, int minZ, int maxX, int maxZ, int baselineY)
	{
		if (cityName == null || cityName.isEmpty()) return Component.translatable("msg.neosim.terraform.noCity");
		if (!(level.getBlockState(boxPos).getBlock() instanceof BuildingConstructor))
		{
			return Component.translatable("msg.neosim.terraform.boxMissing");
		}
		if (findTask(boxPos) != null)
		{
			return Component.translatable("msg.neosim.terraform.taskExists");
		}
		ConstructionTask ct = ConstructionEngine.findTask(boxPos);
		if (ct != null && ct.getState() != BuildingInstance.BuildState.COMPLETE)
		{
			return Component.translatable("msg.neosim.terraform.buildingRunning");
		}
		if (minX > maxX || minZ > maxZ) return Component.translatable("msg.neosim.terraform.invalidRect");
		if (maxX - minX > MarkerManager.MAX_SPAN || maxZ - minZ > MarkerManager.MAX_SPAN)
		{
			return Component.translatable("msg.neosim.terraform.tooLarge", MarkerManager.MAX_SPAN);
		}
		if (baselineY < level.getMinBuildHeight() || baselineY >= level.getMaxBuildHeight())
		{
			return Component.translatable("msg.neosim.terraform.baselineOutOfRange");
		}

		// 地块必须由与模盒相邻的标记棒构成（同农业/矿业盒规则）
		MarkerManager.MarkerRect adj = MarkerManager.findRectAdjacentToMarker(level, boxPos);
		if (adj == null)
		{
			return Component.translatable("msg.neosim.terraform.markerNotAdjacent");
		}
		if (adj.minX() != minX || adj.minZ() != minZ || adj.maxX() != maxX
				|| adj.maxZ() != maxZ || adj.minY() != baselineY)
		{
			return Component.translatable("msg.neosim.terraform.markerMismatch");
		}

		// 预扫描：空则拒绝
		List<BlockPos> targets = new ArrayList<>();
		plan.scan(level, minX, minZ, maxX, maxZ, baselineY, targets);
		if (targets.isEmpty())
		{
			return Component.translatable("msg.neosim.terraform.noTargets");
		}

		TerraformPersistence.TerraformRecord rec =
				TerraformPersistence.TerraformRecord.of(boxPos, plan,
						minX, minZ, maxX, maxZ, baselineY, "");
		TerraformPersistence.addOrUpdate(level, cityName, rec);

		TerraformTask task = new TerraformTask(level, cityName, rec, targets);
		synchronized (tasks)
		{
			tasks.add(task);
		}
		TerraformChunkLoader.registerForPlot(level, rec);
		LOGGER.info("NeoSim-TerraformEngine: started {} at {} ({}x{}, targets={})",
				plan, boxPos, maxX - minX + 1, maxZ - minZ + 1, targets.size());
		return null;
	}

	// 按模盒坐标查找任务（GUI 渲染线程读取，需同步）
	@Nullable
	public static TerraformTask findTask(BlockPos pos)
	{
		synchronized (tasks)
		{
			for (TerraformTask t : tasks)
			{
				if (t.boxPos().equals(pos)) return t;
			}
		}
		return null;
	}

	// 取消：移除任务 + 释放区块 + 删记录（工人由调用方统一处理，如 BreakHandler）
	public static void cancelAt(ServerLevel level, BlockPos pos)
	{
		TerraformTask removed = null;
		synchronized (tasks)
		{
			for (TerraformTask t : tasks)
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
			LOGGER.info("NeoSim-TerraformEngine: terraform task cancelled at {}", pos);
		}
		TerraformPersistence.removeAt(level, pos);
	}

	// 每 tick 轮询调度（M14：快照列表后在锁外 tick；COMPLETE 任务在锁内移除）
	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event)
	{
		ServerLevel level = event.getServer().overworld();
		maybeRestoreTasks(level);

		List<TerraformTask> snapshot;
		synchronized (tasks)
		{
			snapshot = new ArrayList<>(tasks);
		}
		for (TerraformTask task : snapshot)
		{
			try
			{
				task.tick();
			}
			catch (Exception e)
			{
				LOGGER.error("NeoSim-TerraformEngine: task tick error at {}, skipped", task.boxPos(), e);
			}
			if (task.getState() == TerraformTask.TerraformState.COMPLETE)
			{
				synchronized (tasks)
				{
					tasks.remove(task);
				}
				TerraformPersistence.removeAt(level, task.boxPos());
				LOGGER.info("NeoSim-TerraformEngine: completed task cleaned at {}", task.boxPos());
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
		TerraformChunkLoader.clear();
		LOGGER.info("NeoSim-TerraformEngine: tasks saved & cleared on server stopping");
	}

	// 按城市批量写盘所有任务的当前记录（D3：每城一次整文件写，替代逐任务读+写）
	public static void saveAll(ServerLevel level)
	{
		List<TerraformTask> snapshot;
		synchronized (tasks)
		{
			snapshot = new ArrayList<>(tasks);
		}
		Map<String, List<TerraformPersistence.TerraformRecord>> byCity = new HashMap<>();
		for (TerraformTask task : snapshot)
		{
			if (task.getState() == TerraformTask.TerraformState.COMPLETE) continue;
			byCity.computeIfAbsent(task.cityName(), k -> new ArrayList<>()).add(task.record());
		}
		for (Map.Entry<String, List<TerraformPersistence.TerraformRecord>> e : byCity.entrySet())
		{
			TerraformPersistence.save(level, e.getKey(), e.getValue());
		}
	}

	// 启动恢复：重建各城市的整地任务
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
				for (TerraformPersistence.TerraformRecord rec : TerraformPersistence.load(level, city))
				{
					// 模盒方块没了：删记录并跳过
					if (!(level.getBlockState(rec.boxPos()).getBlock() instanceof BuildingConstructor))
					{
						TerraformPersistence.removeAt(level, rec.boxPos());
						LOGGER.warn("NeoSim-TerraformEngine: skip restore at {} — box gone", rec.boxPos());
						continue;
					}

					TerraformPlan plan = TerraformPlan.valueOfSafe(rec.plan());
					if (plan == null)
					{
						TerraformPersistence.removeAt(level, rec.boxPos());
						continue;
					}

					// 重建雇佣关系
					if (rec.worker() != null && !rec.worker().isEmpty())
					{
						NeoSim.WORKER_MAP.put(rec.boxPos(), rec.worker());
					}

					// 重新扫描剩余目标（已处理的格子不再命中）
					List<BlockPos> targets = new ArrayList<>();
					plan.scan(level, rec.minX(), rec.minZ(), rec.maxX(), rec.maxZ(),
							rec.baselineY(), targets);
					if (targets.isEmpty())
					{
						TerraformPersistence.removeAt(level, rec.boxPos());
						continue;
					}

					TerraformTask task = new TerraformTask(level, city, rec, targets);
					task.resetForRestore();
					synchronized (tasks)
					{
						tasks.add(task);
					}
					LOGGER.info("NeoSim-TerraformEngine: restored task at {} (state {}, targets={})",
							rec.boxPos(), rec.state(), targets.size());
				}
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-TerraformEngine: restore failed", e);
		}
	}
}

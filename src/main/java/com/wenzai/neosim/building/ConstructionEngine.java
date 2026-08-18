package com.wenzai.neosim.building;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.client.ClientBlockInteractions;
import com.wenzai.neosim.schematic.PreviewState;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SchematicRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

// 管理所有活跃建造任务，每tick轮询调度
@EventBusSubscriber(modid = NeoSim.MOD_ID)
public class ConstructionEngine
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final List<ConstructionTask> tasks = new ArrayList<>();

	// 最近创建的任务，供GUI读取（L4：volatile 跨线程可见 + 完工后置空防 GUI 残留引用）
	public static volatile ConstructionTask lastTask;

	// 从预览状态创建建造任务
	public static BuildingInstance createBuilding(SchematicData schematic,
												   PreviewState preview,
												   ServerLevel level,
												   String placerName,
												   BlockPos constructorPos)
	{
		BuildingInstance building = new BuildingInstance();
		building.setSchematicName(schematic.getName());
		building.setSchematic(schematic);
		building.setControlBoxPos(preview.getOrigin());
		building.setRotation(preview.getRotation());
		building.setMirror(preview.getMirror());
		building.setFacing(preview.getFacing());
		building.setAuthor(schematic.getAuthor());
		building.setPlacerName(placerName);
		building.setConstructorPos(constructorPos);

		// 快照当前雇佣关系
		building.setWorkerName(NeoSim.WORKER_MAP.get(constructorPos));

		// 整地互斥：模盒有进行中整地任务时拒绝建造
		if (com.wenzai.neosim.block.TerraformEngine.findTask(constructorPos) != null)
		{
			LOGGER.warn("NeoSim-ConstructionEngine: reject '{}' — terraform task active at {}",
					schematic.getName(), constructorPos);
			return null;
		}

		// 相交冲突检测：与已有任务区域重叠则拒绝
		AABB newBox = computeBuildingBox(preview, schematic);
		if (newBox != null)
		{
			synchronized (tasks)
			{
				for (ConstructionTask existing : tasks)
				{
					BuildingInstance other = existing.getBuilding();
					AABB otherBox = computeBuildingBox(other);
					if (otherBox != null && newBox.intersects(otherBox))
					{
						LOGGER.warn("NeoSim-ConstructionEngine: AABB conflict, reject '{}' at {}"
								+ " — overlaps '{}' at {}",
								schematic.getName(), preview.getOrigin(),
								other.getSchematicName(), other.getControlBoxPos());

						// 调用方检查null并通知玩家
						return null;
					}
				}
			}
		}

		ConstructionTask task = new ConstructionTask(building, level);

		task.setState(BuildingInstance.BuildState.WAITING_FOR_WORKER);
		synchronized (tasks)
		{
			tasks.add(task);
		}
		lastTask = task;

		// 强制加载建筑区域区块
		BuildingChunkLoader.registerForBuilding(building, level);
		LOGGER.info("NeoSim-ConstructionEngine: {} tasks, started '{}' at {}",
				tasks.size(), schematic.getName(), preview.getOrigin());

		// 建造开始：一次性从建筑所属城市的资金中扣除费用
		if (com.wenzai.neosim.storage.ModSavedData.get(level).getMode() != 2)
		{
			double fee = schematic.getTotalSolidBlocks() * com.wenzai.neosim.Config.CREDITS_PER_BLOCK.get();
			if (fee > 0)
			{
				ConstructionTask.deductCredits(level, building, fee);
			}
		}
		saveAllTasks(level);
		return building;
	}

	// 计算建筑占用的世界包围盒
	private static AABB computeBuildingBox(PreviewState preview, SchematicData schematic)
	{
		if (preview.getOrigin() == null || schematic == null) return null;
		int sx = schematic.getSizeX(), sy = schematic.getSizeY(), sz = schematic.getSizeZ();
		if (sx <= 0 || sy <= 0 || sz <= 0) return null;
		return boxFromCorners(preview.blueprintToWorld(0, 0, 0),
				preview.blueprintToWorld(sx - 1, sy - 1, sz - 1));
	}

	private static AABB computeBuildingBox(BuildingInstance b)
	{
		if (b.getControlBoxPos() == null || b.getSchematic() == null) return null;
		int sx = b.getSchematic().getSizeX(), sy = b.getSchematic().getSizeY(), sz = b.getSchematic().getSizeZ();
		if (sx <= 0 || sy <= 0 || sz <= 0) return null;
		return boxFromCorners(b.blueprintToWorld(0, 0, 0),
				b.blueprintToWorld(sx - 1, sy - 1, sz - 1));
	}

	private static AABB boxFromCorners(BlockPos c0, BlockPos c1)
	{
		return new AABB(
				Math.min(c0.getX(), c1.getX()), Math.min(c0.getY(), c1.getY()), Math.min(c0.getZ(), c1.getZ()),
				Math.max(c0.getX(), c1.getX()) + 1.0D,
				Math.max(c0.getY(), c1.getY()) + 1.0D,
				Math.max(c0.getZ(), c1.getZ()) + 1.0D);
	}

	// 按模盒坐标查找建造任务（GUI渲染线程调用，与服务端tick并发，需同步）
	public static ConstructionTask findTask(BlockPos constructorPos)
	{
		synchronized (tasks)
		{
			for (ConstructionTask task : tasks)
			{
				BuildingInstance building = task.getBuilding();
				BlockPos con = building.getConstructorPos();

				if ((con != null && con.equals(constructorPos))
						|| (con == null && building.getControlBoxPos().equals(constructorPos)))
				{
					return task;
				}
			}
		}
		return null;
	}

	public static List<BuildingInstance> getActiveBuildings()
	{
		List<BuildingInstance> buildings = new ArrayList<>();
		synchronized (tasks)
		{
			for (ConstructionTask task : tasks)
			{
				buildings.add(task.getBuilding());
			}
		}
		return buildings;
	}

	// C9：缺料任务列表缓存（每 tick 由 DeliveryEngine 失效一次，多个快递盒共享，避免逐盒重建）
	private static java.util.List<ConstructionTask> waitingCache;

	// 全部处于缺料等待中的建造任务（快递盒派单用）
	public static java.util.List<ConstructionTask> getWaitingTasks()
	{
		if (waitingCache != null) return waitingCache;
		synchronized (tasks)
		{
			java.util.List<ConstructionTask> out = new java.util.ArrayList<>();
			for (ConstructionTask task : tasks)
			{
				if (task.getState() == BuildingInstance.BuildState.WAITING_FOR_RESOURCES
						&& !task.isPaused())
				{
					out.add(task);
				}
			}
			waitingCache = out;
			return out;
		}
	}

	// 每 tick 开始时失效派单缓存
	public static void invalidateWaitingCache()
	{
		waitingCache = null;
	}

	// 模盒被破坏时取消模盒建造任务
	public static void cancelTaskAt(BlockPos constructorPos, ServerLevel level)
	{
		List<ConstructionTask> removedTasks = new ArrayList<>();
		synchronized (tasks)
		{
			Iterator<ConstructionTask> it = tasks.iterator();
			while (it.hasNext())
			{
				ConstructionTask task = it.next();
				BlockPos con = task.getBuilding().getConstructorPos();
				if ((con != null && con.equals(constructorPos))
						|| (con == null && task.getBuilding().getControlBoxPos().equals(constructorPos)))
				{
					removedTasks.add(task);
					it.remove();
				}
			}
		}
		if (!removedTasks.isEmpty())
		{
			LOGGER.info("NeoSim-ConstructionEngine: task cancelled — constructor box broken at {}",
					constructorPos);

			// 释放加载的区块
			for (ConstructionTask t : removedTasks)
			{
				BuildingChunkLoader.releaseForBuilding(t.getBuilding(), level);
			}
		}
	}

	// 每tick轮询调度建造任务（M14：快照列表后在锁外 tick；完工清理在锁内移除任务）
	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event)
	{
		ServerLevel level = event.getServer().overworld();
		maybeRestoreTasks(level);

		List<ConstructionTask> snapshot;
		synchronized (tasks)
		{
			snapshot = new ArrayList<>(tasks);
		}
		for (ConstructionTask task : snapshot)
		{
			if (task.getState() == BuildingInstance.BuildState.COMPLETE)
			{
				LOGGER.info("NeoSim-ConstructionEngine: task complete, removing");

				// 完工清理：解雇工人、恢复NPC的AI、清空模盒任务
				BuildingInstance finished = task.getBuilding();
				BlockPos conPos = finished.getConstructorPos();
				if (conPos != null)
				{
					String worker = NeoSim.WORKER_MAP.remove(conPos);
					if (worker != null)
					{
						// 全图按名查找：限半径会漏掉离家/远走的工人，导致其AI永不恢复
						com.wenzai.neosim.npc.Entity npc =
								com.wenzai.neosim.npc.Entity.findByNpcName(level, worker);
						if (npc != null)
						{
							npc.releaseFromSite();

							// 完工：手臂复位
							npc.setBuildAnim(0.0F);
							LOGGER.info("NeoSim-ConstructionEngine: builder '{}' released after completion", worker);
						}
					}

					// GUI已选蓝图缓存清理
					if (FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT)
					{
						ClientBlockInteractions.clearSelectedAt(conPos);
					}
				}

				// 完工：释放加载的区块
				BuildingChunkLoader.releaseForBuilding(finished, level);
				synchronized (tasks)
				{
					tasks.remove(task);
				}
				// L4：完工后置空 lastTask，防止 GUI 残留引用
				if (lastTask == task) lastTask = null;
				saveAllTasks(level);
				continue;
			}
			task.tick();
		}
	}

	// 服务器停止时保存所有任务（重启后恢复）
	@SubscribeEvent
	public static void onServerStopping(ServerStoppingEvent event)
	{
		saveAllTasks(event.getServer().overworld());

		// 清理限流器过期条目
		com.wenzai.neosim.util.JsonUtil.cleanup();

		// 重置恢复标记
		restoredFromDisk = false;

		LOGGER.info("NeoSim-ConstructionEngine: tasks saved on server stopping");
	}

	// 按城市保存所有活跃任务到文件
	public static void saveAllTasks(ServerLevel level)
	{
		List<ConstructionTask> snapshot;
		synchronized (tasks)
		{
			snapshot = new ArrayList<>(tasks);
		}
		if (snapshot.isEmpty()) return;

		Map<String, List<BuildingInstance>> byCity = new HashMap<>();
		for (ConstructionTask task : snapshot)
		{
			if (task.getState() == BuildingInstance.BuildState.COMPLETE) continue;
			BuildingInstance building = task.getBuilding();

			// 同步最新雇佣关系
			building.setWorkerName(NeoSim.WORKER_MAP.get(building.getConstructorPos()));
			String city = ConstructionTask.cityOf(building, level);
			if (city == null || city.isEmpty())
			{
				LOGGER.warn("NeoSim-ConstructionEngine: skip save '{}' — placer has no city",
						building.getSchematicName());
				continue;
			}
			byCity.computeIfAbsent(city, k -> new ArrayList<>()).add(building);
		}
		for (Map.Entry<String, List<BuildingInstance>> entry : byCity.entrySet())
		{
			BuildingPersistence.saveToCity(level, entry.getKey(), entry.getValue());
		}
	}

	// 蓝图注册表加载完成后，从磁盘恢复各城市的模盒任务
	private static boolean restoredFromDisk;

	private static void maybeRestoreTasks(ServerLevel level)
	{
		if (restoredFromDisk || !SchematicRegistry.getInstance().isLoaded()) return;
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
				String cityName = dir.getFileName().toString();
				List<BuildingInstance> buildings = BuildingPersistence.loadFromCity(level, cityName);
				for (BuildingInstance building : buildings)
				{
					if (building.getSchematic() == null)
					{
						LOGGER.warn("NeoSim-ConstructionEngine: skip restore '{}' — schematic missing",
								building.getSchematicName());
						continue;
					}

					if (building.getConstructorPos() != null
							&& !(level.getBlockState(building.getConstructorPos()).getBlock()
									instanceof com.wenzai.neosim.block.BuildingConstructor))
					{
						LOGGER.warn("NeoSim-ConstructionEngine: skip restore '{}' — constructor box gone at {}",
								building.getSchematicName(), building.getConstructorPos());

						// 释放对应NPC（全图按名查找，防漏）
						String worker = building.getWorkerName();
						if (worker != null && !worker.isEmpty())
						{
							com.wenzai.neosim.npc.Entity npc =
									com.wenzai.neosim.npc.Entity.findByNpcName(level, worker);
							if (npc != null)
							{
								npc.releaseFromSite();
								npc.setBuildAnim(0.0F);
							}
						}
						continue;
					}

					// 重建雇佣关系
					if (building.getConstructorPos() != null
							&& building.getWorkerName() != null
							&& !building.getWorkerName().isEmpty())
					{
						NeoSim.WORKER_MAP.put(building.getConstructorPos(), building.getWorkerName());
					}
					ConstructionTask task = new ConstructionTask(building, level);
					task.setState(building.getState());
					synchronized (tasks)
					{
						tasks.add(task);
					}
					// 重启后重新注册强制加载区块
					BuildingChunkLoader.registerForBuilding(building, level);
					LOGGER.info("NeoSim-ConstructionEngine: restored task '{}' at {} (state {})",
							building.getSchematicName(), building.getControlBoxPos(), building.getState());
				}
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-ConstructionEngine: restore failed", e);
		}
	}
}

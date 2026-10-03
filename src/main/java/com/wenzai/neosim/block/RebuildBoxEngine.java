package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.building.ControlBoxPersistence;
import com.wenzai.neosim.schematic.SchematicRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
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

// 重建盒调度：放置即绑定相邻控制箱、无 NPC 自动重建、重启恢复、拆除清理
@EventBusSubscriber(modid = NeoSim.MOD_ID)
public class RebuildBoxEngine
{
	// 工具类：只有静态成员，禁止实例化
	private RebuildBoxEngine()
	{
	}

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final List<RebuildTask> tasks = new ArrayList<>();
	private static boolean restoredFromDisk;
	private static int saveTimer;

	// 玩家放置重建盒：绑定相邻的已登记控制箱并建任务
	public static void onPlaced(ServerLevel level, BlockPos pos, Player player)
	{
		if (!Config.WORKBOX_ENABLED.get())
		{
			player.sendSystemMessage(Component.translatable("msg.neosim.workbox.disabled"));
			return;
		}

		ControlBoxPersistence.Located loc = findAdjacentControlBox(level, pos);
		if (loc == null)
		{
			player.sendSystemMessage(Component.translatable("msg.neosim.rebuild.noControlBox"));
			LOGGER.info("NeoSim-RebuildBoxEngine: no registered control box next to {}", pos);
			return;
		}

		ControlBoxPersistence.ControlBoxRecord cb = loc.record();
		if (SchematicRegistry.getInstance().get(cb.schematicName()) == null)
		{
			player.sendSystemMessage(Component.translatable("msg.neosim.rebuild.noSchematic", cb.schematicName()));
			LOGGER.warn("NeoSim-RebuildBoxEngine: schematic '{}' missing for rebuild box at {}",
					cb.schematicName(), pos);
			return;
		}

		RebuildBoxPersistence.RebuildRecord rec = RebuildBoxPersistence.RebuildRecord.of(
				pos, cb.boxPos(), cb.schematicName(), cb.originPos(),
				cb.rotation(), cb.mirror(), cb.facing(),
				player.getName().getString());

		RebuildBoxPersistence.addOrUpdate(level, loc.city(), rec);
		RebuildTask task = new RebuildTask(level, loc.city(), rec);
		synchronized (tasks)
		{
			tasks.removeIf(t -> t.boxPos().equals(pos));
			tasks.add(task);
		}
		player.sendSystemMessage(Component.translatable("msg.neosim.rebuild.bound", cb.schematicName()));
		LOGGER.info("NeoSim-RebuildBoxEngine: bound rebuild box {} to control box {} ('{}', city '{}')",
				pos, cb.boxPos(), cb.schematicName(), loc.city());
	}

	// 6 邻面找控制箱，并解析其登记记录（跨城市）
	@Nullable
	private static ControlBoxPersistence.Located findAdjacentControlBox(ServerLevel level, BlockPos pos)
	{
		for (Direction d : Direction.values())
		{
			BlockPos p = pos.relative(d);
			if (!(level.getBlockState(p).getBlock() instanceof ControlBox)) continue;
			ControlBoxPersistence.Located loc = ControlBoxPersistence.findRecordAnywhere(level, p);
			if (loc != null) return loc;
		}
		return null;
	}

	@Nullable
	public static RebuildTask findTask(BlockPos pos)
	{
		synchronized (tasks)
		{
			for (RebuildTask t : tasks)
			{
				if (t.boxPos().equals(pos)) return t;
			}
		}
		return null;
	}

	// 拆除/爆炸：移除任务、释放区块、删记录
	public static void removeAt(ServerLevel level, BlockPos pos)
	{
		RebuildTask removed = null;
		synchronized (tasks)
		{
			for (RebuildTask t : tasks)
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
			LOGGER.info("NeoSim-RebuildBoxEngine: task cancelled at {}", pos);
		}
		RebuildBoxPersistence.removeAt(level, pos);
	}

	// 右键状态提示（无 GUI：只在聊天栏报一行）
	public static void sendStatus(ServerLevel level, BlockPos pos, Player player)
	{
		RebuildTask task = findTask(pos);
		if (task == null)
		{
			// 无任务：从未绑定，或已重建完成终止
			player.sendSystemMessage(Component.translatable("msg.neosim.rebuild.idle"));
			return;
		}
		player.sendSystemMessage(Component.translatable("msg.neosim.rebuild.status",
				task.record().schematicName(), stateKey(task.getState())));
	}

	private static Component stateKey(RebuildTask.State state)
	{
		return Component.translatable(switch (state)
		{
			case WAITING -> "msg.neosim.rebuild.state.waiting";
			case UNBOUND -> "msg.neosim.rebuild.state.unbound";
			case COMPLETE -> "msg.neosim.rebuild.state.complete";
			default -> "msg.neosim.rebuild.state.running";
		});
	}

	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event)
	{
		ServerLevel level = event.getServer().overworld();
		maybeRestoreTasks(level);

		// 总开关关闭：暂停所有重建（任务保留，重新打开后继续）
		if (!Config.WORKBOX_ENABLED.get()) return;

		List<RebuildTask> snapshot;
		synchronized (tasks)
		{
			snapshot = new ArrayList<>(tasks);
		}
		for (RebuildTask task : snapshot)
		{
			// 重建完成：终止任务、释放强制加载区块、删除记录（盒子方块保留，不再工作）
			if (task.getState() == RebuildTask.State.COMPLETE)
			{
				synchronized (tasks)
				{
					tasks.remove(task);
				}
				task.onBoxDestroyed();
				RebuildBoxPersistence.removeAt(level, task.boxPos());
				LOGGER.info("NeoSim-RebuildBoxEngine: rebuild complete, task terminated at {}", task.boxPos());
				continue;
			}

			// 盒子没了（爆炸/外部移除）：清理任务与记录
			if (!(level.getBlockState(task.boxPos()).getBlock() instanceof RebuildBox))
			{
				synchronized (tasks)
				{
					tasks.remove(task);
				}
				task.onBoxDestroyed();
				RebuildBoxPersistence.removeAt(level, task.boxPos());
				LOGGER.warn("NeoSim-RebuildBoxEngine: rebuild box gone at {}, task dropped", task.boxPos());
				continue;
			}
			try
			{
				task.tick();
			}
			catch (Exception e)
			{
				LOGGER.error("NeoSim-RebuildBoxEngine: tick error at {}, skipped", task.boxPos(), e);
			}
		}

		saveTimer++;
		if (saveTimer >= 100)
		{
			saveTimer = 0;
			saveAll(level);
		}
	}

	@SubscribeEvent
	public static void onServerStopping(ServerStoppingEvent event)
	{
		saveAll(event.getServer().overworld());
		synchronized (tasks)
		{
			tasks.clear();
		}
		restoredFromDisk = false;
		RebuildChunkLoader.clear();
		LOGGER.info("NeoSim-RebuildBoxEngine: tasks saved & cleared on server stopping");
	}

	public static void saveAll(ServerLevel level)
	{
		List<RebuildTask> snapshot;
		synchronized (tasks)
		{
			snapshot = new ArrayList<>(tasks);
		}
		Map<String, List<RebuildBoxPersistence.RebuildRecord>> byCity = new HashMap<>();
		for (RebuildTask t : snapshot)
		{
			byCity.computeIfAbsent(t.cityName(), k -> new ArrayList<>()).add(t.record());
		}
		for (Map.Entry<String, List<RebuildBoxPersistence.RebuildRecord>> e : byCity.entrySet())
		{
			RebuildBoxPersistence.save(level, e.getKey(), e.getValue());
		}
	}

	// 启动恢复：按城市读 RebuildBox.json，重建任务
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
				for (RebuildBoxPersistence.RebuildRecord rec : RebuildBoxPersistence.load(level, city))
				{
					// 上次已完成：不再恢复任务（盒子保留，重新放置可再次工作）
					if ("COMPLETE".equals(rec.state()))
					{
						RebuildBoxPersistence.removeAt(level, rec.boxPos());
						continue;
					}

					if (!(level.getBlockState(rec.boxPos()).getBlock() instanceof RebuildBox))
					{
						RebuildBoxPersistence.removeAt(level, rec.boxPos());
						LOGGER.warn("NeoSim-RebuildBoxEngine: skip restore at {} — box gone", rec.boxPos());
						continue;
					}
					if (!(level.getBlockState(rec.controlBoxPos()).getBlock() instanceof ControlBox))
					{
						RebuildBoxPersistence.removeAt(level, rec.boxPos());
						LOGGER.warn("NeoSim-RebuildBoxEngine: skip restore at {} — control box gone", rec.boxPos());
						continue;
					}
					if (SchematicRegistry.getInstance().get(rec.schematicName()) == null)
					{
						LOGGER.warn("NeoSim-RebuildBoxEngine: schematic '{}' not loaded yet for {} — task kept idle",
								rec.schematicName(), rec.boxPos());
					}
					RebuildTask task = new RebuildTask(level, city, rec);
					synchronized (tasks)
					{
						tasks.add(task);
					}
					LOGGER.info("NeoSim-RebuildBoxEngine: restored task at {} ('{}')",
							rec.boxPos(), rec.schematicName());
				}
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-RebuildBoxEngine: restore failed", e);
		}
	}
}

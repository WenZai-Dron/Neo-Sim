package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.annotation.Nullable;

public class DeliveryBoxPersistence
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// 记录：盒子 + 雇佣 + 暂停 + 状态
	public record DeliveryBoxRecord(
			int bx, int by, int bz,
			String worker,
			boolean paused,
			String state,
			String placer)
	{
		public static DeliveryBoxRecord of(BlockPos box, String placer)
		{
			return new DeliveryBoxRecord(box.getX(), box.getY(), box.getZ(),
					null, false, "IDLE", placer);
		}

		public BlockPos boxPos()
		{
			return new BlockPos(bx, by, bz);
		}

		public DeliveryBoxRecord withWorker(String name)
		{
			return new DeliveryBoxRecord(bx, by, bz, name, paused, state, placer);
		}

		public DeliveryBoxRecord withPaused(boolean p)
		{
			return new DeliveryBoxRecord(bx, by, bz, worker, p, state, placer);
		}

		public DeliveryBoxRecord withState(String s)
		{
			return new DeliveryBoxRecord(bx, by, bz, worker, paused, s, placer);
		}
	}

	private static String fileName()
	{
		return "DeliveryBox.json";
	}

	// 城市目录
	private static Path getCityDir(ServerLevel level, String cityName)
	{
		Path base = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		boolean dedicated = level.getServer().isDedicatedServer();
		String saveName = dedicated ? null : level.getServer().getWorldData().getLevelName();
		return (saveName == null || saveName.isEmpty())
				? base.resolve(cityName)
				: base.resolve(saveName).resolve(cityName);
	}

	// 记录文件路径
	public static Path getCityPath(@Nullable String saveName, String cityName)
	{
		Path base = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		Path cityDir = (saveName == null || saveName.isEmpty())
				? base.resolve(cityName)
				: base.resolve(saveName).resolve(cityName);
		return cityDir.resolve(fileName());
	}

	// 加载某城市全部快递盒记录
	public static List<DeliveryBoxRecord> load(ServerLevel level, String cityName)
	{
		return readRecords(getCityPath(level.getServer().isDedicatedServer() ? null
				: level.getServer().getWorldData().getLevelName(), cityName));
	}

	// 保存某城市全部快递盒记录
	public static void save(ServerLevel level, String cityName, List<DeliveryBoxRecord> records)
	{
		String saveName = level.getServer().isDedicatedServer() ? null
				: level.getServer().getWorldData().getLevelName();
		writeRecords(getCityPath(saveName, cityName), records);
	}

	// 按盒子位置查找记录（服务端）
	@Nullable
	public static DeliveryBoxRecord findRecord(ServerLevel level, String cityName, BlockPos pos)
	{
		for (DeliveryBoxRecord r : load(level, cityName))
		{
			if (r.boxPos().equals(pos)) return r;
		}
		return null;
	}

	// 更新：按盒子位置在文件中替换记录，不存在则追加
	public static void updateRecord(ServerLevel level, String cityName, DeliveryBoxRecord record)
	{
		String saveName = level.getServer().isDedicatedServer() ? null
				: level.getServer().getWorldData().getLevelName();
		List<DeliveryBoxRecord> records = new ArrayList<>(load(level, cityName));
		records.removeIf(r -> r.boxPos().equals(record.boxPos()));
		records.add(record);
		writeRecords(getCityPath(saveName, cityName), records);
		LOGGER.info("NeoSim-DeliveryBoxPersistence: recorded delivery box {} for '{}'",
				record.boxPos(), cityName);
	}

	// 删除：扫描所有城市目录，按盒子位置移除并返回；未找到返回 null
	@Nullable
	public static DeliveryBoxRecord removeAt(ServerLevel level, BlockPos pos)
	{
		Path dataDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		if (!level.getServer().isDedicatedServer())
		{
			dataDir = dataDir.resolve(level.getServer().getWorldData().getLevelName());
		}
		if (!Files.isDirectory(dataDir)) return null;

		try (Stream<Path> dirs = Files.list(dataDir))
		{
			for (Path dir : dirs.filter(Files::isDirectory).toList())
			{
				Path file = dir.resolve(fileName());
				if (!Files.exists(file)) continue;
				List<DeliveryBoxRecord> records = readRecords(file);
				for (DeliveryBoxRecord r : records)
				{
					if (r.boxPos().equals(pos))
					{
						records.remove(r);
						writeRecords(file, records);
						LOGGER.info("NeoSim-DeliveryBoxPersistence: removed delivery box record at {}", pos);
						return r;
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-DeliveryBoxPersistence: removeAt failed", e);
		}
		return null;
	}

	// 客户端查找
	@Nullable
	public static DeliveryBoxRecord findRecord(@Nullable String saveName, String cityName, BlockPos pos)
	{
		Path file = getCityPath(saveName, cityName);
		if (!Files.exists(file)) return null;
		for (DeliveryBoxRecord r : readRecords(file))
		{
			if (r.boxPos().equals(pos)) return r;
		}
		return null;
	}

	private static void writeRecords(Path file, List<DeliveryBoxRecord> records)
	{
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (DeliveryBoxRecord r : records)
		{
			arr.add(recordToJson(r));
		}
		JsonUtil.write(file, arr);
	}

	private static List<DeliveryBoxRecord> readRecords(Path file)
	{
		List<DeliveryBoxRecord> records = new ArrayList<>();
		if (!Files.exists(file)) return records;

		com.google.gson.JsonArray arr = JsonUtil.readArray(file);
		if (arr == null) return records;
		for (com.google.gson.JsonElement e : arr)
		{
			if (!e.isJsonObject()) continue;
			DeliveryBoxRecord rec = recordFromJson(e.getAsJsonObject());
			if (rec != null) records.add(rec);
		}
		return records;
	}

	private static com.google.gson.JsonObject recordToJson(DeliveryBoxRecord r)
	{
		com.google.gson.JsonObject obj = new com.google.gson.JsonObject();

		com.google.gson.JsonObject box = new com.google.gson.JsonObject();
		box.addProperty("x", r.bx);
		box.addProperty("y", r.by);
		box.addProperty("z", r.bz);
		obj.add("box", box);

		if (r.worker() != null && !r.worker().isEmpty()) obj.addProperty("worker", r.worker());
		obj.addProperty("paused", r.paused);
		obj.addProperty("state", r.state);
		if (r.placer() != null && !r.placer().isEmpty()) obj.addProperty("placer", r.placer());
		return obj;
	}

	private static DeliveryBoxRecord recordFromJson(com.google.gson.JsonObject obj)
	{
		try
		{
			com.google.gson.JsonObject box = JsonUtil.getObject(obj, "box");
			BlockPos boxPos = box != null
					? new BlockPos(JsonUtil.getInt(box, "x", 0), JsonUtil.getInt(box, "y", 0), JsonUtil.getInt(box, "z", 0))
					: BlockPos.ZERO;

			String worker = JsonUtil.getString(obj, "worker", null);
			boolean paused = JsonUtil.getBoolean(obj, "paused", false);
			String state = JsonUtil.getString(obj, "state", "IDLE");
			String placer = JsonUtil.getString(obj, "placer", null);

			// 篡改/损坏的数值一律规范化，杜绝越界
			int bx = JsonUtil.clampX(boxPos.getX());
			int by = JsonUtil.clampY(boxPos.getY());
			int bz = JsonUtil.clampX(boxPos.getZ());

			// 飞行中的状态无法恢复（缺目标坐标）：一律回到 IDLE，快递员会走回站点
			if (!"IDLE".equals(state) && !"WAITING_WORKER".equals(state) && !"WORKER_ASSIGNED".equals(state))
			{
				state = "IDLE";
			}

			return new DeliveryBoxRecord(bx, by, bz, worker, paused, state, placer);
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-DeliveryBoxPersistence: skip bad record", e);
			return null;
		}
	}
}

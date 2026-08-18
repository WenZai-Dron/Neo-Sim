package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class WorkBoxPersistence
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final int PROX_MARGIN = 256;

	// 记录：盒子+绑定矩形快照+雇佣+游标/状态
	public record WorkBoxRecord(
			String type,                        // 盒子名
			int bx, int by, int bz,             // 盒子位置
			int rx1, int ry, int rz1,           // 绑定矩形
			int rx2, int rz2,                   // 绑定矩形
			String worker,
			boolean paused,
			int row, int col,                   // 农业/矿业游标
			String state,
			String placer,
			String farmType,                    // 农业专属：作物名
			int discards,                       // 矿业专属：丢弃过滤
			int depth,                          // 矿业专属：当前已挖到Y
			boolean bound)                      // 是否已绑定矩形
	{
		public static WorkBoxRecord of(String type, BlockPos box, MarkerManager.MarkerRect rect, String placer)
		{
			return new WorkBoxRecord(type, box.getX(), box.getY(), box.getZ(),
					rect.minX(), rect.minY(), rect.minZ(), rect.maxX(), rect.maxZ(),
					null, false, 0, 0, "IDLE", placer, "",
					Config.WORK_MINE_DISCARDS.get(), rect.minY() - 1, true);
		}

		// 未绑定（无可用矩形/矩形被占用）：只留记录供GUI提示
		public static WorkBoxRecord ofUnbound(String type, BlockPos box, String placer)
		{
			return new WorkBoxRecord(type, box.getX(), box.getY(), box.getZ(),
					0, 0, 0, 0, 0, null, false, 0, 0, "IDLE", placer, "",
					Config.WORK_MINE_DISCARDS.get(), 0, false);
		}

		public BlockPos boxPos() { return new BlockPos(bx, by, bz); }

		public WorkBoxRecord withWorker(String name)
		{
			return new WorkBoxRecord(type, bx, by, bz, rx1, ry, rz1, rx2, rz2,
					name, paused, row, col, state, placer, farmType, discards, depth, bound);
		}

		public WorkBoxRecord withPaused(boolean p)
		{
			return new WorkBoxRecord(type, bx, by, bz, rx1, ry, rz1, rx2, rz2,
					worker, p, row, col, state, placer, farmType, discards, depth, bound);
		}

		public WorkBoxRecord withCursor(int r, int c)
		{
			return new WorkBoxRecord(type, bx, by, bz, rx1, ry, rz1, rx2, rz2,
					worker, paused, r, c, state, placer, farmType, discards, depth, bound);
		}

		public WorkBoxRecord withState(String s)
		{
			return new WorkBoxRecord(type, bx, by, bz, rx1, ry, rz1, rx2, rz2,
					worker, paused, row, col, s, placer, farmType, discards, depth, bound);
		}

		public WorkBoxRecord withFarmType(String t)
		{
			return new WorkBoxRecord(type, bx, by, bz, rx1, ry, rz1, rx2, rz2,
					worker, paused, row, col, state, placer, t, discards, depth, bound);
		}

		public WorkBoxRecord withDiscards(int d)
		{
			return new WorkBoxRecord(type, bx, by, bz, rx1, ry, rz1, rx2, rz2,
					worker, paused, row, col, state, placer, farmType, d, depth, bound);
		}

		public WorkBoxRecord withDepth(int y)
		{
			return new WorkBoxRecord(type, bx, by, bz, rx1, ry, rz1, rx2, rz2,
					worker, paused, row, col, state, placer, farmType, discards, y, bound);
		}

		// 补上矩形快照，保留已有设置
		public WorkBoxRecord withRect(MarkerManager.MarkerRect rect)
		{
			return new WorkBoxRecord(type, bx, by, bz,
					rect.minX(), rect.minY(), rect.minZ(), rect.maxX(), rect.maxZ(),
					worker, paused, row, col, state, placer, farmType, discards,
					rect.minY() - 1, true);
		}
	}

	// 按类型取文件名：农业盒/矿业盒各自独立落盘
	private static String fileNameFor(String type)
	{
		return "farming".equals(type) ? "FarmingBox.json" : "MiningBox.json";
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
	public static Path getCityPath(@Nullable String saveName, String cityName, String type)
	{
		Path base = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		Path cityDir = (saveName == null || saveName.isEmpty())
				? base.resolve(cityName)
				: base.resolve(saveName).resolve(cityName);
		return cityDir.resolve(fileNameFor(type));
	}

	// 加载某城市某类型的工作盒记录
	private static List<WorkBoxRecord> loadType(ServerLevel level, String cityName, String type)
	{
		return readRecords(getCityPath(level.getServer().isDedicatedServer() ? null
				: level.getServer().getWorldData().getLevelName(), cityName, type));
	}

	// 加载某城市的全部工作盒记录
	public static List<WorkBoxRecord> load(ServerLevel level, String cityName)
	{
		migrateLegacy(level, cityName);
		List<WorkBoxRecord> out = new ArrayList<>();
		out.addAll(loadType(level, cityName, "farming"));
		out.addAll(loadType(level, cityName, "mining"));
		return out;
	}

	private static void migrateLegacy(ServerLevel level, String cityName)
	{
		Path cityDir = getCityDir(level, cityName);
		Path legacy = cityDir.resolve("WorkBoxes.json");
		if (!Files.exists(legacy)) return;
		Path farmFile = cityDir.resolve("FarmingBox.json");
		Path mineFile = cityDir.resolve("MiningBox.json");
		if (Files.exists(farmFile) || Files.exists(mineFile)) return;

		List<WorkBoxRecord> records = readRecords(legacy);
		List<WorkBoxRecord> farm = new ArrayList<>();
		List<WorkBoxRecord> mine = new ArrayList<>();
		for (WorkBoxRecord r : records)
		{
			("farming".equals(r.type()) ? farm : mine).add(r);
		}
		if (!farm.isEmpty()) writeRecords(farmFile, farm);
		if (!mine.isEmpty()) writeRecords(mineFile, mine);
		try
		{
			Files.delete(legacy);
			LOGGER.info("NeoSim-WorkBoxPersistence: migrated WorkBoxes.json -> FarmingBox/MiningBox for '{}'", cityName);
		}
		catch (IOException e)
		{
			LOGGER.warn("NeoSim-WorkBoxPersistence: legacy file delete failed", e);
		}
	}

	// 保存某城市的全部工作盒记录
	public static void save(ServerLevel level, String cityName, List<WorkBoxRecord> records)
	{
		List<WorkBoxRecord> farm = new ArrayList<>();
		List<WorkBoxRecord> mine = new ArrayList<>();
		for (WorkBoxRecord r : records)
		{
			("farming".equals(r.type()) ? farm : mine).add(r);
		}
		String saveName = level.getServer().isDedicatedServer() ? null
				: level.getServer().getWorldData().getLevelName();
		writeRecords(getCityPath(saveName, cityName, "farming"), farm);
		writeRecords(getCityPath(saveName, cityName, "mining"), mine);
	}

	// 按盒子位置查找记录
	@Nullable
	public static WorkBoxRecord findRecord(ServerLevel level, String cityName, BlockPos pos)
	{
		for (WorkBoxRecord r : load(level, cityName))
		{
			if (r.boxPos().equals(pos)) return r;
		}
		return null;
	}

	// 更新：按盒子位置在所属类型文件中替换记录
	public static void updateRecord(ServerLevel level, String cityName, WorkBoxRecord record)
	{
		List<WorkBoxRecord> records = new ArrayList<>(loadType(level, cityName, record.type()));
		for (int i = 0; i < records.size(); i++)
		{
			if (records.get(i).boxPos().equals(record.boxPos()))
			{
				records.set(i, record);
				writeRecords(getCityPath(level.getServer().isDedicatedServer() ? null
						: level.getServer().getWorldData().getLevelName(), cityName, record.type()), records);
				return;
			}
		}
		records.add(record);
		writeRecords(getCityPath(level.getServer().isDedicatedServer() ? null
				: level.getServer().getWorldData().getLevelName(), cityName, record.type()), records);
	}

	// 增改：同位置已存在则覆盖，否则追加
	public static void addOrUpdate(ServerLevel level, String cityName, WorkBoxRecord record)
	{
		String saveName = level.getServer().isDedicatedServer() ? null
				: level.getServer().getWorldData().getLevelName();
		List<WorkBoxRecord> records = new ArrayList<>(loadType(level, cityName, record.type()));
		records.removeIf(r -> r.boxPos().equals(record.boxPos()));
		records.add(record);
		writeRecords(getCityPath(saveName, cityName, record.type()), records);
		LOGGER.debug("NeoSim-WorkBoxPersistence: recorded work box {} (type={}) for '{}'",
				record.boxPos(), record.type(), cityName);
	}

	// 删除：按盒子位置扫描所有城市目录的两种文件，找到即移除并返回。未找到返回null
	@Nullable
	public static WorkBoxRecord removeAt(ServerLevel level, BlockPos pos)
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
				for (String fn : new String[] { "FarmingBox.json", "MiningBox.json" })
				{
					Path file = dir.resolve(fn);
					if (!Files.exists(file)) continue;
					List<WorkBoxRecord> records = readRecords(file);
					for (WorkBoxRecord r : records)
					{
						if (r.boxPos().equals(pos))
						{
							records.remove(r);
							writeRecords(file, records);
							LOGGER.info("NeoSim-WorkBoxPersistence: removed work box record at {}", pos);
							return r;
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-WorkBoxPersistence: removeAt failed", e);
		}
		return null;
	}

	// 绑定矩形是否已被同城其他盒子占用
	public static boolean rectInUse(ServerLevel level, String cityName, MarkerManager.MarkerRect rect)
	{
		for (WorkBoxRecord r : load(level, cityName))
		{
			if (!r.bound()) continue;
			if (r.ry() != rect.minY()) continue;
			if (r.rx1() <= rect.maxX() && rect.minX() <= r.rx2()
					&& r.rz1() <= rect.maxZ() && rect.minZ() <= r.rz2())
			{
				return true;
			}
		}
		return false;
	}

	// 客户端查找
	@Nullable
	public static WorkBoxRecord findRecord(@Nullable String saveName, String cityName, BlockPos pos)
	{
		for (String type : new String[] { "farming", "mining" })
		{
			Path file = getCityPath(saveName, cityName, type);
			if (!Files.exists(file)) continue;
			for (WorkBoxRecord r : readRecords(file))
			{
				if (r.boxPos().equals(pos)) return r;
			}
		}
		return null;
	}

	private static void writeRecords(Path file, List<WorkBoxRecord> records)
	{
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (WorkBoxRecord r : records)
		{
			arr.add(recordToJson(r));
		}
		JsonUtil.write(file, arr);
	}

	private static List<WorkBoxRecord> readRecords(Path file)
	{
		List<WorkBoxRecord> records = new ArrayList<>();
		if (!Files.exists(file)) return records;

		com.google.gson.JsonArray arr = JsonUtil.readArray(file);
		if (arr == null) return records;
		for (com.google.gson.JsonElement e : arr)
		{
			if (!e.isJsonObject()) continue;
			WorkBoxRecord rec = recordFromJson(e.getAsJsonObject());
			if (rec != null) records.add(rec);
		}
		return records;
	}

	private static com.google.gson.JsonObject recordToJson(WorkBoxRecord r)
	{
		com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
		obj.addProperty("type", r.type());

		com.google.gson.JsonObject box = new com.google.gson.JsonObject();
		box.addProperty("x", r.bx);
		box.addProperty("y", r.by);
		box.addProperty("z", r.bz);
		obj.add("box", box);

		com.google.gson.JsonObject rect = new com.google.gson.JsonObject();
		rect.addProperty("minX", r.rx1);
		rect.addProperty("minY", r.ry);
		rect.addProperty("minZ", r.rz1);
		rect.addProperty("maxX", r.rx2);
		rect.addProperty("maxY", r.ry);
		rect.addProperty("maxZ", r.rz2);
		obj.add("rect", rect);

		if (r.worker() != null && !r.worker().isEmpty()) obj.addProperty("worker", r.worker());
		obj.addProperty("paused", r.paused);

		com.google.gson.JsonObject progress = new com.google.gson.JsonObject();
		progress.addProperty("row", r.row);
		progress.addProperty("col", r.col);
		obj.add("progress", progress);

		obj.addProperty("state", r.state);
		if (r.placer() != null && !r.placer().isEmpty()) obj.addProperty("placer", r.placer());
		obj.addProperty("farmType", r.farmType);
		obj.addProperty("discards", r.discards);
		obj.addProperty("depth", r.depth);
		obj.addProperty("bound", r.bound);
		return obj;
	}

	private static WorkBoxRecord recordFromJson(com.google.gson.JsonObject obj)
	{
		try
		{
			String type = JsonUtil.getString(obj, "type", "farming");

			com.google.gson.JsonObject box = JsonUtil.getObject(obj, "box");
			BlockPos boxPos = box != null
					? new BlockPos(JsonUtil.getInt(box, "x", 0), JsonUtil.getInt(box, "y", 0), JsonUtil.getInt(box, "z", 0))
					: BlockPos.ZERO;

			com.google.gson.JsonObject rect = JsonUtil.getObject(obj, "rect");
			int rx1 = 0, ry = 0, rz1 = 0, rx2 = 0, rz2 = 0;
			if (rect != null)
			{
				rx1 = JsonUtil.getInt(rect, "minX", 0);
				ry = JsonUtil.getInt(rect, "minY", 0);
				rz1 = JsonUtil.getInt(rect, "minZ", 0);
				rx2 = JsonUtil.getInt(rect, "maxX", 0);
				rz2 = JsonUtil.getInt(rect, "maxZ", 0);
			}

			String worker = JsonUtil.getString(obj, "worker", null);
			boolean paused = JsonUtil.getBoolean(obj, "paused", false);

			com.google.gson.JsonObject progress = JsonUtil.getObject(obj, "progress");
			int row = progress != null ? JsonUtil.getInt(progress, "row", 0) : 0;
			int col = progress != null ? JsonUtil.getInt(progress, "col", 0) : 0;

			String state = JsonUtil.getString(obj, "state", "IDLE");
			String placer = JsonUtil.getString(obj, "placer", null);
			String farmType = JsonUtil.getString(obj, "farmType", "");
			int discards = JsonUtil.getInt(obj, "discards", Config.WORK_MINE_DISCARDS.get());
			int depth = JsonUtil.getInt(obj, "depth", 0);
			boolean bound = JsonUtil.getBoolean(obj, "bound", true);

			// 篡改/损坏的数值一律规范化，杜绝越界
			int bx = JsonUtil.clampX(boxPos.getX());
			int by = JsonUtil.clampY(boxPos.getY());
			int bz = JsonUtil.clampX(boxPos.getZ());

			// 矩形反向则交换，保证min<=max
			if (rx1 > rx2) { int t = rx1; rx1 = rx2; rx2 = t; }
			if (rz1 > rz2) { int t = rz1; rz1 = rz2; rz2 = t; }
			rx1 = JsonUtil.clampX(rx1);
			rx2 = JsonUtil.clampX(rx2);
			ry = JsonUtil.clampY(ry);
			rz1 = JsonUtil.clampX(rz1);
			rz2 = JsonUtil.clampX(rz2);
			depth = JsonUtil.clampY(depth);
			row = JsonUtil.clampInt(row, 0, 1_000_000);
			col = JsonUtil.clampInt(col, 0, 1_000_000);
			discards = JsonUtil.clampInt(discards, 0, 7);

			// 已绑定但矩形全零：矩形丢失，退回未绑定
			if (bound && rx1 == 0 && ry == 0 && rz1 == 0 && rx2 == 0 && rz2 == 0)
			{
				bound = false;
			}
			// 矩形与盒子相距过远：判定为篡改数据，退回未绑定（防远处区块强制加载卡服）
			else if (bound && (Math.abs(rx1 - bx) > PROX_MARGIN || Math.abs(rx2 - bx) > PROX_MARGIN
					|| Math.abs(rz1 - bz) > PROX_MARGIN || Math.abs(rz2 - bz) > PROX_MARGIN
					|| Math.abs(ry - by) > PROX_MARGIN))
			{
				LOGGER.warn("NeoSim-WorkBoxPersistence: rect far from box at ({},{},{}) — drop bind",
						bx, by, bz);
				bound = false;
			}

			return new WorkBoxRecord(type, bx, by, bz,
					rx1, ry, rz1, rx2, rz2, worker, paused, row, col, state,
					placer, farmType, discards, depth, bound);
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-WorkBoxPersistence: skip bad record", e);
			return null;
		}
	}
}

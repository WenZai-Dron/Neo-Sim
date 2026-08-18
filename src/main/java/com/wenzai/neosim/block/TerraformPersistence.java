package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

// 整地任务持久化：每城市 Terraform.json
public class TerraformPersistence
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final int PROX_MARGIN = 256;

	// 记录：模盒 + 地块快照 + 方案 + 雇佣 + 游标/状态
	public record TerraformRecord(
			int bx, int by, int bz,             // 模盒位置
			String plan,                        // TerraformPlan 枚举名
			int minX, int minZ, int maxX, int maxZ, int baselineY,  // 地块快照
			String worker,
			boolean paused,
			int progress, int total,            // 目标列表游标 / 总数（展示用，恢复时重扫）
			String state,
			String placer)
	{
		public static TerraformRecord of(BlockPos box, TerraformPlan plan,
				int minX, int minZ, int maxX, int maxZ, int baselineY, String placer)
		{
			return new TerraformRecord(box.getX(), box.getY(), box.getZ(), plan.name(),
					minX, minZ, maxX, maxZ, baselineY, null, false, 0, 0, "IDLE", placer);
		}

		public BlockPos boxPos() { return new BlockPos(bx, by, bz); }

		public TerraformRecord withWorker(String name)
		{
			return new TerraformRecord(bx, by, bz, plan, minX, minZ, maxX, maxZ, baselineY,
					name, paused, progress, total, state, placer);
		}

		public TerraformRecord withPaused(boolean p)
		{
			return new TerraformRecord(bx, by, bz, plan, minX, minZ, maxX, maxZ, baselineY,
					worker, p, progress, total, state, placer);
		}

		public TerraformRecord withCursor(int progress, int total)
		{
			return new TerraformRecord(bx, by, bz, plan, minX, minZ, maxX, maxZ, baselineY,
					worker, paused, progress, total, state, placer);
		}

		public TerraformRecord withState(String s)
		{
			return new TerraformRecord(bx, by, bz, plan, minX, minZ, maxX, maxZ, baselineY,
					worker, paused, progress, total, s, placer);
		}
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
	private static Path getFilePath(ServerLevel level, String cityName)
	{
		return getCityDir(level, cityName).resolve("Terraform.json");
	}

	// 加载某城市全部整地记录
	public static List<TerraformRecord> load(ServerLevel level, String cityName)
	{
		return readRecords(getFilePath(level, cityName));
	}

	// 批量写某城市全部记录（D3：一次整文件写，替代逐任务读+写）
	public static void save(ServerLevel level, String cityName, List<TerraformRecord> records)
	{
		writeRecords(getFilePath(level, cityName), records);
	}

	// 按模盒位置查找记录
	@Nullable
	public static TerraformRecord findRecord(ServerLevel level, String cityName, BlockPos pos)
	{
		for (TerraformRecord r : load(level, cityName))
		{
			if (r.boxPos().equals(pos)) return r;
		}
		return null;
	}

	// 增改：同位置已存在则覆盖，否则追加
	public static void addOrUpdate(ServerLevel level, String cityName, TerraformRecord record)
	{
		List<TerraformRecord> records = new ArrayList<>(load(level, cityName));
		records.removeIf(r -> r.boxPos().equals(record.boxPos()));
		records.add(record);
		writeRecords(getFilePath(level, cityName), records);
		LOGGER.info("NeoSim-TerraformPersistence: recorded terraform task {} ({}) for '{}'",
				record.boxPos(), record.plan(), cityName);
	}

	// 更新：按模盒位置替换记录（不存在则追加）
	public static void updateRecord(ServerLevel level, String cityName, TerraformRecord record)
	{
		List<TerraformRecord> records = new ArrayList<>(load(level, cityName));
		records.removeIf(r -> r.boxPos().equals(record.boxPos()));
		records.add(record);
		writeRecords(getFilePath(level, cityName), records);
	}

	// 删除：扫描所有城市目录，找到即移除。未找到返回 null
	@Nullable
	public static TerraformRecord removeAt(ServerLevel level, BlockPos pos)
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
				Path file = dir.resolve("Terraform.json");
				if (!Files.exists(file)) continue;
				List<TerraformRecord> records = readRecords(file);
				for (TerraformRecord r : records)
				{
					if (r.boxPos().equals(pos))
					{
						records.remove(r);
						writeRecords(file, records);
						LOGGER.info("NeoSim-TerraformPersistence: removed terraform record at {}", pos);
						return r;
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-TerraformPersistence: removeAt failed", e);
		}
		return null;
	}

	private static void writeRecords(Path file, List<TerraformRecord> records)
	{
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (TerraformRecord r : records)
		{
			arr.add(recordToJson(r));
		}
		JsonUtil.write(file, arr);
	}

	private static List<TerraformRecord> readRecords(Path file)
	{
		List<TerraformRecord> records = new ArrayList<>();
		if (!Files.exists(file)) return records;

		com.google.gson.JsonArray arr = JsonUtil.readArray(file);
		if (arr == null) return records;
		for (com.google.gson.JsonElement e : arr)
		{
			if (!e.isJsonObject()) continue;
			TerraformRecord rec = recordFromJson(e.getAsJsonObject());
			if (rec != null) records.add(rec);
		}
		return records;
	}

	private static com.google.gson.JsonObject recordToJson(TerraformRecord r)
	{
		com.google.gson.JsonObject obj = new com.google.gson.JsonObject();

		com.google.gson.JsonObject box = new com.google.gson.JsonObject();
		box.addProperty("x", r.bx);
		box.addProperty("y", r.by);
		box.addProperty("z", r.bz);
		obj.add("box", box);

		obj.addProperty("plan", r.plan);

		com.google.gson.JsonObject rect = new com.google.gson.JsonObject();
		rect.addProperty("minX", r.minX);
		rect.addProperty("minZ", r.minZ);
		rect.addProperty("maxX", r.maxX);
		rect.addProperty("maxZ", r.maxZ);
		rect.addProperty("baselineY", r.baselineY);
		obj.add("rect", rect);

		if (r.worker() != null && !r.worker().isEmpty()) obj.addProperty("worker", r.worker());
		obj.addProperty("paused", r.paused);

		com.google.gson.JsonObject progress = new com.google.gson.JsonObject();
		progress.addProperty("progress", r.progress);
		progress.addProperty("total", r.total);
		obj.add("progress", progress);

		obj.addProperty("state", r.state);
		if (r.placer() != null && !r.placer().isEmpty()) obj.addProperty("placer", r.placer());
		return obj;
	}

	private static TerraformRecord recordFromJson(com.google.gson.JsonObject obj)
	{
		try
		{
			com.google.gson.JsonObject box = JsonUtil.getObject(obj, "box");
			BlockPos boxPos = box != null
					? new BlockPos(JsonUtil.getInt(box, "x", 0), JsonUtil.getInt(box, "y", 0), JsonUtil.getInt(box, "z", 0))
					: BlockPos.ZERO;

			String plan = JsonUtil.getString(obj, "plan", "");

			com.google.gson.JsonObject rect = JsonUtil.getObject(obj, "rect");
			int minX = 0, minZ = 0, maxX = 0, maxZ = 0, baselineY = 0;
			if (rect != null)
			{
				minX = JsonUtil.getInt(rect, "minX", 0);
				minZ = JsonUtil.getInt(rect, "minZ", 0);
				maxX = JsonUtil.getInt(rect, "maxX", 0);
				maxZ = JsonUtil.getInt(rect, "maxZ", 0);
				baselineY = JsonUtil.getInt(rect, "baselineY", 0);
			}

			String worker = JsonUtil.getString(obj, "worker", null);
			boolean paused = JsonUtil.getBoolean(obj, "paused", false);

			com.google.gson.JsonObject progress = JsonUtil.getObject(obj, "progress");
			int progressIdx = progress != null ? JsonUtil.getInt(progress, "progress", 0) : 0;
			int total = progress != null ? JsonUtil.getInt(progress, "total", 0) : 0;

			String state = JsonUtil.getString(obj, "state", "IDLE");
			String placer = JsonUtil.getString(obj, "placer", null);

			// 篡改/损坏的数值一律规范化
			int bx = JsonUtil.clampX(boxPos.getX());
			int by = JsonUtil.clampY(boxPos.getY());
			int bz = JsonUtil.clampX(boxPos.getZ());
			if (minX > maxX) { int t = minX; minX = maxX; maxX = t; }
			if (minZ > maxZ) { int t = minZ; minZ = maxZ; maxZ = t; }
			minX = JsonUtil.clampX(minX);
			maxX = JsonUtil.clampX(maxX);
			minZ = JsonUtil.clampX(minZ);
			maxZ = JsonUtil.clampX(maxZ);
			baselineY = JsonUtil.clampY(baselineY);
			progressIdx = JsonUtil.clampInt(progressIdx, 0, 10_000_000);
			total = JsonUtil.clampInt(total, 0, 10_000_000);

			// 方案非法：丢弃该记录
			if (TerraformPlan.valueOfSafe(plan) == null)
			{
				LOGGER.warn("NeoSim-TerraformPersistence: bad plan '{}' at {}, drop record", plan, boxPos);
				return null;
			}
			// 矩形与模盒相距过远：判定为篡改数据，丢弃（防远处区块强制加载卡服）
			if (Math.abs(minX - bx) > PROX_MARGIN || Math.abs(maxX - bx) > PROX_MARGIN
					|| Math.abs(minZ - bz) > PROX_MARGIN || Math.abs(maxZ - bz) > PROX_MARGIN
					|| Math.abs(baselineY - by) > PROX_MARGIN)
			{
				LOGGER.warn("NeoSim-TerraformPersistence: rect far from box at ({},{},{}) — drop record",
						bx, by, bz);
				return null;
			}

			return new TerraformRecord(bx, by, bz, plan,
					minX, minZ, maxX, maxZ, baselineY,
					worker, paused, progressIdx, total, state, placer);
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-TerraformPersistence: skip bad record", e);
			return null;
		}
	}
}

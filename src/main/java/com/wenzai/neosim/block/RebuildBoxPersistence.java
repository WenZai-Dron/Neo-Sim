package com.wenzai.neosim.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

// 重建盒任务持久化：每城市 RebuildBox.json
// 记录绑定关系（控制箱 → 蓝图 → 落地几何）与游标/状态；重启后按记录恢复任务。
public class RebuildBoxPersistence
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// 记录与绑定的控制箱坐标相距上限：超出视为篡改数据（防止强制加载远端区块）
	private static final int PROX_MARGIN = 512;

	public record RebuildRecord(
			// 重建盒位置
			int bx, int by, int bz,

			// 绑定的控制箱位置
			int cbx, int cby, int cbz,

			// 蓝图与落地几何（origin = 控制箱记录里的建筑原点）
			String schematicName,
			int ox, int oy, int oz,
			String rotation, String mirror, String facing,

			// 状态 / 展示用游标
			String state,
			int progress, int total,

			String placer)
	{
		public static RebuildRecord of(BlockPos box, BlockPos controlBox, String schematicName,
									   BlockPos origin, String rotation, String mirror, String facing,
									   String placer)
		{
			return new RebuildRecord(box.getX(), box.getY(), box.getZ(),
					controlBox.getX(), controlBox.getY(), controlBox.getZ(),
					schematicName, origin.getX(), origin.getY(), origin.getZ(),
					rotation == null ? "NONE" : rotation,
					mirror == null ? "NONE" : mirror,
					facing,
					"RUNNING", 0, 0, placer);
		}

		public BlockPos boxPos()
		{
			return new BlockPos(bx, by, bz);
		}

		public BlockPos controlBoxPos()
		{
			return new BlockPos(cbx, cby, cbz);
		}

		public BlockPos originPos()
		{
			return new BlockPos(ox, oy, oz);
		}

		public RebuildRecord withState(String s)
		{
			return new RebuildRecord(bx, by, bz, cbx, cby, cbz, schematicName, ox, oy, oz,
					rotation, mirror, facing, s, progress, total, placer);
		}

		public RebuildRecord withCursor(int progress, int total)
		{
			return new RebuildRecord(bx, by, bz, cbx, cby, cbz, schematicName, ox, oy, oz,
					rotation, mirror, facing, state, progress, total, placer);
		}

		public RebuildRecord withGeometry(String rotation, String mirror, String facing)
		{
			return new RebuildRecord(bx, by, bz, cbx, cby, cbz, schematicName, ox, oy, oz,
					rotation == null ? "NONE" : rotation,
					mirror == null ? "NONE" : mirror,
					facing, state, progress, total, placer);
		}

		// 是否已有可信落地几何
		public boolean hasGeometry()
		{
			return facing != null && !facing.isEmpty();
		}
	}

	private static Path getCityDir(ServerLevel level, String cityName)
	{
		Path base = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		boolean dedicated = level.getServer().isDedicatedServer();
		String saveName = dedicated ? null : level.getServer().getWorldData().getLevelName();
		return (saveName == null || saveName.isEmpty())
				? base.resolve(cityName)
				: base.resolve(saveName).resolve(cityName);
	}

	private static Path getFilePath(ServerLevel level, String cityName)
	{
		return getCityDir(level, cityName).resolve("RebuildBox.json");
	}

	public static List<RebuildRecord> load(ServerLevel level, String cityName)
	{
		return readRecords(getFilePath(level, cityName));
	}

	public static void save(ServerLevel level, String cityName, List<RebuildRecord> records)
	{
		writeRecords(getFilePath(level, cityName), records);
	}

	@Nullable
	public static RebuildRecord findRecord(ServerLevel level, String cityName, BlockPos pos)
	{
		for (RebuildRecord r : load(level, cityName))
		{
			if (r.boxPos().equals(pos)) return r;
		}
		return null;
	}

	public static void addOrUpdate(ServerLevel level, String cityName, RebuildRecord record)
	{
		List<RebuildRecord> records = new ArrayList<>(load(level, cityName));
		records.removeIf(r -> r.boxPos().equals(record.boxPos()));
		records.add(record);
		writeRecords(getFilePath(level, cityName), records);
		LOGGER.info("NeoSim-RebuildBoxPersistence: recorded rebuild box {} for '{}'",
				record.boxPos(), cityName);
	}

	public static void updateRecord(ServerLevel level, String cityName, RebuildRecord record)
	{
		addOrUpdate(level, cityName, record);
	}

	// 删除：扫描所有城市目录，找到即移除并返回；未找到返回 null
	@Nullable
	public static RebuildRecord removeAt(ServerLevel level, BlockPos pos)
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
				Path file = dir.resolve("RebuildBox.json");
				if (!Files.exists(file)) continue;
				List<RebuildRecord> records = readRecords(file);
				for (RebuildRecord r : records)
				{
					if (r.boxPos().equals(pos))
					{
						records.remove(r);
						writeRecords(file, records);
						LOGGER.info("NeoSim-RebuildBoxPersistence: removed rebuild box record at {}", pos);
						return r;
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-RebuildBoxPersistence: removeAt failed", e);
		}
		return null;
	}

	private static void writeRecords(Path file, List<RebuildRecord> records)
	{
		JsonArray arr = new JsonArray();
		for (RebuildRecord r : records)
		{
			arr.add(recordToJson(r));
		}
		JsonUtil.write(file, arr);
	}

	private static List<RebuildRecord> readRecords(Path file)
	{
		List<RebuildRecord> records = new ArrayList<>();
		if (!Files.exists(file)) return records;

		JsonArray arr = JsonUtil.readArray(file);
		if (arr == null) return records;
		for (JsonElement e : arr)
		{
			if (!e.isJsonObject()) continue;
			RebuildRecord rec = recordFromJson(e.getAsJsonObject());
			if (rec != null) records.add(rec);
		}
		return records;
	}

	private static JsonObject recordToJson(RebuildRecord r)
	{
		JsonObject obj = new JsonObject();

		JsonObject box = new JsonObject();
		box.addProperty("x", r.bx);
		box.addProperty("y", r.by);
		box.addProperty("z", r.bz);
		obj.add("box", box);

		JsonObject cb = new JsonObject();
		cb.addProperty("x", r.cbx);
		cb.addProperty("y", r.cby);
		cb.addProperty("z", r.cbz);
		obj.add("controlBox", cb);

		obj.addProperty("schematicName", r.schematicName);

		JsonObject origin = new JsonObject();
		origin.addProperty("x", r.ox);
		origin.addProperty("y", r.oy);
		origin.addProperty("z", r.oz);
		obj.add("origin", origin);

		obj.addProperty("rotation", r.rotation != null ? r.rotation : "NONE");
		obj.addProperty("mirror", r.mirror != null ? r.mirror : "NONE");
		if (r.facing != null) obj.addProperty("facing", r.facing);

		obj.addProperty("state", r.state);

		JsonObject progress = new JsonObject();
		progress.addProperty("progress", r.progress);
		progress.addProperty("total", r.total);
		obj.add("progress", progress);

		if (r.placer != null && !r.placer.isEmpty()) obj.addProperty("placer", r.placer);
		return obj;
	}

	@Nullable
	private static RebuildRecord recordFromJson(JsonObject obj)
	{
		try
		{
			JsonObject box = JsonUtil.getObject(obj, "box");
			if (box == null) return null;
			int bx = JsonUtil.clampX(JsonUtil.getInt(box, "x", 0));
			int by = JsonUtil.clampY(JsonUtil.getInt(box, "y", 0));
			int bz = JsonUtil.clampX(JsonUtil.getInt(box, "z", 0));

			JsonObject cb = JsonUtil.getObject(obj, "controlBox");
			int cbx = JsonUtil.clampX(cb != null ? JsonUtil.getInt(cb, "x", 0) : 0);
			int cby = JsonUtil.clampY(cb != null ? JsonUtil.getInt(cb, "y", 0) : 0);
			int cbz = JsonUtil.clampX(cb != null ? JsonUtil.getInt(cb, "z", 0) : 0);

			String schematicName = JsonUtil.getString(obj, "schematicName", "");
			if (schematicName.isEmpty()) return null;

			JsonObject origin = JsonUtil.getObject(obj, "origin");
			int ox = JsonUtil.clampX(origin != null ? JsonUtil.getInt(origin, "x", 0) : 0);
			int oy = JsonUtil.clampY(origin != null ? JsonUtil.getInt(origin, "y", 0) : 0);
			int oz = JsonUtil.clampX(origin != null ? JsonUtil.getInt(origin, "z", 0) : 0);

			String rotation = JsonUtil.getString(obj, "rotation", "NONE");
			String mirror = JsonUtil.getString(obj, "mirror", "NONE");
			String facing = JsonUtil.getString(obj, "facing", null);

			String state = JsonUtil.getString(obj, "state", "RUNNING");

			JsonObject progress = JsonUtil.getObject(obj, "progress");
			int progressIdx = progress != null ? JsonUtil.getInt(progress, "progress", 0) : 0;
			int total = progress != null ? JsonUtil.getInt(progress, "total", 0) : 0;
			progressIdx = JsonUtil.clampInt(progressIdx, 0, 10_000_000);
			total = JsonUtil.clampInt(total, 0, 10_000_000);

			String placer = JsonUtil.getString(obj, "placer", null);

			// 防篡改：绑定控制箱不得离重建盒过远
			if (Math.abs(cbx - bx) > PROX_MARGIN || Math.abs(cbz - bz) > PROX_MARGIN
					|| Math.abs(cby - by) > PROX_MARGIN)
			{
				LOGGER.warn("NeoSim-RebuildBoxPersistence: control box far from box at ({},{},{}) — drop record",
						bx, by, bz);
				return null;
			}

			return new RebuildRecord(bx, by, bz, cbx, cby, cbz, schematicName,
					ox, oy, oz, rotation, mirror, facing, state, progressIdx, total, placer);
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-RebuildBoxPersistence: skip bad record", e);
			return null;
		}
	}
}

package com.wenzai.neosim.building;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.annotation.Nullable;

// 控制箱记录JSON持久化：按城市存储已放置控制箱的信息
public class ControlBoxPersistence
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// 居民：姓名 + 入住的生活点位置
	public record Resident(String name, int x, int y, int z)
	{
		public BlockPos pos()
		{
			return new BlockPos(x, y, z);
		}
	}

	// 生活点（按列 x/z）是否已被居民占用：兼容旧存档中居民记录了抬升后坐标（y 不同）的情况
	public static boolean isLivingPointOccupied(ControlBoxRecord rec, BlockPos lp)
	{
		for (Resident r : rec.residents())
		{
			if (r.x() == lp.getX() && r.z() == lp.getZ()) return true;
		}
		return false;
	}

	// 控制箱记录：位置<->建筑信息（含生活点与居民）
	public record ControlBoxRecord(int x, int y, int z, String schematicName,
								   int originX, int originY, int originZ,
								   String placerName, String author,
								   List<BlockPos> livingPoints, List<Resident> residents,
								   double rent)
	{
		public static ControlBoxRecord of(BlockPos box, BlockPos origin, String schematicName,
										  String placerName, String author, List<BlockPos> livingPoints)
		{
			return new ControlBoxRecord(box.getX(), box.getY(), box.getZ(), schematicName,
					origin.getX(), origin.getY(), origin.getZ(), placerName, author,
					livingPoints, new ArrayList<>(), 0.0);
		}

		// 定价后返回新记录
		public ControlBoxRecord withRent(double rent)
		{
			return new ControlBoxRecord(x, y, z, schematicName, originX, originY, originZ,
					placerName, author, livingPoints, residents, rent);
		}

		public BlockPos boxPos()
		{
			return new BlockPos(x, y, z);
		}

		public BlockPos originPos()
		{
			return new BlockPos(originX, originY, originZ);
		}
	}

	// 记录文件路径
	public static Path getCityPath(@Nullable String saveName, String cityName)
	{
		Path base = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		return (saveName == null || saveName.isEmpty())
				? base.resolve(cityName).resolve("ControlBox.json")
				: base.resolve(saveName).resolve(cityName).resolve("ControlBox.json");
	}

	private static Path getCityPath(ServerLevel level, String cityName)
	{
		boolean dedicated = level.getServer().isDedicatedServer();
		String saveName = dedicated ? null : level.getServer().getWorldData().getLevelName();
		return getCityPath(saveName, cityName);
	}

	// 加载某城市的控制箱记录
	public static List<ControlBoxRecord> load(ServerLevel level, String cityName)
	{
		return readRecords(getCityPath(level, cityName));
	}

	// 保存某城市的全部控制箱记录
	public static void save(ServerLevel level, String cityName, List<ControlBoxRecord> records)
	{
		writeRecords(getCityPath(level, cityName), records);
	}

	// 按控制箱位置查找记录
	@Nullable
	public static ControlBoxRecord findRecord(ServerLevel level, String cityName, BlockPos pos)
	{
		for (ControlBoxRecord r : load(level, cityName))
		{
			if (r.boxPos().equals(pos)) return r;
		}
		return null;
	}

	// 更新：按控制箱位置替换记录（居民入住/退房后落盘）
	public static void updateRecord(ServerLevel level, String cityName, ControlBoxRecord record)
	{
		List<ControlBoxRecord> records = new ArrayList<>(load(level, cityName));
		for (int i = 0; i < records.size(); i++)
		{
			if (records.get(i).boxPos().equals(record.boxPos()))
			{
				records.set(i, record);
				save(level, cityName, records);
				return;
			}
		}
		records.add(record);
		save(level, cityName, records);
	}

	// 增改：同位置已存在则覆盖，否则追加
	public static void addOrUpdate(ServerLevel level, String cityName, ControlBoxRecord record)
	{
		List<ControlBoxRecord> records = new ArrayList<>(load(level, cityName));
		records.removeIf(r -> r.boxPos().equals(record.boxPos()));
		records.add(record);
		save(level, cityName, records);
		LOGGER.debug("NeoSim-ControlBoxPersistence: recorded control box {} for '{}'",
				record.boxPos(), record.schematicName());
	}

	// 删除：按控制箱位置扫描所有城市目录，找到即移除并返回；未找到返回null
	@Nullable
	public static ControlBoxRecord removeAt(ServerLevel level, BlockPos pos)
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
				Path file = dir.resolve("ControlBox.json");
				if (!Files.exists(file)) continue;
				List<ControlBoxRecord> records = readRecords(file);
				for (ControlBoxRecord r : records)
				{
					if (r.boxPos().equals(pos))
					{
						records.remove(r);
						writeRecords(file, records);
						LOGGER.info("NeoSim-ControlBoxPersistence: removed control box record at {}", pos);
						return r;
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-ControlBoxPersistence: removeAt failed", e);
		}
		return null;
	}

	// 客户端查找
	@Nullable
	public static ControlBoxRecord findRecord(@Nullable String saveName, String cityName, BlockPos pos)
	{
		Path file = getCityPath(saveName, cityName);
		if (!Files.exists(file)) return null;
		for (ControlBoxRecord r : readRecords(file))
		{
			if (r.boxPos().equals(pos)) return r;
		}
		return null;
	}

	private static void writeRecords(Path file, List<ControlBoxRecord> records)
	{
		try
		{
			Files.createDirectories(file.getParent());
			JsonArray arr = new JsonArray();
			for (ControlBoxRecord r : records)
			{
				arr.add(recordToJson(r));
			}
			try (Writer w = Files.newBufferedWriter(file))
			{
				GSON.toJson(arr, w);
			}
			LOGGER.debug("NeoSim-ControlBoxPersistence: saved {} records to {}", records.size(), file);
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-ControlBoxPersistence: save failed", e);
		}
	}

	private static List<ControlBoxRecord> readRecords(Path file)
	{
		List<ControlBoxRecord> records = new ArrayList<>();
		if (!Files.exists(file)) return records;

		try (Reader r = Files.newBufferedReader(file))
		{
			JsonArray arr = GSON.fromJson(r, JsonArray.class);
			if (arr == null) return records;
			for (JsonElement e : arr)
			{
				ControlBoxRecord rec = recordFromJson(e.getAsJsonObject());
				if (rec != null) records.add(rec);
			}
		}
		catch (Exception ex)
		{
			LOGGER.error("NeoSim-ControlBoxPersistence: load failed", ex);
		}
		return records;
	}

	private static JsonObject recordToJson(ControlBoxRecord r)
	{
		JsonObject obj = new JsonObject();
		obj.addProperty("x", r.x());
		obj.addProperty("y", r.y());
		obj.addProperty("z", r.z());
		obj.addProperty("schematicName", r.schematicName());
		obj.addProperty("originX", r.originX());
		obj.addProperty("originY", r.originY());
		obj.addProperty("originZ", r.originZ());
		obj.addProperty("placerName", r.placerName());
		obj.addProperty("author", r.author());

		// 生活点
		JsonArray lps = new JsonArray();
		for (BlockPos p : r.livingPoints())
		{
			JsonObject lp = new JsonObject();
			lp.addProperty("x", p.getX());
			lp.addProperty("y", p.getY());
			lp.addProperty("z", p.getZ());
			lps.add(lp);
		}
		obj.add("livingPoints", lps);

		// 居民（姓名 + 入住位置）
		JsonArray res = new JsonArray();
		for (Resident rd : r.residents())
		{
			JsonObject o = new JsonObject();
			o.addProperty("name", rd.name());
			o.addProperty("x", rd.x());
			o.addProperty("y", rd.y());
			o.addProperty("z", rd.z());
			res.add(o);
		}
		obj.add("residents", res);

		// 租金
		obj.addProperty("rent", r.rent());
		return obj;
	}

	private static ControlBoxRecord recordFromJson(JsonObject obj)
	{
		try
		{
			// 生活点（兼容旧记录：缺失则为空）
			List<BlockPos> livingPoints = new ArrayList<>();
			if (obj.has("livingPoints"))
			{
				for (JsonElement e : obj.getAsJsonArray("livingPoints"))
				{
					JsonObject lp = e.getAsJsonObject();
					livingPoints.add(new BlockPos(
							lp.get("x").getAsInt(), lp.get("y").getAsInt(), lp.get("z").getAsInt()));
				}
			}

			// 居民
			List<Resident> residents = new ArrayList<>();
			if (obj.has("residents"))
			{
				for (JsonElement e : obj.getAsJsonArray("residents"))
				{
					JsonObject rd = e.getAsJsonObject();
					residents.add(new Resident(
							rd.get("name").getAsString(),
							rd.get("x").getAsInt(), rd.get("y").getAsInt(), rd.get("z").getAsInt()));
				}
			}

			// 防删改：坐标钳制
			return new ControlBoxRecord(
					JsonUtil.clampX(obj.get("x").getAsInt()),
					JsonUtil.clampY(obj.get("y").getAsInt()),
					JsonUtil.clampX(obj.get("z").getAsInt()),
					obj.get("schematicName").getAsString(),
					JsonUtil.clampX(obj.get("originX").getAsInt()),
					JsonUtil.clampY(obj.get("originY").getAsInt()),
					JsonUtil.clampX(obj.get("originZ").getAsInt()),
					obj.has("placerName") && !obj.get("placerName").isJsonNull()
							? obj.get("placerName").getAsString() : null,
					obj.has("author") && !obj.get("author").isJsonNull()
							? obj.get("author").getAsString() : null,
					livingPoints, residents,
					obj.has("rent") ? obj.get("rent").getAsDouble() : 0.0);
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-ControlBoxPersistence: skip bad record", e);
			return null;
		}
	}
}

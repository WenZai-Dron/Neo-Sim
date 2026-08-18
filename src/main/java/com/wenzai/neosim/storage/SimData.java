package com.wenzai.neosim.storage;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

// 共享数据模型，服务端ModSavedData和客户端ClientDataHolder共用此结构
public record SimData(byte mode, short population, int dayOfWeek, int day, double credit)
{

	public static final SimData DEFAULT = new SimData((byte)0, (short)0, 0, 1, Config.INITIAL_CREDIT.get());

	// 文件序列化
	public static SimData fromJson(JsonObject json)
	{
		return new SimData(
				json.get("mode").getAsByte(),
				json.get("population").getAsShort(),
				json.get("dayOfWeek").getAsInt(),
				json.get("day").getAsInt(),
				json.get("credit").getAsDouble()
		);
	}

	public JsonObject toJson(JsonObject json)
	{
		json.addProperty("mode", mode);
		json.addProperty("population", population);
		json.addProperty("dayOfWeek", dayOfWeek);
		json.addProperty("day", day);
		json.addProperty("credit", credit);
		return json;
	}

	// 返回新实例
	public SimData withMode(byte mode) { return new SimData(mode, population, dayOfWeek, day, credit); }
	public SimData withPopulation(short population) { return new SimData(mode, population, dayOfWeek, day, credit); }
	public SimData withDayOfWeek(int dayOfWeek) { return new SimData(mode, population, dayOfWeek, day, credit); }
	public SimData withDay(int day) { return new SimData(mode, population, dayOfWeek, day, credit); }
	public SimData withCredit(double credit) { return new SimData(mode, population, dayOfWeek, day, Math.round(credit * 100.0) / 100.0); }

	// 每城市运行时数据
	public record CityData(short population, int day, double credit)
	{
		private static final Logger LOGGER = LogUtils.getLogger();

		public static final CityData DEFAULT = new CityData((short) 0, 1, Config.INITIAL_CREDIT.get());

		public CityData withPopulation(short population) { return new CityData(population, day, credit); }
		public CityData withDay(int day) { return new CityData(population, day, credit); }
		public CityData withCredit(double credit)
		{
			return new CityData(population, day, Math.round(credit * 100.0) / 100.0);
		}

		// 内存缓存条目：数据 + 脏标记（写入先落内存，周期合并落盘）
		private static final class CachedCity
		{
			final Path file;
			CityData data;
			boolean dirty;
			CachedCity(Path file, CityData data, boolean dirty)
			{
				this.file = file;
				this.data = data;
				this.dirty = dirty;
			}
		}

		// 城市数据内存缓存（键=解析后的文件路径，天然区分存档/城市）：
		// 消除每方块/每单的"读盘+写盘+再读盘"，写入走脏标记 + 合并窗口
		private static final java.util.Map<Path, CachedCity> CACHE = new java.util.HashMap<>();

		private static Path resolvePath(ServerLevel level, String cityName)
		{
			Path cityDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
			if (!level.getServer().isDedicatedServer())
			{
				cityDir = cityDir.resolve(level.getServer().getWorldData().getLevelName());
			}
			return cityDir.resolve(cityName).resolve("data.json");
		}

		// 取缓存条目：首次访问读盘（损坏则备份重建默认值）
		private static CachedCity cached(Path file)
		{
			return CACHE.computeIfAbsent(file, f ->
			{
				CityData loaded = DEFAULT;
				boolean dirty = false;
				if (Files.exists(f))
				{
					JsonObject json = JsonUtil.readObject(f);
					if (json == null)
					{
						// 内容被篡改/清空：备份.bak后重建默认值，游戏自动恢复
						JsonUtil.backupCorrupted(f);
						loaded = DEFAULT;
						dirty = true;
						LOGGER.warn("NeoSim-CityData: corrupted, backed up and rebuilt: {}", f);
					}
					else
					{
						loaded = fromJson(json);
					}
				}
				return new CachedCity(f, loaded, dirty);
			});
		}

		public static CityData read(ServerLevel level, String cityName)
		{
			return cached(resolvePath(level, cityName)).data;
		}

		public static void write(ServerLevel level, String cityName, CityData data)
		{
			CachedCity c = cached(resolvePath(level, cityName));
			c.data = data;
			c.dirty = true;
		}

		// 周期 flush：所有脏条目一次写盘（合并窗口，每 100 tick 由 NeoSim 调用）
		public static void flushDirty()
		{
			for (CachedCity c : CACHE.values())
			{
				if (c.dirty)
				{
					writeFile(c.file, c.data);
					c.dirty = false;
				}
			}
		}

		// 服务端停止：强制 flush 后清空缓存，防止跨存档残留
		public static void flushAndClear()
		{
			flushDirty();
			CACHE.clear();
		}

		private static void writeFile(Path file, CityData data)
		{
			JsonObject json = new JsonObject();
			data.toJson(json);
			JsonUtil.write(file, json);
			LOGGER.debug("NeoSim-CityData: saved {}", file);
		}

		public static CityData fromJson(JsonObject json)
		{
			short population = JsonUtil.getShort(json, "population", (short) 0);
			int day = JsonUtil.getInt(json, "day", 1);
			double credit = JsonUtil.getDouble(json, "credit", Config.INITIAL_CREDIT.get());
			return new CityData(population, day, credit);
		}

		public void toJson(JsonObject json)
		{
			json.addProperty("population", population);
			json.addProperty("day", day);
			json.addProperty("credit", credit);
		}
	}
}

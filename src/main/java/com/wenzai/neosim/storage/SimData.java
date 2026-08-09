// 共享数据模型，服务端ModSavedData和客户端ClientDataHolder共用此结构

package com.wenzai.neosim.storage;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.util.SafeJson;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

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

        private static Path resolvePath(ServerLevel level, String cityName)
        {
            Path cityDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
            if (!level.getServer().isDedicatedServer())
            {
                cityDir = cityDir.resolve(level.getServer().getWorldData().getLevelName());
            }
            return cityDir.resolve(cityName).resolve("data.json");
        }

        public static CityData read(ServerLevel level, String cityName)
        {
            Path file = resolvePath(level, cityName);
            if (!Files.exists(file)) return DEFAULT;
            JsonObject json = SafeJson.readObject(file);
            if (json == null)
            {
                // 内容被篡改/清空：备份.bak后重建默认值，游戏自动恢复
                SafeJson.backupCorrupted(file);
                write(level, cityName, DEFAULT);
                LOGGER.warn("NeoSim-CityData: corrupted, backed up and rebuilt: {}", file);
                return DEFAULT;
            }
            return fromJson(json);
        }

        public static void write(ServerLevel level, String cityName, CityData data)
        {
            Path file = resolvePath(level, cityName);
            JsonObject json = new JsonObject();
            data.toJson(json);
            SafeJson.write(file, json);
            LOGGER.info("NeoSim-CityData: saved {}", file);
        }

        public static CityData fromJson(JsonObject json)
        {
            short population = SafeJson.getShort(json, "population", (short) 0);
            int day = SafeJson.getInt(json, "day", 1);
            double credit = SafeJson.getDouble(json, "credit", Config.INITIAL_CREDIT.get());
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

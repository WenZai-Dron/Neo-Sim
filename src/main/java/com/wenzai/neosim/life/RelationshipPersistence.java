// 关系文件读写

package com.wenzai.neosim.life;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.util.SafeJson;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RelationshipPersistence
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private RelationshipPersistence() {}

    private static Path relationshipsDir(ServerLevel level, String cityName)
    {
        if (level.getServer() == null || cityName == null || cityName.isEmpty()) return null;
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path cityDir = level.getServer().isDedicatedServer()
                ? gameDir.resolve("NeoSim").resolve("data").resolve(cityName)
                : gameDir.resolve("NeoSim").resolve("data")
                        .resolve(level.getServer().getWorldData().getLevelName()).resolve(cityName);
        return cityDir.resolve("relationships");
    }

    // 读取一对居民的关系
    public static Relationship.RelationshipData loadPair(ServerLevel level, String city, String a, String b)
    {
        Path dir = relationshipsDir(level, city);
        if (dir == null) return null;
        Path file = dir.resolve(fileName(a, b));
        if (!Files.exists(file)) return null;

        JsonObject json = SafeJson.readObject(file);
        if (json == null)
        {
            // 内容被篡改：备份后跳过，该关系视为不存在
            SafeJson.backupCorrupted(file);
            LOGGER.warn("NeoSim-RelationshipPersistence: corrupted file skipped, {}", file);
            return null;
        }
        return parsePair(json, a, b);
    }

    // 读取某城市全部关系（婚姻扫描）
    public static List<Relationship.RelationshipData> loadAll(ServerLevel level, String city)
    {
        List<Relationship.RelationshipData> all = new ArrayList<>();
        Path dir = relationshipsDir(level, city);
        if (dir == null || !Files.isDirectory(dir)) return all;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
        {
            for (Path file : stream)
            {
                if (!file.getFileName().toString().endsWith(".json")) continue;
                JsonObject json = SafeJson.readObject(file);
                if (json == null)
                {
                    SafeJson.backupCorrupted(file);
                    LOGGER.warn("NeoSim-RelationshipPersistence: corrupted file skipped, {}", file);
                    continue;
                }
                try
                {
                    Relationship.RelationshipData rel = parsePair(json, "", "");
                    if (rel != null) all.add(rel);
                }
                catch (Exception e)
                {
                    LOGGER.error("NeoSim-RelationshipPersistence: loadAll failed, file={}, error={}",
                            file, e.getMessage(), e);
                }
            }
        }
        catch (IOException e)
        {
            LOGGER.error("NeoSim-RelationshipPersistence: loadAll failed, error={}", e.getMessage(), e);
        }
        return all;
    }

    // 婚姻改姓
    public static void renameAllFor(ServerLevel level, String city, String oldName, String newName)
    {
        Path dir = relationshipsDir(level, city);
        if (dir == null || !Files.isDirectory(dir) || oldName == null || oldName.isEmpty()) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
        {
            for (Path file : stream)
            {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".json")) continue;
                boolean references = fileName.startsWith(oldName + "_") || fileName.endsWith("_" + oldName + ".json");
                if (!references) continue;

                JsonObject json = SafeJson.readObject(file);
                if (json == null)
                {
                    SafeJson.backupCorrupted(file);
                    LOGGER.warn("NeoSim-RelationshipPersistence: corrupted file skipped, {}", file);
                    continue;
                }
                try
                {
                    String f1 = SafeJson.getString(json, "folk1", "");
                    String f2 = SafeJson.getString(json, "folk2", "");
                    if (f1.isEmpty() || f2.isEmpty()) continue;
                    if (f1.equals(oldName)) f1 = newName;
                    if (f2.equals(oldName)) f2 = newName;
                    Files.deleteIfExists(file);
                    save(level, city, parsePair(json, f1, f2));
                }
                catch (Exception e)
                {
                    LOGGER.error("NeoSim-RelationshipPersistence: renameAllFor failed, file={}, error={}",
                            file, e.getMessage(), e);
                }
            }
        }
        catch (IOException e)
        {
            LOGGER.error("NeoSim-RelationshipPersistence: renameAllFor failed, error={}", e.getMessage(), e);
        }
    }

    // 解析一对关系
    private static Relationship.RelationshipData parsePair(JsonObject json, String fallback1, String fallback2)
    {
        String f1 = SafeJson.getString(json, "folk1", fallback1);
        String f2 = SafeJson.getString(json, "folk2", fallback2);
        if (f1.isEmpty() || f2.isEmpty()) return null;
        int sub = SafeJson.getInt(json, "subLevel", 0);
        return new Relationship.RelationshipData(f1, f2, parseLevel(json), sub);
    }

    // 写入一对关系（改动即存）
    public static void save(ServerLevel level, String city, Relationship.RelationshipData rel)
    {
        Path dir = relationshipsDir(level, city);
        if (dir == null || rel == null) return;
        JsonObject json = new JsonObject();
        json.addProperty("folk1", rel.folk1());
        json.addProperty("folk2", rel.folk2());
        json.addProperty("level", rel.level().name());
        json.addProperty("subLevel", rel.subLevel());
        SafeJson.write(dir.resolve(fileName(rel.folk1(), rel.folk2())), json);
    }

    public static void removePair(ServerLevel level, String city, String a, String b)
    {
        Path dir = relationshipsDir(level, city);
        if (dir == null) return;
        try
        {
            Files.deleteIfExists(dir.resolve(fileName(a, b)));
        }
        catch (IOException e)
        {
            LOGGER.error("NeoSim-RelationshipPersistence: remove failed, error={}", e.getMessage(), e);
        }
    }

    // 删除含该居民名的全部关系文件（死亡清理）
    public static void removeAllFor(ServerLevel level, String city, String name)
    {
        Path dir = relationshipsDir(level, city);
        if (dir == null || !Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
        {
            for (Path file : stream)
            {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".json")) continue;
                if (fileName.startsWith(name + "_") || fileName.endsWith("_" + name + ".json"))
                {
                    Files.deleteIfExists(file);
                }
            }
        }
        catch (IOException e)
        {
            LOGGER.error("NeoSim-RelationshipPersistence: removeAllFor failed, error={}", e.getMessage(), e);
        }
    }

    // 文件名
    private static String fileName(String a, String b)
    {
        return (a.compareTo(b) <= 0 ? a : b) + "_" + (a.compareTo(b) <= 0 ? b : a) + ".json";
    }

    private static Relationship.RelationshipLevel parseLevel(JsonObject json)
    {
        if (!json.has("level")) return Relationship.RelationshipLevel.AQUAINTANCE;
        try
        {
            return Relationship.RelationshipLevel.valueOf(json.get("level").getAsString());
        }
        catch (IllegalArgumentException e)
        {
            return Relationship.RelationshipLevel.AQUAINTANCE;
        }
    }
}

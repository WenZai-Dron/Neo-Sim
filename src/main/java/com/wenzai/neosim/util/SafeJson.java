package com.wenzai.neosim.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class SafeJson
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SafeJson() {}

    // 读取文件（不抛异常）
    public static JsonObject readObject(Path file)
    {
        if (file == null || !Files.exists(file)) return null;
        try (Reader reader = Files.newBufferedReader(file))
        {
            JsonElement el = GSON.fromJson(reader, JsonElement.class);
            if (el == null || el.isJsonNull() || !el.isJsonObject()) return null;
            return el.getAsJsonObject();
        }
        catch (Exception e)
        {
            LOGGER.error("NeoSim-SafeJson: read object fail, {}", file, e);
            return null;
        }
    }

    // 读取文件数组
    public static JsonArray readArray(Path file)
    {
        if (file == null || !Files.exists(file)) return null;
        try (Reader reader = Files.newBufferedReader(file))
        {
            JsonElement el = GSON.fromJson(reader, JsonElement.class);
            if (el == null || el.isJsonNull() || !el.isJsonArray()) return null;
            return el.getAsJsonArray();
        }
        catch (Exception e)
        {
            LOGGER.error("NeoSim-SafeJson: read array fail, {}", file, e);
            return null;
        }
    }

    // 原子写入：先写.tmp再move覆盖，避免写入中途崩溃留下半截文件
    public static void write(Path file, JsonElement json)
    {
        if (file == null || json == null) return;
        try
        {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp))
            {
                GSON.toJson(json, w);
            }
            try
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException e)
            {
                // move失败（如目标文件被占用）时
                try (Writer w = Files.newBufferedWriter(file))
                {
                    GSON.toJson(json, w);
                }
                Files.deleteIfExists(tmp);
            }
        }
        catch (Exception e)
        {
            LOGGER.error("NeoSim-SafeJson: write fail, {}", file, e);
        }
    }

    // 损坏文件备份为.bak（覆盖旧备份），供手动恢复
    public static void backupCorrupted(Path file)
    {
        if (file == null || !Files.exists(file)) return;
        try
        {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("NeoSim-SafeJson: corrupted file backed up to {}.bak", file);
        }
        catch (IOException e)
        {
            LOGGER.error("NeoSim-SafeJson: backup fail, {}", file, e);
        }
    }

    // 类型安全取值
    public static String getString(JsonObject o, String key, String def)
    {
        try
        {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
            return o.get(key).getAsString();
        }
        catch (Exception e) { return def; }
    }

    public static byte getByte(JsonObject o, String key, byte def)
    {
        try
        {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
            return o.get(key).getAsByte();
        }
        catch (Exception e) { return def; }
    }

    public static short getShort(JsonObject o, String key, short def)
    {
        try
        {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
            return o.get(key).getAsShort();
        }
        catch (Exception e) { return def; }
    }

    public static int getInt(JsonObject o, String key, int def)
    {
        try
        {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
            return o.get(key).getAsInt();
        }
        catch (Exception e) { return def; }
    }

    public static long getLong(JsonObject o, String key, long def)
    {
        try
        {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
            return o.get(key).getAsLong();
        }
        catch (Exception e) { return def; }
    }

    public static float getFloat(JsonObject o, String key, float def)
    {
        try
        {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
            return o.get(key).getAsFloat();
        }
        catch (Exception e) { return def; }
    }

    public static double getDouble(JsonObject o, String key, double def)
    {
        try
        {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
            return o.get(key).getAsDouble();
        }
        catch (Exception e) { return def; }
    }

    public static boolean getBoolean(JsonObject o, String key, boolean def)
    {
        try
        {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
            return o.get(key).getAsBoolean();
        }
        catch (Exception e) { return def; }
    }

    // 取子对象
    public static JsonObject getObject(JsonObject o, String key)
    {
        try
        {
            if (o == null || !o.has(key) || !o.get(key).isJsonObject()) return null;
            return o.getAsJsonObject(key);
        }
        catch (Exception e) { return null; }
    }

    // 取子数组
    public static JsonArray getArray(JsonObject o, String key)
    {
        try
        {
            if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return new JsonArray();
            return o.getAsJsonArray(key);
        }
        catch (Exception e) { return new JsonArray(); }
    }
}

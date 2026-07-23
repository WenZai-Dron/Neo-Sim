package com.wenzai.neosim.npc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class npcData
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private npcData() {}

    // 客户端路径
    public static void save(Entity entity, String cityName, String saveName)
    {
        String npcName = entity.getNpcName();
        if (npcName.isEmpty())
        {
            LOGGER.warn("npcData.save: NPC name is empty. UUID={}", entity.getUUID());
            return;
        }

        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcDir = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName).resolve("npc");

        writeNpcJson(npcDir, npcName, entity);
    }

    public static JsonObject load(String npcName, String cityName, String saveName)
    {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcFile = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName).resolve("npc").resolve(npcName + ".json");

        return readNpcJson(npcFile);
    }

    // 服务端路径
    public static void save(Entity entity, String cityName)
    {
        String npcName = entity.getNpcName();
        if (npcName.isEmpty())
        {
            LOGGER.warn("npcData.save: NPC name is empty. UUID={}", entity.getUUID());
            return;
        }

        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcDir = gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc");

        writeNpcJson(npcDir, npcName, entity);
    }

    public static JsonObject load(String npcName, String cityName)
    {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcFile = gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc").resolve(npcName + ".json");

        return readNpcJson(npcFile);
    }

    // 列出某城市所有已保存的NPC
    public static List<String> listNpcNames(String cityName, String saveName)
    {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcDir = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName).resolve("npc");
        return listNpcFiles(npcDir);
    }

    public static List<String> listNpcNames(String cityName)
    {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcDir = gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc");
        return listNpcFiles(npcDir);
    }

    // 死亡时删除文件
    public static void delete(String npcName, String cityName, String saveName)
    {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcFile = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName).resolve("npc").resolve(npcName + ".json");
        deleteNpcJson(npcFile);
    }

    public static void delete(String npcName, String cityName)
    {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcFile = gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc").resolve(npcName + ".json");
        deleteNpcJson(npcFile);
    }

    private static void deleteNpcJson(Path npcFile)
    {
        try
        {
            if (Files.deleteIfExists(npcFile))
            {
                LOGGER.info("npcData.delete: Succeed, {}", npcFile.toAbsolutePath());
            }
            else
            {
                LOGGER.warn("npcData.delete: File not found, {}", npcFile.toAbsolutePath());
            }
        }
        catch (IOException e)
        {
            LOGGER.error("npcData.delete: Fail, path={}, error={}", npcFile.toAbsolutePath(), e.getMessage(), e);
        }
    }

    private static List<String> listNpcFiles(Path npcDir)
    {
        List<String> names = new ArrayList<>();
        if (!Files.exists(npcDir)) return names;

        try (Stream<Path> stream = Files.list(npcDir))
        {
            stream.filter(Files::isRegularFile)
                  .map(Path::getFileName)
                  .map(Path::toString)
                  .filter(name -> name.endsWith(".json"))
                  .map(name -> name.substring(0, name.length() - 5))
                  .forEach(names::add);
        }
        catch (IOException e)
        {
            LOGGER.error("npcData.listNpcFiles: Fail, path={}, error={}", npcDir.toAbsolutePath(), e.getMessage(), e);
        }
        return names;
    }

    // 写入
    private static void writeNpcJson(Path npcDir, String npcName, Entity entity)
    {
        Path npcFile = npcDir.resolve(npcName + ".json");

        try
        {
            Files.createDirectories(npcDir);

            try (Writer writer = Files.newBufferedWriter(npcFile))
            {
                JsonObject json = new JsonObject();

                // 姓名
                Name name = Name.of(entity.getPersistentData());
                json.addProperty("name", name.get());
                json.addProperty("surname", name.getSurname());
                json.addProperty("givenName", name.getGivenName());

                // 性别
                json.addProperty("sex", name.getSex());

                // 皮肤
                json.addProperty("skin", entity.getSkin());

                // UUID
                json.addProperty("uuid", entity.getUUID().toString());

                // 位置
                JsonObject pos = new JsonObject();
                pos.addProperty("x", entity.getX());
                pos.addProperty("y", entity.getY());
                pos.addProperty("z", entity.getZ());
                json.add("position", pos);

                // 朝向
                json.addProperty("yaw", entity.getYRot());
                json.addProperty("pitch", entity.getXRot());

                // 生命值
                json.addProperty("health", entity.getHealth());
                json.addProperty("maxHealth", entity.getMaxHealth());

                // 年龄
                json.addProperty("age", entity.getAge());

                GSON.toJson(json, writer);
                LOGGER.info("npcData.save: Succeed, {}", npcFile.toAbsolutePath());
            }
        }
        catch (IOException e)
        {
            LOGGER.error("npcData.save: Fail, path={}, error={}", npcFile.toAbsolutePath(), e.getMessage(), e);
        }
    }

    // 读取
    private static JsonObject readNpcJson(Path npcFile)
    {
        if (!Files.exists(npcFile))
        {
            LOGGER.warn("npcData.load: File not found, {}", npcFile.toAbsolutePath());
            return null;
        }

        try (Reader reader = Files.newBufferedReader(npcFile))
        {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            LOGGER.info("npcData.load: Succeed, {}", npcFile.toAbsolutePath());
            return json;
        }
        catch (IOException e)
        {
            LOGGER.error("npcData.load: Fail, path={}, error={}", npcFile.toAbsolutePath(), e.getMessage(), e);
            return null;
        }
    }
}

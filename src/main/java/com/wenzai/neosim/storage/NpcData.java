package com.wenzai.neosim.storage;

import com.wenzai.neosim.npc.Entity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.util.SafeJson;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class NpcData
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private NpcData() {}

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

    // 按服务端环境解析加载单个NPC的文件
    public static JsonObject load(ServerLevel level, String cityName, String npcName)
    {
        if (npcName.isEmpty() || cityName.isEmpty() || level.getServer() == null) return null;
        if (level.getServer().isDedicatedServer())
        {
            return load(npcName, cityName);
        }
        else
        {
            String saveName = level.getServer().getWorldData().getLevelName();
            return load(npcName, cityName, saveName);
        }
    }

    // 直接改写某NPC文件的族谱字段：死亡摘除时处理未加载亲戚
    public static void patchGenealogy(ServerLevel level, String cityName, String npcName,
                                      List<String> parents, List<String> children)
    {
        if (npcName.isEmpty() || cityName.isEmpty() || level.getServer() == null) return;

        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcDir = level.getServer().isDedicatedServer()
                ? gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc")
                : gameDir.resolve("NeoSim").resolve("data")
                        .resolve(level.getServer().getWorldData().getLevelName())
                        .resolve(cityName).resolve("npc");
        Path npcFile = npcDir.resolve(npcName + ".json");
        if (!Files.exists(npcFile)) return;

        JsonObject json = SafeJson.readObject(npcFile);
        if (json == null)
        {
            // 内容被篡改：备份后跳过（该NPC数据视为无效）
            SafeJson.backupCorrupted(npcFile);
            LOGGER.warn("npcData.patchGenealogy: corrupted file skipped, {}", npcFile.toAbsolutePath());
            return;
        }

        JsonArray parentsArr = new JsonArray();
        if (parents != null)
        {
            for (String p : parents)
            {
                if (p != null && !p.isEmpty()) parentsArr.add(p);
            }
        }
        if (parentsArr.size() > 0) json.add("parents", parentsArr);
        else json.remove("parents");

        JsonArray childrenArr = new JsonArray();
        if (children != null)
        {
            for (String c : children)
            {
                if (c != null && !c.isEmpty()) childrenArr.add(c);
            }
        }
        if (childrenArr.size() > 0) json.add("children", childrenArr);
        else json.remove("children");

        SafeJson.write(npcFile, json);
        LOGGER.info("npcData.patchGenealogy: Succeed, {}", npcFile.toAbsolutePath());
    }

    // 直接改写某NPC文件的伴侣字段（伴侣死亡时清理未加载的一方）
    public static void patchPartner(ServerLevel level, String cityName, String npcName, String partner)
    {
        if (npcName.isEmpty() || cityName.isEmpty() || level.getServer() == null) return;

        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcDir = level.getServer().isDedicatedServer()
                ? gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc")
                : gameDir.resolve("NeoSim").resolve("data")
                        .resolve(level.getServer().getWorldData().getLevelName())
                        .resolve(cityName).resolve("npc");
        Path npcFile = npcDir.resolve(npcName + ".json");
        if (!Files.exists(npcFile)) return;

        JsonObject json = SafeJson.readObject(npcFile);
        if (json == null)
        {
            // 内容被篡改：备份后跳过（该NPC数据视为无效）
            SafeJson.backupCorrupted(npcFile);
            LOGGER.warn("npcData.patchPartner: corrupted file skipped, {}", npcFile.toAbsolutePath());
            return;
        }

        if (partner == null || partner.isEmpty()) json.remove("partner");
        else json.addProperty("partner", partner);

        SafeJson.write(npcFile, json);
        LOGGER.info("npcData.patchPartner: Succeed, {}", npcFile.toAbsolutePath());
    }

    // 修改某未加载NPC的年龄
    public static void patchAge(ServerLevel level, String cityName, String npcName, short age)
    {
        patchJson(level, cityName, npcName, json -> json.addProperty("age", age));
    }

    // 修改某未加载NPC的孕期进度
    public static void patchPregnancy(ServerLevel level, String cityName, String npcName, float stage)
    {
        patchJson(level, cityName, npcName, json ->
        {
            if (stage > 0.0F) json.addProperty("pregnancy", stage);
            else json.remove("pregnancy");
        });
    }

    // 清除某未加载NPC的生活点登记
    public static void patchClearHome(ServerLevel level, String cityName, String npcName)
    {
        patchJson(level, cityName, npcName, json ->
        {
            json.remove("home");
            json.remove("homeBuilding");
        });
    }

    // 读-改-写单个NPC文件（仅服务端数据侧操作）
    private static void patchJson(ServerLevel level, String cityName, String npcName,
                                  java.util.function.Consumer<JsonObject> editor)
    {
        if (npcName == null || npcName.isEmpty() || cityName == null || cityName.isEmpty()
                || level.getServer() == null) return;

        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcDir = level.getServer().isDedicatedServer()
                ? gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc")
                : gameDir.resolve("NeoSim").resolve("data")
                        .resolve(level.getServer().getWorldData().getLevelName())
                        .resolve(cityName).resolve("npc");
        Path npcFile = npcDir.resolve(npcName + ".json");
        if (!Files.exists(npcFile)) return;

        JsonObject json = SafeJson.readObject(npcFile);
        if (json == null)
        {
            // 内容被篡改：备份后跳过（该NPC数据视为无效）
            SafeJson.backupCorrupted(npcFile);
            LOGGER.warn("npcData.patchJson: corrupted file skipped, {}", npcFile.toAbsolutePath());
            return;
        }

        editor.accept(json);

        SafeJson.write(npcFile, json);
        LOGGER.info("npcData.patchJson: Succeed, {}", npcFile.toAbsolutePath());
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

    // 按服务端环境解析列出
    public static List<String> listNpcNames(ServerLevel level, String cityName)
    {
        if (level.getServer() == null || cityName == null || cityName.isEmpty()) return List.of();
        if (level.getServer().isDedicatedServer())
        {
            return listNpcNames(cityName);
        }
        return listNpcNames(cityName, level.getServer().getWorldData().getLevelName());
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

    // 按服务端环境解析删除
    public static void delete(ServerLevel level, String cityName, String npcName)
    {
        if (level.getServer() == null || cityName == null || cityName.isEmpty()
                || npcName == null || npcName.isEmpty()) return;
        if (level.getServer().isDedicatedServer())
        {
            delete(npcName, cityName);
        }
        else
        {
            delete(npcName, cityName, level.getServer().getWorldData().getLevelName());
        }
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

            {
                JsonObject json = new JsonObject();

                // 姓名
                json.addProperty("name", entity.getNpcName());
                json.addProperty("surname", entity.getNpcSurname());
                json.addProperty("givenName", entity.getNpcGivenName());

                // 性别
                json.addProperty("sex", entity.getSex());

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

                // 职业等级
                JsonObject job = new JsonObject();
                job.addProperty("architect", entity.getJobArchitect());
                job.addProperty("farmer", entity.getJobFarmer());
                job.addProperty("miner", entity.getJobMiner());
                job.addProperty("courier", entity.getJobCourier());
                json.add("job", job);

                // 生活点
                BlockPos home = entity.getHomePos();
                if (home != null)
                {
                    JsonObject homeObj = new JsonObject();
                    homeObj.addProperty("x", home.getX());
                    homeObj.addProperty("y", home.getY());
                    homeObj.addProperty("z", home.getZ());
                    json.add("home", homeObj);
                    json.addProperty("homeBuilding", entity.getHomeBuilding());
                }

                // 孕期进度
                if (entity.getPregnancyStage() > 0.0F)
                {
                    json.addProperty("pregnancy", entity.getPregnancyStage());
                }

                // 关系与族谱
                if (!entity.getPartner().isEmpty())
                {
                    json.addProperty("partner", entity.getPartner());
                }
                List<String> parents = entity.getParentNames();
                if (!parents.isEmpty())
                {
                    JsonArray parentsArr = new JsonArray();
                    for (String p : parents) parentsArr.add(p);
                    json.add("parents", parentsArr);
                }
                List<String> children = entity.getChildren();
                if (!children.isEmpty())
                {
                    JsonArray childrenArr = new JsonArray();
                    for (String c : children) childrenArr.add(c);
                    json.add("children", childrenArr);
                }

                SafeJson.write(npcFile, json);
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

        JsonObject json = SafeJson.readObject(npcFile);
        if (json == null)
        {
            // 内容被篡改/清空：备份.bak后删除原文件，该NPC数据视为不存在，游戏继续运行
            SafeJson.backupCorrupted(npcFile);
            try
            {
                Files.deleteIfExists(npcFile);
                LOGGER.warn("npcData.load: corrupted, backed up and removed, {}", npcFile.toAbsolutePath());
            }
            catch (IOException e)
            {
                LOGGER.error("npcData.load: corrupted file removal fail, path={}, error={}",
                        npcFile.toAbsolutePath(), e.getMessage(), e);
            }
            return null;
        }
        LOGGER.info("npcData.load: Succeed, {}", npcFile.toAbsolutePath());
        return json;
    }
}

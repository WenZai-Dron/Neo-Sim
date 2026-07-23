package com.wenzai.neosim.npc;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.storage.ModSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Manage
{
    private Manage() {}

    // 按城市计数
    public static short getPopulation(ServerLevel level, String cityName)
    {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path npcDir;
        if (level.getServer().isDedicatedServer())
        {
            npcDir = gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc");
        }
        else
        {
            String saveName = level.getServer().getWorldData().getLevelName();
            npcDir = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName).resolve("npc");
        }

        short count = 0;

        if (Files.exists(npcDir))
        {
            try (var stream = Files.list(npcDir))
            {
                count = (short) stream.filter(Files::isRegularFile).count();
            }
            catch (IOException e)
            {
                NeoSim.LOGGER.error("NeoSim-getPopulation: Fail to list npc dir, {}", e.getMessage(), e);
                return ModSavedData.get(level).getPopulation();
            }
        }

        NeoSim.LOGGER.info("NeoSim-getPopulation: {} NPC files in {}", count, npcDir.toAbsolutePath());
        return count;
    }

    // 检查城市是否存在
    public static boolean cityExists(ServerLevel level, String cityName)
    {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path cityDir;
        if (level.getServer().isDedicatedServer())
        {
            cityDir = gameDir.resolve("NeoSim").resolve("data").resolve(cityName);
        }
        else
        {
            String saveName = level.getServer().getWorldData().getLevelName();
            cityDir = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName);
        }
        return Files.exists(cityDir);
    }

    public static void ensurePopulationNotEmpty(ServerLevel level)
    {
        ModSavedData data = ModSavedData.get(level);
        if (data.getPopulation() >= 200)
        {
            return;
        }
        if (data.getPopulation() == 0)
        {
            String cityName = ModSavedData.getActiveCityName();
            if (!cityName.isEmpty())
            {
                // 先尝试从JSON恢复，没有存档文件才随机生成
                restoreAll(level, cityName);
                if (getPopulation(level, cityName) == 0)
                {
                    npcAdd.spawn(level);
                    data.setPopulation((short) 1, level);
                }
            }
        }
    }

    // 从JSON文件恢复城市中所有NPC
    public static void restoreAll(ServerLevel level, String cityName)
    {
        List<String> npcNames;
        if (level.getServer().isDedicatedServer())
        {
            npcNames = npcData.listNpcNames(cityName);
        }
        else
        {
            String saveName = level.getServer().getWorldData().getLevelName();
            npcNames = npcData.listNpcNames(cityName, saveName);
        }

        if (npcNames.isEmpty())
        {
            NeoSim.LOGGER.info("NeoSim-restoreAll: No NPC files found for city {}", cityName);
            return;
        }

        for (String npcName : npcNames)
        {
            JsonObject json;
            if (level.getServer().isDedicatedServer())
            {
                json = npcData.load(npcName, cityName);
            }
            else
            {
                String saveName = level.getServer().getWorldData().getLevelName();
                json = npcData.load(npcName, cityName, saveName);
            }

            if (json == null) continue;

            Entity npc = Entity.NPC.get().create(level);
            if (npc == null)
            {
                NeoSim.LOGGER.error("NeoSim-restoreAll: Fail to create NPC for {}", npcName);
                continue;
            }

            // 恢复姓名
            Name name = Name.of(npc.getPersistentData());
            if (json.has("surname")) name.setSurname(json.get("surname").getAsString());
            if (json.has("givenName")) name.setGivenName(json.get("givenName").getAsString());
            if (json.has("name")) name.set(json.get("name").getAsString());
            npc.setNpcName(name.get());

            // 记录所属城市，用于死亡时删除文件
            npc.setCityName(cityName);

            // 恢复性别
            if (json.has("sex")) name.setSex(json.get("sex").getAsString());

            // 恢复皮肤
            if (json.has("skin")) npc.setSkin(json.get("skin").getAsString());

            // 恢复位置
            if (json.has("position"))
            {
                JsonObject pos = json.getAsJsonObject("position");
                double x = pos.has("x") ? pos.get("x").getAsDouble() : 0;
                double y = pos.has("y") ? pos.get("y").getAsDouble() : 64;
                double z = pos.has("z") ? pos.get("z").getAsDouble() : 0;
                float yaw = json.has("yaw") ? json.get("yaw").getAsFloat() : 0F;
                float pitch = json.has("pitch") ? json.get("pitch").getAsFloat() : 0F;
                npc.moveTo(x, y, z, yaw, pitch);
            }

            // 恢复生命值
            if (json.has("health"))
            {
                npc.setHealth(json.get("health").getAsFloat());
            }

            // 恢复年龄
            if (json.has("age")) npc.setAge(json.get("age").getAsShort());

            level.addFreshEntity(npc);
            NeoSim.LOGGER.info("NeoSim-restoreAll: Restored {} (sex={}) at ({}, {}, {})",
                    npc.getNpcName(), npc.getSex(), npc.getX(), npc.getY(), npc.getZ());
        }

        // 同步人口
        short pop = getPopulation(level, cityName);
        ModSavedData.get(level).setPopulation(pop, level);
        NeoSim.LOGGER.info("NeoSim-restoreAll: Restored {} NPCs for city {}", npcNames.size(), cityName);
    }

    // 在指定城市生成NPC，姓名与性别随机
    public static void spawnAt(ServerLevel level, BlockPos pos, String cityName)
    {
        // 人口上限检查
        short currentPop = getPopulation(level, cityName);
        if (currentPop >= 200)
        {
            NeoSim.LOGGER.warn("NeoSim-spawnAt: Population = 200，city: {}", cityName);
            return;
        }

        Entity npc = Entity.NPC.get().create(level);
        if (npc == null)
        {
            NeoSim.LOGGER.error("NeoSim-spawnAt: Fail to create NPC");
            return;
        }

        // 随机姓名与性别
        Name.of(npc.getPersistentData()).generateAndSet();
        npc.setNpcName(Name.of(npc.getPersistentData()).get());

        // 随机皮肤
        npc.setSkin(Entity.randomSkin(npc.getSex()));

        // 记录所属城市，用于死亡时删除文件
        npc.setCityName(cityName);

        // 放置到指定位置（其实就是指令使用者的原地）
        npc.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);

        // 加入世界
        level.addFreshEntity(npc);

        // 保存数据
        if (level.getServer().isDedicatedServer())
        {
            npcData.save(npc, cityName);
        }
        else
        {
            String saveName = level.getServer().getWorldData().getLevelName();
            npcData.save(npc, cityName, saveName);
        }

        // 更新人口
        short pop = getPopulation(level, cityName);
        ModSavedData.get(level).setPopulation(pop, level);

        NeoSim.LOGGER.info("NeoSim-spawnAt: Spawned {} (sex={}) in city {} at ({}, {}, {})",
                npc.getNpcName(), npc.getSex(), cityName, npc.getX(), npc.getY(), npc.getZ());
    }

    // 生成第一个NPC
    public static class npcAdd
    {
        private npcAdd() {}

        static void spawn(ServerLevel level)
        {
            Entity npc = Entity.NPC.get().create(level);
            if (npc == null)
            {
                NeoSim.LOGGER.error("NeoSim-npcAdd: Fail");
                return;
            }

            // 分配姓名
            Name.of(npc.getPersistentData()).generateAndSet();
            npc.setNpcName(Name.of(npc.getPersistentData()).get());

            // 随机皮肤
            npc.setSkin(Entity.randomSkin(npc.getSex()));

            // 记录所属城市，用于死亡时删除文件
            String cityName = ModSavedData.getActiveCityName();
            npc.setCityName(cityName);

            // 放置在世界出生点
            BlockPos spawnPos = level.getSharedSpawnPos();
            npc.moveTo(
                    spawnPos.getX() + 0.5D,
                    spawnPos.getY(),
                    spawnPos.getZ() + 0.5D,
                    0.0F,
                    0.0F
            );

            // 加入世界
            level.addFreshEntity(npc);

            // 保存数据
            if (cityName.isEmpty())
            {
                NeoSim.LOGGER.warn("NeoSim-Add: cityName is empty");
            }
            else
            {
                if (level.getServer().isDedicatedServer())
                {
                    npcData.save(npc, cityName);
                }
                else
                {
                    String saveName = level.getServer().getWorldData().getLevelName();
                    npcData.save(npc, cityName, saveName);
                }
            }

            NeoSim.LOGGER.info("NeoSim-Add: Spawned {} at ({}, {}, {})",
                    npc.getNpcName(), npc.getX(), npc.getY(), npc.getZ());
        }
    }
}

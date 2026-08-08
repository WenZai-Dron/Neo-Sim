package com.wenzai.neosim.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.life.Genealogy;
import com.wenzai.neosim.life.LifeSystem;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    // 持续补人
    public static void replenishPopulation(ServerLevel level, String cityName)
    {
        if (cityName.isEmpty()) return;

        // 人口已达上限：不再补人
        if (getPopulation(level, cityName) >= Config.MAX_POPULATION.get()) return;

        // 城市有档案但无实体（服务器重启）：先恢复玩家附近的
        if (getPopulation(level, cityName) > 0)
        {
            respawnNearPlayers(level, cityName);

            // 恢复后仍无任何已加载实体（档案位置都远离玩家）：本次不补人，等玩家靠近再按需恢复
            if (!hasLoadedNpc(level, cityName)) return;

            // 仍有流浪者（含未加载档案中的流浪者）：等人入住，不补
            if (hasWanderer(level, cityName)) return;
        }

        spawnWithAnnouncement(level, cityName);
    }

    // 城市内是否有流浪者：已加载实体+未加载档案双查
    private static boolean hasWanderer(ServerLevel level, String cityName)
    {
        Set<String> loadedNames = new HashSet<>();
        for (net.minecraft.world.entity.Entity e : level.getAllEntities())
        {
            if (e instanceof Entity npc && cityName.equals(npc.getCityName()))
            {
                loadedNames.add(npc.getNpcName());
                if (npc.getHomePos() == null)
                {
                    return true;
                }
            }
        }

        // 未加载的档案也计入：无生活点即流浪者
        for (String name : NpcData.listNpcNames(level, cityName))
        {
            if (loadedNames.contains(name)) continue;
            JsonObject json = NpcData.load(level, cityName, name);
            if (json != null && !json.has("home"))
            {
                return true;
            }
        }
        return false;
    }

    // 城市是否有已加载的NPC实体
    private static boolean hasLoadedNpc(ServerLevel level, String cityName)
    {
        for (net.minecraft.world.entity.Entity e : level.getAllEntities())
        {
            if (e instanceof Entity npc && cityName.equals(npc.getCityName()))
            {
                return true;
            }
        }
        return false;
    }

    // 自动入城：生成新NPC并公告
    public static void spawnWithAnnouncement(ServerLevel level, String cityName)
    {
        if (getPopulation(level, cityName) >= Config.MAX_POPULATION.get()) return;

        Entity npc = Entity.NPC.get().create(level);
        if (npc == null)
        {
            NeoSim.LOGGER.error("NeoSim-spawnWithAnnouncement: Fail to create NPC");
            return;
        }

        // 随机姓名与性别
        Entity.generateAndSetName(npc);
        npc.setNpcName(npc.getNpcName());

        // 随机皮肤
        npc.setSkin(Entity.randomSkin(npc.getSex()));

        // 记录所属城市，用于死亡时删除文件
        npc.setCityName(cityName);

        // 有空位则分配生活点（先于保存）
        CityLivingManager.tryAssignHome(level, npc);

        // 放置在世界出生点
        BlockPos spawnPos = level.getSharedSpawnPos();
        npc.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D, 0.0F, 0.0F);

        // 加入世界
        level.addFreshEntity(npc);

        // 保存数据
        if (level.getServer().isDedicatedServer())
        {
            NpcData.save(npc, cityName);
        }
        else
        {
            String saveName = level.getServer().getWorldData().getLevelName();
            NpcData.save(npc, cityName, saveName);
        }

        // 更新人口
        short pop = getPopulation(level, cityName);
        ModSavedData.get(level).setPopulation(pop, level);

        // 公告
        LifeSystem.announce(level, cityName, "§f" + npc.getNpcName() + " §e来到了城市");
        NeoSim.LOGGER.info("NeoSim-spawnWithAnnouncement: Spawned {} (sex={}) in city {}",
                npc.getNpcName(), npc.getSex(), cityName);
    }

    // 从文件恢复城市中所有NPC（服务器重启/全量恢复）
    public static void restoreAll(ServerLevel level, String cityName)
    {
        List<String> npcNames = NpcData.listNpcNames(level, cityName);

        if (npcNames.isEmpty())
        {
            NeoSim.LOGGER.info("NeoSim-restoreAll: No NPC files found for city {}", cityName);
            return;
        }

        int restored = 0;
        for (String npcName : npcNames)
        {
            if (spawnSingle(level, cityName, npcName) != null)
            {
                restored++;
            }
        }

        // 同步人口
        short pop = getPopulation(level, cityName);
        ModSavedData.get(level).setPopulation(pop, level);
        NeoSim.LOGGER.info("NeoSim-restoreAll: Restored {} NPCs for city {}", restored, cityName);
    }

    // 从文件恢复单个NPC
    public static Entity spawnSingle(ServerLevel level, String cityName, String npcName)
    {
        return spawnSingle(level, cityName, npcName, null);
    }

    // 从文件恢复单个NPC
    public static Entity spawnSingle(ServerLevel level, String cityName, String npcName, BlockPos atPos)
    {
        JsonObject json = NpcData.load(level, cityName, npcName);
        if (json == null) return null;

        Entity npc = Entity.NPC.get().create(level);
        if (npc == null)
        {
            NeoSim.LOGGER.error("NeoSim-spawnSingle: Fail to create NPC for {}", npcName);
            return null;
        }

        // 恢复姓名
        CompoundTag tag = npc.getPersistentData();
        if (json.has("surname")) tag.putString(Entity.KEY_SURNAME, json.get("surname").getAsString());
        if (json.has("givenName")) tag.putString(Entity.KEY_GIVEN_NAME, json.get("givenName").getAsString());
        if (json.has("name")) tag.putString(Entity.KEY_FULL_NAME, json.get("name").getAsString());
        npc.setNpcName(json.has("name") ? json.get("name").getAsString() : "");

        // 记录所属城市，用于死亡时删除文件
        npc.setCityName(cityName);

        // 恢复性别
        if (json.has("sex")) npc.setSex(json.get("sex").getAsString());

        // 恢复皮肤
        if (json.has("skin")) npc.setSkin(json.get("skin").getAsString());

        // 恢复位置
        if (atPos != null)
        {
            npc.moveTo(atPos.getX() + 0.5D, atPos.getY() + 1.0D, atPos.getZ() + 0.5D, 0.0F, 0.0F);
        }
        else if (json.has("position"))
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

        // 恢复职业等级
        if (json.has("job"))
        {
            JsonObject job = json.getAsJsonObject("job");
            if (job.has("architect")) npc.setJobArchitect(job.get("architect").getAsByte());
            if (job.has("farmer")) npc.setJobFarmer(job.get("farmer").getAsByte());
            if (job.has("miner")) npc.setJobMiner(job.get("miner").getAsByte());
            if (job.has("courier")) npc.setJobCourier(job.get("courier").getAsByte());
        }

        // 恢复生活点
        if (json.has("home"))
        {
            JsonObject home = json.getAsJsonObject("home");
            BlockPos homePos = new BlockPos(
                    home.get("x").getAsInt(), home.get("y").getAsInt(), home.get("z").getAsInt());
            String homeBuilding = json.has("homeBuilding")
                    ? json.get("homeBuilding").getAsString() : "";
            npc.setHome(homePos, homeBuilding);
        }

        // 恢复孕期进度
        if (json.has("pregnancy")) npc.setPregnancyStage(json.get("pregnancy").getAsFloat());

        // 恢复关系与族谱
        if (json.has("partner")) npc.setPartner(json.get("partner").getAsString());
        if (json.has("parents"))
        {
            JsonArray parents = json.getAsJsonArray("parents");
            String p1 = parents.size() > 0 ? parents.get(0).getAsString() : "";
            String p2 = parents.size() > 1 ? parents.get(1).getAsString() : "";
            npc.setParents(p1, p2);
        }
        if (json.has("children"))
        {
            JsonArray children = json.getAsJsonArray("children");
            List<String> childList = new java.util.ArrayList<>();
            for (JsonElement e : children) childList.add(e.getAsString());
            npc.setChildren(childList);
        }

        level.addFreshEntity(npc);
        NeoSim.LOGGER.info("NeoSim-spawnSingle: Restored {} (sex={}) at ({}, {}, {})",
                npc.getNpcName(), npc.getSex(), npc.getX(), npc.getY(), npc.getZ());
        return npc;
    }

    // 卸载阈值：远离所有玩家超过该距离则卸载
    private static final double DESPAWN_DISTANCE = 50.0D;
    
    // 恢复阈值：玩家进入该距离才从文件恢复，与卸载阈值留滞后带避免来回抖动
    private static final double RESPAWN_DISTANCE = 40.0D;

    // 远离所有玩家的NPC卸载
    public static void despawnFarFromPlayers(ServerLevel level, String cityName)
    {
        List<? extends Player> players = level.players();
        for (net.minecraft.world.entity.Entity e : level.getAllEntities())
        {
            if (!(e instanceof Entity npc)) continue;
            if (!cityName.equals(npc.getCityName())) continue;
            
            // GUI冻结中：不卸载
            if (npc.isFrozen()) continue;
            
            // 雇佣：建筑进度全局推进，不因玩家远离而停
            if (npc.hasJob()) continue;
            if (nearestPlayerDistSqr(npc, players) > DESPAWN_DISTANCE * DESPAWN_DISTANCE)
            {
                despawnNpc(level, npc);
            }
        }
    }

    // 恢复在玩家附近的未加载NPC
    public static void respawnNearPlayers(ServerLevel level, String cityName)
    {
        List<? extends Player> players = level.players();
        if (players.isEmpty()) return;

        Set<String> loadedNames = new HashSet<>();
        for (net.minecraft.world.entity.Entity e : level.getAllEntities())
        {
            if (e instanceof Entity npc && cityName.equals(npc.getCityName()))
            {
                loadedNames.add(npc.getNpcName());
            }
        }

        for (String name : NpcData.listNpcNames(level, cityName))
        {
            if (loadedNames.contains(name)) continue;
            JsonObject json = NpcData.load(level, cityName, name);
            if (json == null || !json.has("position")) continue;

            JsonObject pos = json.getAsJsonObject("position");
            double x = pos.get("x").getAsDouble();
            double y = pos.get("y").getAsDouble();
            double z = pos.get("z").getAsDouble();
            for (Player p : players)
            {
                double dx = p.getX() - x;
                double dy = p.getY() - y;
                double dz = p.getZ() - z;
                if (dx * dx + dy * dy + dz * dz < RESPAWN_DISTANCE * RESPAWN_DISTANCE)
                {
                    spawnSingle(level, cityName, name);
                    break;
                }
            }
        }
    }

    // 快照（最新状态写回文件）后卸载实体
    public static void despawnNpc(ServerLevel level, Entity npc)
    {
        String name = npc.getNpcName();
        String city = npc.getCityName();
        npc.syncToJson();
        npc.discard();
        NeoSim.LOGGER.info("NeoSim-despawnNpc: Unloaded {} (city={})", name, city);
    }

    // 数据侧死亡：实体未加载，只动文件与记录
    public static void dieUnloaded(ServerLevel level, String cityName, String npcName)
    {
        JsonObject json = NpcData.load(level, cityName, npcName);
        if (json == null) return;

        int age = json.has("age") ? json.get("age").getAsShort() : 0;
        LifeSystem.announce(level, cityName, "§f" + npcName
                + " §e年纪大了，感觉不太舒服……哦不！ §f(At " + age
                + " — oh well, they had a good long life!)");

        // 族谱摘除（含未加载亲戚的文件改写）+删关系文件
        Genealogy.onDeath(level, cityName, npcName);

        // 退房
        CityLivingManager.releaseHomeByName(level, cityName, npcName);

        // 删档+人口同步
        NpcData.delete(level, cityName, npcName);
        short pop = getPopulation(level, cityName);
        ModSavedData.get(level).setPopulation(pop, level);
        NeoSim.LOGGER.info("NeoSim-dieUnloaded: '{}' died of old age while unloaded (age {})", npcName, age);
    }

    // 到最近玩家的距离平方
    private static double nearestPlayerDistSqr(Entity npc, List<? extends Player> players)
    {
        double best = Double.MAX_VALUE;
        for (Player p : players)
        {
            double d = npc.distanceToSqr(p);
            if (d < best) best = d;
        }
        return best;
    }

    // 在指定城市生成NPC，姓名与性别随机
    public static void spawnAt(ServerLevel level, BlockPos pos, String cityName)
    {
        // 人口上限检查
        short currentPop = getPopulation(level, cityName);
        if (currentPop >= Config.MAX_POPULATION.get())
        {
            NeoSim.LOGGER.warn("NeoSim-spawnAt: Population at max ({}), city: {}", Config.MAX_POPULATION.get(), cityName);
            return;
        }

        Entity npc = Entity.NPC.get().create(level);
        if (npc == null)
        {
            NeoSim.LOGGER.error("NeoSim-spawnAt: Fail to create NPC");
            return;
        }

        // 随机姓名与性别
        Entity.generateAndSetName(npc);
        npc.setNpcName(npc.getNpcName());

        // 随机皮肤
        npc.setSkin(Entity.randomSkin(npc.getSex()));

        // 记录所属城市，用于死亡时删除文件
        npc.setCityName(cityName);

        // 有空位则分配生活点（先于保存）
        CityLivingManager.tryAssignHome(level, npc);

        // 放置到指定位置（其实就是指令使用者的原地）
        npc.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);

        // 加入世界
        level.addFreshEntity(npc);

        // 保存数据
        if (level.getServer().isDedicatedServer())
        {
            NpcData.save(npc, cityName);
        }
        else
        {
            String saveName = level.getServer().getWorldData().getLevelName();
            NpcData.save(npc, cityName, saveName);
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
            Entity.generateAndSetName(npc);
            npc.setNpcName(npc.getNpcName());

            // 随机皮肤
            npc.setSkin(Entity.randomSkin(npc.getSex()));

            // 记录所属城市，用于死亡时删除文件
            String cityName = ModSavedData.getActiveCityName();
            npc.setCityName(cityName);

            // 有空位则分配生活点（先于保存）
            CityLivingManager.tryAssignHome(level, npc);

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
                    NpcData.save(npc, cityName);
                }
                else
                {
                    String saveName = level.getServer().getWorldData().getLevelName();
                    NpcData.save(npc, cityName, saveName);
                }
            }

            NeoSim.LOGGER.info("NeoSim-Add: Spawned {} at ({}, {}, {})",
                    npc.getNpcName(), npc.getX(), npc.getY(), npc.getZ());
        }
    }
}

package com.wenzai.neosim.npc;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.building.BuildingInstance;
import com.wenzai.neosim.building.ConstructionTask;
import com.wenzai.neosim.building.ControlBoxPersistence;
import com.wenzai.neosim.building.ControlBoxPersistence.ControlBoxRecord;
import com.wenzai.neosim.building.ControlBoxPersistence.Resident;
import com.wenzai.neosim.schematic.BuildingType;
import com.wenzai.neosim.storage.FileCreater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 城市级生活点池：完工住宅注册空位、无家NPC入住、退房/驱逐时空位自动归还
public class CityLivingManager
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private CityLivingManager() {}

    // 建筑完工：注册生活点空位
    public static void onBuildingCompleted(ServerLevel level, BuildingInstance building)
    {
        String city = ConstructionTask.cityOf(building, level);
        if (city == null || city.isEmpty()) return;

        ControlBoxRecord rec = ControlBoxPersistence.findRecord(level, city, building.getControlBoxPos());
        if (rec == null) return;

        int free = freeSlotCount(rec, building);
        if (free <= 0) return;

        // 建造者优先入住
        String workerName = building.getWorkerName();
        if (workerName != null && !workerName.isEmpty())
        {
            Entity builder = findNpc(level, workerName);
            if (builder != null && builder.getHomePos() == null)
            {
                assignHome(level, city, rec, building, builder);
                free--;
            }
        }

        // 剩余空位分配给城市内无家NPC
        if (free > 0)
        {
            for (net.minecraft.world.entity.Entity e : level.getAllEntities())
            {
                if (free <= 0) break;
                if (!(e instanceof Entity npc)) continue;
                if (!city.equals(npc.getCityName())) continue;
                if (npc.getHomePos() != null) continue;
                if (assignHome(level, city, rec, building, npc))
                {
                    free--;
                }
            }
        }
    }

    // NPC生成时：城市有空位则分配，无则保持流浪
    public static void tryAssignHome(ServerLevel level, Entity npc)
    {
        String city = npc.getCityName();
        if (city.isEmpty() || npc.getHomePos() != null) return;

        for (ControlBoxRecord rec : ControlBoxPersistence.load(level, city))
        {
            // 空位从记录中算，只知道建筑类型
            if (freeSlotCount(rec) > 0)
            {
                assignHome(level, city, rec, null, npc);
                return;
            }
        }
    }

    // 退房（NPC死亡等）：从记录居民列表移除，空位归还
    public static void releaseHome(ServerLevel level, Entity npc)
    {
        String name = npc.getNpcName();
        String city = npc.getCityName();
        if (name.isEmpty() || city.isEmpty()) return;

        if (releaseHomeByName(level, city, name))
        {
            npc.clearHome();
        }
    }

    // 按名退房（成年离家/死亡）：仅动记录
    public static boolean releaseHomeByName(ServerLevel level, String cityName, String name)
    {
        if (name == null || name.isEmpty() || cityName == null || cityName.isEmpty()) return false;

        List<ControlBoxRecord> all = ControlBoxPersistence.load(level, cityName);
        boolean changed = false;
        for (ControlBoxRecord rec : all)
        {
            if (rec.residents().removeIf(r -> r.name().equals(name)))
            {
                changed = true;
                break;
            }
        }
        if (changed)
        {
            ControlBoxPersistence.save(level, cityName, all);
            LOGGER.info("NeoSim-CityLivingManager: '{}' checked out of home", name);
        }
        return changed;
    }

    // 记录被删除（模盒/控制箱被破坏）时，全部居民失去家（应该有问题）
    public static void evictResidents(ServerLevel level, ControlBoxRecord record)
    {
        int count = record.residents().size();
        for (Resident r : record.residents())
        {
            Entity npc = findNpc(level, r.name());
            if (npc != null)
            {
                npc.clearHome();
            }
        }
        record.residents().clear();
        LOGGER.info("NeoSim-CityLivingManager: evicted {} residents", count);
    }

    // 空位数量（无生活点的住宅按1个控制箱位计）
    public static int freeSlotCount(ControlBoxRecord rec)
    {
        return freeSlotCount(rec, null);
    }

    private static int freeSlotCount(ControlBoxRecord rec, BuildingInstance building)
    {
        int slots = rec.livingPoints().size();
        if (slots == 0 && isResidential(building))
        {
            // 控制箱位
            slots = 1;
        }
        return slots - rec.residents().size();
    }

    private static boolean isResidential(BuildingInstance building)
    {
        return building != null
                && building.getSchematic() != null
                && building.getSchematic().getType() == BuildingType.RESIDENTIAL;
    }

    // 入住：取第一个未被占用的生活点，登记并公告
    private static boolean assignHome(ServerLevel level, String cityName,
                                      ControlBoxRecord rec, BuildingInstance building, Entity npc)
    {
        BlockPos slot = findFreeSlot(rec, building);
        if (slot == null) return false;

        // 生活点被堵：向上最多8格找空气
        slot = findAirAbove(level, slot);

        registerResident(level, cityName, rec, npc, slot);
        return true;
    }

    // 婚姻同居：把NPC登记进已有住宅的空位
    public static boolean assignToExistingHome(ServerLevel level, String cityName, Entity npc, ControlBoxRecord rec)
    {
        BlockPos slot = findFreeSlot(rec, null);
        if (slot == null) return false;

        // 生活点被堵：向上最多8格找空气
        slot = findAirAbove(level, slot);

        registerResident(level, cityName, rec, npc, slot);
        return true;
    }

    // 找第一个未被占用的生活点（无生活点记录时按1个控制箱位计）
    private static BlockPos findFreeSlot(ControlBoxRecord rec, BuildingInstance building)
    {
        if (!rec.livingPoints().isEmpty())
        {
            Set<BlockPos> occupied = new HashSet<>();
            for (Resident r : rec.residents())
            {
                occupied.add(r.pos());
            }
            for (BlockPos p : rec.livingPoints())
            {
                if (!occupied.contains(p))
                {
                    return p;
                }
            }
            return null;
        }
        if (isResidential(building) || building == null)
        {
            // 无生活点的住宅按1个控制箱位计
            BlockPos slot = rec.boxPos();
            for (Resident r : rec.residents())
            {
                if (r.pos().equals(slot))
                {
                    return null;
                }
            }
            return slot;
        }
        return null;
    }

    // 登记入住
    private static void registerResident(ServerLevel level, String cityName, ControlBoxRecord rec,
                                         Entity npc, BlockPos slot)
    {
        npc.setHome(slot, rec.schematicName());
        rec.residents().add(new Resident(npc.getNpcName(), slot.getX(), slot.getY(), slot.getZ()));
        ControlBoxPersistence.updateRecord(level, cityName, rec);

        announce(level, cityName, "§f" + npc.getNpcName() + " §e搬进了 §f" + rec.schematicName());
        LOGGER.info("NeoSim-CityLivingManager: '{}' moved into '{}' at {}", npc.getNpcName(),
                rec.schematicName(), slot);
    }

    // 生活点被堵：向上最多8格找空气
    private static BlockPos findAirAbove(ServerLevel level, BlockPos pos)
    {
        BlockPos.MutableBlockPos p = pos.mutable();
        for (int i = 0; i < 8; i++)
        {
            if (level.getBlockState(p).canBeReplaced())
            {
                return p.immutable();
            }
            p.move(Direction.UP);
        }
        return pos;
    }

    private static Entity findNpc(ServerLevel level, String name)
    {
        for (net.minecraft.world.entity.Entity e : level.getAllEntities())
        {
            if (e instanceof Entity npc && name.equals(npc.getNpcName()))
            {
                return npc;
            }
        }
        return null;
    }

    // 公告给该城市在线玩家
    private static void announce(ServerLevel level, String cityName, String msg)
    {
        if (level.getServer() == null) return;
        boolean dedicated = level.getServer().isDedicatedServer();
        String saveName = dedicated ? null : level.getServer().getWorldData().getLevelName();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers())
        {
            boolean inCity = dedicated
                    ? FileCreater.isPlayerInCity(cityName, player.getName().getString())
                    : FileCreater.isPlayerInCity(cityName, saveName, player.getName().getString());
            if (inCity)
            {
                player.displayClientMessage(Component.literal(msg), false);
            }
        }
    }
}

package com.wenzai.neosim.building;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.Comparator;

// 区块加载
public class BuildingChunkLoader
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final TicketType<ChunkPos> BUILDING_TICKET =
            TicketType.create("neo_sim:building", Comparator.comparingLong(ChunkPos::toLong));

    private BuildingChunkLoader() {}

    // 为建筑覆盖的所有区块注册BORDER模式ticket
    public static void registerForBuilding(BuildingInstance building, ServerLevel level)
    {
        if (building.getControlBoxPos() == null || building.getSchematic() == null) return;
        int sx = building.getSchematic().getSizeX();
        int sy = building.getSchematic().getSizeY();
        int sz = building.getSchematic().getSizeZ();
        if (sx <= 0 || sy <= 0 || sz <= 0) return;

        BlockPos c0 = building.blueprintToWorld(0, 0, 0);
        BlockPos c1 = building.blueprintToWorld(sx - 1, sy - 1, sz - 1);
        int minCX = Math.min(c0.getX(), c1.getX()) >> 4;
        int maxCX = Math.max(c0.getX(), c1.getX()) >> 4;
        int minCZ = Math.min(c0.getZ(), c1.getZ()) >> 4;
        int maxCZ = Math.max(c0.getZ(), c1.getZ()) >> 4;

        for (int cx = minCX; cx <= maxCX; cx++)
        {
            for (int cz = minCZ; cz <= maxCZ; cz++)
            {
                ChunkPos cp = new ChunkPos(cx, cz);
                level.getChunkSource().addRegionTicket(BUILDING_TICKET, cp, 0, cp);
                building.addLoadedChunk(cp);
            }
        }
        LOGGER.info("NeoSim-BuildingChunkLoader: {} chunks loaded for '{}' at {}",
                building.getLoadedChunks().size(), building.getSchematicName(), building.getControlBoxPos());
    }

    // 释放该建筑加载的区块
    public static void releaseForBuilding(BuildingInstance building, ServerLevel level)
    {
        for (ChunkPos cp : building.getLoadedChunks())
        {
            level.getChunkSource().removeRegionTicket(BUILDING_TICKET, cp, 0, cp);
        }
        building.clearLoadedChunks();
    }
}

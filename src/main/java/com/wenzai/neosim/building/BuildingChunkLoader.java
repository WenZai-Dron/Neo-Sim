package com.wenzai.neosim.building;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

// 区块加载（按当前建造层 ±1 的窗口随进度滚动，整栋常驻改为小窗口）
public class BuildingChunkLoader
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final TicketType<ChunkPos> BUILDING_TICKET =
			TicketType.create("neo_sim:building", Comparator.comparingLong(ChunkPos::toLong));

	private BuildingChunkLoader()
	{
	}

	// 为建筑覆盖的所有区块注册BORDER模式ticket（首次全量注册，随后 updateWindow 收窄滚动）
	public static void registerForBuilding(BuildingInstance building, ServerLevel level)
	{
		if (building.getControlBoxPos() == null || building.getSchematic() == null) return;
		int sx = building.getSchematic().getSizeX();
		int sy = building.getSchematic().getSizeY();
		int sz = building.getSchematic().getSizeZ();
		if (sx <= 0 || sy <= 0 || sz <= 0) return;

		// 初始即注册当前层 ±1 窗口（而非整栋全部层）
		Set<Long> window = windowFor(building, level, 0);
		for (Long l : window)
		{
			ChunkPos cp = new ChunkPos(l);
			level.getChunkSource().addRegionTicket(BUILDING_TICKET, cp, 0, cp);
			building.addLoadedChunk(cp);
		}
	}

	// 按建造进度把强加载窗口滚动到"当前层 ±1"；返回是否有变化
	public static boolean updateWindow(BuildingInstance building, ServerLevel level)
	{
		if (building.getControlBoxPos() == null || building.getSchematic() == null) return false;
		int sx = building.getSchematic().getSizeX();
		int sy = building.getSchematic().getSizeY();
		int sz = building.getSchematic().getSizeZ();
		if (sx <= 0 || sy <= 0 || sz <= 0) return false;

		int layer = building.getBuildProgress() / (sx * sz);
		Set<Long> wanted = windowFor(building, level, layer);

		// 增：窗口内缺的
		boolean changed = false;
		for (Long l : wanted)
		{
			if (!building.containsLoadedChunk(l))
			{
				ChunkPos cp = new ChunkPos(l);
				level.getChunkSource().addRegionTicket(BUILDING_TICKET, cp, 0, cp);
				building.addLoadedChunk(cp);
				changed = true;
			}
		}
		// 减：已注册但不在新窗口内的
		Iterator<ChunkPos> it = building.getLoadedChunks().iterator();
		while (it.hasNext())
		{
			ChunkPos cp = it.next();
			if (!wanted.contains(cp.toLong()))
			{
				level.getChunkSource().removeRegionTicket(BUILDING_TICKET, cp, 0, cp);
				it.remove();
				changed = true;
			}
		}
		return changed;
	}

	// 当前层 ±1 覆盖的区块集合
	private static Set<Long> windowFor(BuildingInstance building, ServerLevel level, int layer)
	{
		Set<Long> out = new HashSet<>();
		int sx = building.getSchematic().getSizeX();
		int sy = building.getSchematic().getSizeY();
		int sz = building.getSchematic().getSizeZ();
		for (int dy = -1; dy <= 1; dy++)
		{
			int y = layer + dy;
			if (y < 0 || y >= sy) continue;
			BlockPos c0 = building.blueprintToWorld(0, y, 0);
			BlockPos c1 = building.blueprintToWorld(sx - 1, y, sz - 1);
			int minCX = Math.min(c0.getX(), c1.getX()) >> 4;
			int maxCX = Math.max(c0.getX(), c1.getX()) >> 4;
			int minCZ = Math.min(c0.getZ(), c1.getZ()) >> 4;
			int maxCZ = Math.max(c0.getZ(), c1.getZ()) >> 4;
			for (int cx = minCX; cx <= maxCX; cx++)
			{
				for (int cz = minCZ; cz <= maxCZ; cz++)
				{
					out.add(ChunkPos.asLong(cx, cz));
				}
			}
		}
		// 模盒所在区块始终保留（工人/交互点）
		BlockPos con = building.getConstructorPos();
		if (con != null)
		{
			out.add(ChunkPos.asLong(con.getX() >> 4, con.getZ() >> 4));
		}
		return out;
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


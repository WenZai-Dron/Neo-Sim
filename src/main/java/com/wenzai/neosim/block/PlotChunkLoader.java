// 工作盒地块区块强制加载

package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public class PlotChunkLoader
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final TicketType<ChunkPos> PLOT_TICKET =
			TicketType.create("neo_sim:workplot", Comparator.comparingLong(ChunkPos::toLong));

	// 已被某地块加载的区块（防重复注册/误释放）
	private static final Set<Long> loaded = new HashSet<>();

	private PlotChunkLoader() {}

	// 为地块矩形覆盖的所有区块注册
	public static void registerForPlot(ServerLevel level, WorkBoxPersistence.WorkBoxRecord record)
	{
		if (!record.bound()) return;
		int minCX = Math.min(record.rx1(), record.rx2()) >> 4;
		int maxCX = Math.max(record.rx1(), record.rx2()) >> 4;
		int minCZ = Math.min(record.rz1(), record.rz2()) >> 4;
		int maxCZ = Math.max(record.rz1(), record.rz2()) >> 4;

		for (int cx = minCX; cx <= maxCX; cx++)
		{
			for (int cz = minCZ; cz <= maxCZ; cz++)
			{
				ChunkPos cp = new ChunkPos(cx, cz);
				if (loaded.add(cp.toLong()))
				{
					level.getChunkSource().addRegionTicket(PLOT_TICKET, cp, 0, cp);
				}
			}
		}
		LOGGER.debug("NeoSim-PlotChunkLoader: {} chunks loaded for work box at {}", loaded.size(), record.boxPos());
	}

	// 释放该地块加载的区块
	public static void releaseForPlot(ServerLevel level, WorkBoxPersistence.WorkBoxRecord record)
	{
		if (!record.bound()) return;
		int minCX = Math.min(record.rx1(), record.rx2()) >> 4;
		int maxCX = Math.max(record.rx1(), record.rx2()) >> 4;
		int minCZ = Math.min(record.rz1(), record.rz2()) >> 4;
		int maxCZ = Math.max(record.rz1(), record.rz2()) >> 4;

		for (int cx = minCX; cx <= maxCX; cx++)
		{
			for (int cz = minCZ; cz <= maxCZ; cz++)
			{
				ChunkPos cp = new ChunkPos(cx, cz);
				if (loaded.remove(cp.toLong()))
				{
					level.getChunkSource().removeRegionTicket(PLOT_TICKET, cp, 0, cp);
				}
			}
		}
	}

	// 服务器停止/世界卸载时清空
	public static void clear()
	{
		loaded.clear();
	}
}

// 工作盒地块区块强制加载（按盒子记账，两个地块共享区块时互不误释放）

package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.*;

public class PlotChunkLoader
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final TicketType<ChunkPos> PLOT_TICKET =
			TicketType.create("neo_sim:workplot", Comparator.comparingLong(ChunkPos::toLong));

	// 按盒子记账（仿 DeliveryChunkLoader），两个地块共享区块时一个释放不会误删另一个的 ticket
	private static final Map<BlockPos, Set<Long>> plotTickets = new HashMap<>();

	private PlotChunkLoader() {}

	// 为地块矩形覆盖的所有区块注册（按盒子记账）
	public static void registerForPlot(ServerLevel level, WorkBoxPersistence.WorkBoxRecord record)
	{
		if (!record.bound()) return;
		Set<Long> set = plotTickets.computeIfAbsent(record.boxPos(), b -> new HashSet<>());
		int minCX = Math.min(record.rx1(), record.rx2()) >> 4;
		int maxCX = Math.max(record.rx1(), record.rx2()) >> 4;
		int minCZ = Math.min(record.rz1(), record.rz2()) >> 4;
		int maxCZ = Math.max(record.rz1(), record.rz2()) >> 4;

		for (int cx = minCX; cx <= maxCX; cx++)
		{
			for (int cz = minCZ; cz <= maxCZ; cz++)
			{
				ChunkPos cp = new ChunkPos(cx, cz);
				if (set.add(cp.toLong()))
				{
					level.getChunkSource().addRegionTicket(PLOT_TICKET, cp, 0, cp);
				}
			}
		}
	}

	// 释放该地块加载的区块
	public static void releaseForPlot(ServerLevel level, WorkBoxPersistence.WorkBoxRecord record)
	{
		Set<Long> set = plotTickets.remove(record.boxPos());
		if (set == null) return;
		for (Long l : set)
		{
			ChunkPos cp = new ChunkPos(l);
			level.getChunkSource().removeRegionTicket(PLOT_TICKET, cp, 0, cp);
		}
	}

	// 服务器停止/世界卸载时清空
	public static void clear()
	{
		plotTickets.clear();
	}

	// 当前已记账的盒子数（调试/测试用）
	public static int boxCount()
	{
		return plotTickets.size();
	}
}

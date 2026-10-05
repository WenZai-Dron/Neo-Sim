package com.wenzai.neosim.block;

import com.wenzai.neosim.util.ChunkWindows;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// 工作盒强制加载：只保留「作业游标 3×3 + 盒子 + 材料箱」窗口，随游标滚动（差量增删，不 add/remove 抖动）
// - 距离 0 的 region ticket = FULL 等级：区块可读写方块，但不跑实体刻/方块刻，代价最小
// - 按盒子记账：两个地块共享区块时，一个释放不会误删另一个的 ticket
public class PlotChunkLoader
{
	private static final TicketType<ChunkPos> PLOT_TICKET =
			TicketType.create("neo_sim:workplot", Comparator.comparingLong(ChunkPos::toLong));

	private static final Map<BlockPos, Set<Long>> plotTickets = new HashMap<>();

	private PlotChunkLoader()
	{
	}

	// 用任务算好的窗口替换该盒子的加载集合
	public static void setWindow(ServerLevel level, BlockPos box, Set<Long> desired)
	{
		ChunkWindows.apply(level, PLOT_TICKET, plotTickets, box, desired);
	}

	// 释放该盒子的全部区块（解雇/拆除/夜间/等待）
	public static void releaseForPlot(ServerLevel level, BlockPos box)
	{
		ChunkWindows.release(level, PLOT_TICKET, plotTickets, box);
	}

	// 服务器停止/世界卸载时清空
	public static void clear()
	{
		ChunkWindows.clearAll(plotTickets);
	}

	// 当前已记账的盒子数（调试/测试用）
	public static int boxCount()
	{
		return plotTickets.size();
	}
}

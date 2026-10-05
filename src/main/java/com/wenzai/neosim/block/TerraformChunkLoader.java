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

// 整地强制加载：等待/扫描阶段要整块地块（扫描逐块读方块状态），作业阶段收窄成「游标 3×3 + 盒子 + 材料箱」
// - 距离 0 的 region ticket = FULL 等级（可读写方块，不跑实体刻），代价最小
// - 按任务记账：两个整地任务共享区块时互不误释放
public class TerraformChunkLoader
{
	private static final TicketType<ChunkPos> TERRAFORM_TICKET =
			TicketType.create("neo_sim:terraform", Comparator.comparingLong(ChunkPos::toLong));

	private static final Map<BlockPos, Set<Long>> taskTickets = new HashMap<>();

	private TerraformChunkLoader()
	{
	}

	// 用任务算好的窗口替换该任务的加载集合
	public static void setWindow(ServerLevel level, BlockPos box, Set<Long> desired)
	{
		ChunkWindows.apply(level, TERRAFORM_TICKET, taskTickets, box, desired);
	}

	// 释放该任务加载的区块
	public static void releaseForPlot(ServerLevel level, BlockPos box)
	{
		ChunkWindows.release(level, TERRAFORM_TICKET, taskTickets, box);
	}

	// 服务器停止/世界卸载时清空
	public static void clear()
	{
		ChunkWindows.clearAll(taskTickets);
	}
}

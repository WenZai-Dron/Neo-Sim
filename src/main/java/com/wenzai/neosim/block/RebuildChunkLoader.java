package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.util.ChunkWindows;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 重建盒强制加载：只保留「当前扫描层 ±1 的整栋覆盖 + 重建盒 ±1（含相邻材料箱）+ 控制箱」窗口
// - 窗口随扫描游标换层滚动；重建完成后释放（任务 onBoxDestroyed）
// - 距离 0 的 region ticket = FULL 等级：区块可读写方块，不跑实体刻，代价最小
// - rebuildMaxChunks 仍作为单盒上限兜底：窗口超限时只保留距盒子最近的若干区块
public class RebuildChunkLoader
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final TicketType<ChunkPos> REBUILD_TICKET =
			TicketType.create("neo_sim:rebuild", Comparator.comparingLong(ChunkPos::toLong));

	// 按盒子记账（防跨盒误释放）
	private static final Map<BlockPos, Set<Long>> tickets = new HashMap<>();

	private RebuildChunkLoader()
	{
	}

	// 用任务算好的窗口替换该盒子的加载集合
	public static void setWindow(ServerLevel level, BlockPos box, Set<Long> desired)
	{
		Set<Long> capped = enforceCap(box, desired);
		ChunkWindows.apply(level, REBUILD_TICKET, tickets, box, capped);
	}

	// 释放该盒子的全部区块
	public static void release(ServerLevel level, BlockPos box)
	{
		ChunkWindows.release(level, REBUILD_TICKET, tickets, box);
	}

	// 服务器停止/世界卸载时清空
	public static void clear()
	{
		ChunkWindows.clearAll(tickets);
	}

	// 单盒上限兜底：超限时按到盒子的距离保留最近的若干区块
	private static Set<Long> enforceCap(BlockPos box, Set<Long> desired)
	{
		int cap = Math.max(1, Config.REBUILD_MAX_CHUNKS.get());
		if (desired.size() <= cap) return desired;

		int bcx = box.getX() >> 4;
		int bcz = box.getZ() >> 4;
		List<Long> all = new ArrayList<>(desired);
		all.sort(Comparator.comparingLong(l ->
		{
			ChunkPos cp = new ChunkPos(l);
			long dx = cp.x - bcx;
			long dz = cp.z - bcz;
			return dx * dx + dz * dz;
		}));
		LOGGER.warn("NeoSim-RebuildChunkLoader: window {} chunks at {} exceeds cap {}, keeping nearest {}",
				desired.size(), box, cap, cap);
		return new HashSet<>(all.subList(0, cap));
	}
}

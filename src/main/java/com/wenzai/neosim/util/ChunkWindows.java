package com.wenzai.neosim.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

// 区块窗口工具：把方块坐标折算成"待强加载的区块"集合，并按 owner 差量收放 ticket
// - 集合元素是 ChunkPos.toLong() 打包后的 long
// - 同一 owner 复用同一张实票：只有新增区块才 addRegionTicket，消失的才 removeRegionTicket
public class ChunkWindows
{
	private ChunkWindows()
	{
	}

	// 该方块所在区块是否已加载
	// 未加载时读方块状态 / 写方块都会触发同步加载（卡主线程），全田/整层扫描前必须以此跳过
	public static boolean isLoaded(ServerLevel level, BlockPos pos)
	{
		return level.hasChunkAt(pos);
	}

	// 单点所在区块
	public static long of(BlockPos pos)
	{
		return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
	}

	// 以某点为心的 (2r+1)² 区块窗口（r=1 即 3×3）
	public static void addAround(Set<Long> out, BlockPos center, int radius)
	{
		if (center == null) return;
		int cx = center.getX() >> 4;
		int cz = center.getZ() >> 4;
		for (int dx = -radius; dx <= radius; dx++)
		{
			for (int dz = -radius; dz <= radius; dz++)
			{
				out.add(ChunkPos.asLong(cx + dx, cz + dz));
			}
		}
	}

	// 一批方块各自所在区块
	public static void addAll(Set<Long> out, Collection<BlockPos> positions)
	{
		if (positions == null) return;
		for (BlockPos p : positions)
		{
			if (p != null) out.add(of(p));
		}
	}

	// 方块矩形覆盖的全部区块（min/max 顺序不限）
	public static void addRect(Set<Long> out, int x1, int z1, int x2, int z2)
	{
		int minCX = Math.min(x1, x2) >> 4;
		int maxCX = Math.max(x1, x2) >> 4;
		int minCZ = Math.min(z1, z2) >> 4;
		int maxCZ = Math.max(z1, z2) >> 4;
		for (int cx = minCX; cx <= maxCX; cx++)
		{
			for (int cz = minCZ; cz <= maxCZ; cz++)
			{
				out.add(ChunkPos.asLong(cx, cz));
			}
		}
	}

	// 差量生效：desired 相比当前记账多出来的加票，少掉的撤票
	public static void apply(ServerLevel level, TicketType<ChunkPos> type,
			Map<BlockPos, Set<Long>> tickets, BlockPos owner, Set<Long> desired)
	{
		if (level == null || owner == null || desired == null) return;
		Set<Long> current = tickets.computeIfAbsent(owner, k -> new java.util.HashSet<>());

		for (Long l : desired)
		{
			if (current.add(l))
			{
				ChunkPos cp = new ChunkPos(l);
				level.getChunkSource().addRegionTicket(type, cp, 0, cp);
			}
		}

		Iterator<Long> it = current.iterator();
		while (it.hasNext())
		{
			Long l = it.next();
			if (!desired.contains(l))
			{
				ChunkPos cp = new ChunkPos(l);
				level.getChunkSource().removeRegionTicket(type, cp, 0, cp);
				it.remove();
			}
		}
	}

	// 释放该 owner 的全部区块
	public static void release(ServerLevel level, TicketType<ChunkPos> type,
			Map<BlockPos, Set<Long>> tickets, BlockPos owner)
	{
		Set<Long> set = tickets.remove(owner);
		if (set == null || level == null) return;
		for (Long l : set)
		{
			ChunkPos cp = new ChunkPos(l);
			level.getChunkSource().removeRegionTicket(type, cp, 0, cp);
		}
	}

	// 服务器停止时清空记账表
	public static void clearAll(Map<BlockPos, Set<Long>> tickets)
	{
		tickets.clear();
	}
}

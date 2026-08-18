// 快递盒区块强制加载：站点区常驻 + 快递员滚动窗口（按盒子记账，避免跨盒误释放）

package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.*;

public class DeliveryChunkLoader
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final TicketType<ChunkPos> DELIVERY_TICKET =
			TicketType.create("neo_sim:delivery", Comparator.comparingLong(ChunkPos::toLong));

	// 按快递盒记账：站点区区块 与 滚动窗口区块（防跨盒误释放）
	private static final Map<BlockPos, Set<Long>> boxTickets = new HashMap<>();
	private static final Map<BlockPos, Set<Long>> windowTickets = new HashMap<>();

	private DeliveryChunkLoader() {}

	// 站点区：快递盒所在区块 ±1，快递员在雇状态下常驻
	public static void registerBox(ServerLevel level, BlockPos box)
	{
		Set<Long> set = boxTickets.computeIfAbsent(box, b -> new HashSet<>());
		int cx = box.getX() >> 4;
		int cz = box.getZ() >> 4;
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dz = -1; dz <= 1; dz++)
			{
				ChunkPos cp = new ChunkPos(cx + dx, cz + dz);
				if (set.add(cp.toLong()))
				{
					level.getChunkSource().addRegionTicket(DELIVERY_TICKET, cp, 0, cp);
				}
			}
		}
	}

	// 滚动窗口：以快递员位置为中心、半径 deliveryChunkRadius 的区块列，跟随移动增删
	public static void setWindow(ServerLevel level, BlockPos box, BlockPos courierPos)
	{
		Set<Long> current = windowTickets.computeIfAbsent(box, b -> new HashSet<>());
		Set<Long> next = new HashSet<>();
		if (courierPos != null)
		{
			int cx = courierPos.getX() >> 4;
			int cz = courierPos.getZ() >> 4;
			int r = Config.DELIVERY_CHUNK_RADIUS.get();
			for (int dx = -r; dx <= r; dx++)
			{
				for (int dz = -r; dz <= r; dz++)
				{
					next.add(ChunkPos.asLong(cx + dx, cz + dz));
				}
			}
		}
		for (Long l : next)
		{
			if (current.add(l))
			{
				ChunkPos cp = new ChunkPos(l);
				level.getChunkSource().addRegionTicket(DELIVERY_TICKET, cp, 0, cp);
			}
		}
		Iterator<Long> it = current.iterator();
		while (it.hasNext())
		{
			Long l = it.next();
			if (!next.contains(l))
			{
				ChunkPos cp = new ChunkPos(l);
				level.getChunkSource().removeRegionTicket(DELIVERY_TICKET, cp, 0, cp);
				it.remove();
			}
		}
	}

	// 释放某快递盒的全部区块（解雇/拆除/任务取消）
	public static void releaseAll(ServerLevel level, BlockPos box)
	{
		Set<Long> bs = boxTickets.remove(box);
		if (bs != null)
		{
			for (Long l : bs)
			{
				ChunkPos cp = new ChunkPos(l);
				level.getChunkSource().removeRegionTicket(DELIVERY_TICKET, cp, 0, cp);
			}
		}
		Set<Long> ws = windowTickets.remove(box);
		if (ws != null)
		{
			for (Long l : ws)
			{
				ChunkPos cp = new ChunkPos(l);
				level.getChunkSource().removeRegionTicket(DELIVERY_TICKET, cp, 0, cp);
			}
		}
	}

	// 服务器停止/世界卸载时清空
	public static void clear()
	{
		boxTickets.clear();
		windowTickets.clear();
	}
}

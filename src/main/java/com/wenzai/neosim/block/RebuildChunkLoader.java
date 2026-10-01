package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
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

// 重建盒强制加载：整栋建筑覆盖的区块常驻加载，保证玩家离开也能继续重建。
// 按盒子记账（防跨盒误释放）；数量超过 rebuildMaxChunks 时只加载距盒子最近的若干区块。
public class RebuildChunkLoader
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final TicketType<ChunkPos> REBUILD_TICKET =
			TicketType.create("neo_sim:rebuild", Comparator.comparingLong(ChunkPos::toLong));

	private static final Map<BlockPos, Set<Long>> tickets = new HashMap<>();

	private RebuildChunkLoader()
	{
	}

	public static void register(ServerLevel level, BlockPos box, BlockPos min, BlockPos max)
	{
		Set<Long> set = tickets.computeIfAbsent(box, b -> new HashSet<>());

		int minCx = Math.min(min.getX(), max.getX()) >> 4;
		int maxCx = Math.max(min.getX(), max.getX()) >> 4;
		int minCz = Math.min(min.getZ(), max.getZ()) >> 4;
		int maxCz = Math.max(min.getZ(), max.getZ()) >> 4;

		List<ChunkPos> all = new ArrayList<>();
		for (int cx = minCx; cx <= maxCx; cx++)
		{
			for (int cz = minCz; cz <= maxCz; cz++)
			{
				all.add(new ChunkPos(cx, cz));
			}
		}

		int cap = Math.max(1, Config.REBUILD_MAX_CHUNKS.get());
		if (all.size() > cap)
		{
			int bcx = box.getX() >> 4;
			int bcz = box.getZ() >> 4;
			all.sort(Comparator.comparingLong(cp ->
			{
				long dx = cp.x - bcx;
				long dz = cp.z - bcz;
				return dx * dx + dz * dz;
			}));
			LOGGER.warn("NeoSim-RebuildChunkLoader: {} chunks needed at {} exceeds cap {}, keeping nearest {}",
					all.size(), box, cap, cap);
			all = new ArrayList<>(all.subList(0, cap));
		}

		int added = 0;
		for (ChunkPos cp : all)
		{
			if (set.add(cp.toLong()))
			{
				level.getChunkSource().addRegionTicket(REBUILD_TICKET, cp, 0, cp);
				added++;
			}
		}
		LOGGER.info("NeoSim-RebuildChunkLoader: registered {} chunks ({} new) for rebuild box {}",
				all.size(), added, box);
	}

	public static void release(ServerLevel level, BlockPos box)
	{
		Set<Long> set = tickets.remove(box);
		if (set == null) return;
		for (Long l : set)
		{
			ChunkPos cp = new ChunkPos(l);
			level.getChunkSource().removeRegionTicket(REBUILD_TICKET, cp, 0, cp);
		}
		LOGGER.info("NeoSim-RebuildChunkLoader: released {} chunks for rebuild box {}", set.size(), box);
	}

	public static void clear()
	{
		tickets.clear();
	}
}

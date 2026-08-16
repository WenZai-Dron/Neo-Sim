package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public class TerraformChunkLoader
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final TicketType<ChunkPos> TERRAFORM_TICKET =
			TicketType.create("neo_sim:terraform", Comparator.comparingLong(ChunkPos::toLong));

	// 已被某整地任务加载的区块（防重复注册/误释放）
	private static final Set<Long> loaded = new HashSet<>();

	private TerraformChunkLoader() {}

	// 为整地地块覆盖的所有区块注册
	public static void registerForPlot(ServerLevel level, TerraformPersistence.TerraformRecord record)
	{
		int minCX = Math.min(record.minX(), record.maxX()) >> 4;
		int maxCX = Math.max(record.minX(), record.maxX()) >> 4;
		int minCZ = Math.min(record.minZ(), record.maxZ()) >> 4;
		int maxCZ = Math.max(record.minZ(), record.maxZ()) >> 4;

		for (int cx = minCX; cx <= maxCX; cx++)
		{
			for (int cz = minCZ; cz <= maxCZ; cz++)
			{
				ChunkPos cp = new ChunkPos(cx, cz);
				if (loaded.add(cp.toLong()))
				{
					level.getChunkSource().addRegionTicket(TERRAFORM_TICKET, cp, 0, cp);
				}
			}
		}
		LOGGER.debug("NeoSim-TerraformChunkLoader: {} chunks loaded for terraform at {}", loaded.size(), record.boxPos());
	}

	// 释放该整地任务加载的区块
	public static void releaseForPlot(ServerLevel level, TerraformPersistence.TerraformRecord record)
	{
		int minCX = Math.min(record.minX(), record.maxX()) >> 4;
		int maxCX = Math.max(record.minX(), record.maxX()) >> 4;
		int minCZ = Math.min(record.minZ(), record.maxZ()) >> 4;
		int maxCZ = Math.max(record.minZ(), record.maxZ()) >> 4;

		for (int cx = minCX; cx <= maxCX; cx++)
		{
			for (int cz = minCZ; cz <= maxCZ; cz++)
			{
				ChunkPos cp = new ChunkPos(cx, cz);
				if (loaded.remove(cp.toLong()))
				{
					level.getChunkSource().removeRegionTicket(TERRAFORM_TICKET, cp, 0, cp);
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

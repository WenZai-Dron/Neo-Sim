package com.wenzai.neosim.container;

import com.wenzai.neosim.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

// 把「一个方块位置」解析成「一个容器句柄」：Capability 优先，Container 回退
// 不 import 任何模组类：只要目标模组按标准注册了 Capabilities.ItemHandler.BLOCK 就自动兼容
public final class ContainerFinder
{
	private ContainerFinder()
	{
	}

	// 本刻的解析结果（null 也存：空位置不必重复探）
	private static final Map<BlockPos, ContainerHandle> CACHE = new HashMap<>();

	// 同一份库存被多个位置暴露时（3×3×3 等多方块容器）只认第一次的句柄，避免重复计数
	private static final Map<IItemHandler, ContainerHandle> BY_HANDLER = new IdentityHashMap<>();

	private static ServerLevel cacheLevel;
	private static long cacheTick = Long.MIN_VALUE;

	// 黑名单解析结果：配置是字符串列表，扫描路径每格都解析太贵，按列表实例缓存
	private static List<? extends String> blacklistRaw;
	private static final Set<Block> BLACKLIST = new HashSet<>();

	@Nullable
	public static ContainerHandle resolve(ServerLevel level, BlockPos pos)
	{
		if (!level.isLoaded(pos)) return null;

		// 没有方块实体一定不是容器：省掉 capability 查询（链式扫描会探到大量空气 / 石头）
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null) return null;
		if (blacklisted(level.getBlockState(pos).getBlock())) return null;

		// 同一游戏刻内同一位置只解析一次；跨刻自动失效（容器被拆后旧句柄会往空气里存货）
		roll(level);
		if (CACHE.containsKey(pos)) return CACHE.get(pos);

		IItemHandler handler = findHandler(level, pos, be);
		if (handler == null)
		{
			CACHE.put(pos, null);
			return null;
		}

		// 同一个 handler 实例只包一个句柄：多方块容器的各个面拿到的若是同一份库存，只算一次
		ContainerHandle handle = BY_HANDLER.get(handler);
		if (handle == null)
		{
			handle = new ContainerHandle(pos, handler);
			BY_HANDLER.put(handler, handle);
		}
		CACHE.put(pos, handle);
		return handle;
	}

	// 跨刻（或跨维度）时清空本刻缓存
	private static void roll(ServerLevel level)
	{
		long now = level.getGameTime();
		if (level == cacheLevel && now == cacheTick) return;
		cacheLevel = level;
		cacheTick = now;
		CACHE.clear();
		BY_HANDLER.clear();
	}

	// 解析三件套：无方向 → 六面 → Container 回退（InvWrapper 官方标注非通用用途，同样包 try）
	@Nullable
	private static IItemHandler findHandler(ServerLevel level, BlockPos pos, BlockEntity be)
	{
		IItemHandler handler = tryCapability(level, pos, null);
		for (Direction d : Direction.values())
		{
			if (handler != null) break;
			handler = tryCapability(level, pos, d);
		}

		// InvWrapper 每次都是新实例，所以这条回退路径上的去重失效（已知限制）
		if (handler == null && be instanceof Container container)
		{
			try
			{
				handler = new InvWrapper(container);
			}
			catch (RuntimeException e)
			{
				return null;
			}
		}
		return handler;
	}

	@Nullable
	private static IItemHandler tryCapability(ServerLevel level, BlockPos pos, @Nullable Direction side)
	{
		try
		{
			return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static boolean blacklisted(Block block)
	{
		List<? extends String> raw;
		try
		{
			raw = Config.CONTAINER_BLACKLIST.get();
		}
		catch (IllegalStateException notLoaded)
		{
			return false;
		}
		if (raw != blacklistRaw)
		{
			blacklistRaw = raw;
			BLACKLIST.clear();
			for (String entry : raw)
			{
				if (entry == null) continue;
				ResourceLocation id = ResourceLocation.tryParse(entry.trim());
				if (id == null) continue;
				BuiltInRegistries.BLOCK.getOptional(id).ifPresent(BLACKLIST::add);
			}
		}
		return BLACKLIST.contains(block);
	}

	// 配置热重载：NeoSim.onConfigReloading 调它，下次扫描重建黑名单与本刻缓存
	public static void invalidateCache()
	{
		blacklistRaw = null;
		BLACKLIST.clear();
		CACHE.clear();
		BY_HANDLER.clear();
		cacheTick = Long.MIN_VALUE;
	}
}

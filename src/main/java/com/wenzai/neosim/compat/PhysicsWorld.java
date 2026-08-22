package com.wenzai.neosim.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

// 物理感知世界门面：自动化建筑引擎的唯一入口。
// 无任何模组硬依赖——Sable 相关调用全部守卫在 isLoaded("sable") 内并 try/catch 降级。
// 注意：物理化结构上的方块读写集成已停用（见 load()），仅保留投影/判定/NPC 跟踪等辅助能力。
public final class PhysicsWorld
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static volatile IPhysicsAdapter adapter;
	private static boolean tried = false;

	private PhysicsWorld()
	{
	}

	private static IPhysicsAdapter adapter()
	{
		IPhysicsAdapter a = adapter;
		if (a != null) return a;
		if (!tried)
		{
			tried = true;
			a = load();
			adapter = a;
		}
		return a;
	}

	private static IPhysicsAdapter load()
	{
		// 物理化结构上的建造集成已停用：任何对子世界的访问都会触发 Sable 卸载队列自旋（保存卡死）。
		// 见 isInSubLevel 的拒绝逻辑。恢复下方加载逻辑前请先修复该 Sable 并发问题。
		return null;
	}

	// 物理感知读取：命中结构返回结构方块，否则主世界状态
	public static BlockState getBlockState(ServerLevel level, BlockPos pos)
	{
		IPhysicsAdapter a = adapter();
		if (a != null)
		{
			BlockState s = a.getBlockState(level, pos);
			if (s != null) return s;
		}
		return level.getBlockState(pos);
	}

	// 物理感知写入：命中结构写入子世界，否则原版
	public static boolean setBlock(ServerLevel level, BlockPos pos, BlockState state, int flags)
	{
		IPhysicsAdapter a = adapter();
		if (a != null && a.setBlock(level, pos, state, flags)) return true;
		return level.setBlock(pos, state, flags);
	}

	// 物理感知空位判定
	public static boolean isEmptyBlock(ServerLevel level, BlockPos pos)
	{
		return getBlockState(level, pos).isAir();
	}

	// 物理感知拆除：命中结构从子世界移除，否则原版
	public static boolean destroyBlock(ServerLevel level, BlockPos pos, boolean drop)
	{
		IPhysicsAdapter a = adapter();
		if (a != null && a.destroyBlock(level, pos)) return true;
		return level.destroyBlock(pos, drop);
	}

	// 当前是否启用物理兼容（调试用）
	public static boolean isActive()
	{
		return adapter() != null;
	}

	// 局部坐标 → 世界坐标（无子世界时原样返回；用于 NPC 生成/导航等世界坐标场景）
	public static BlockPos toWorld(ServerLevel level, BlockPos pos)
	{
		try
		{
			if (ModList.get().isLoaded("sable"))
			{
				return SablePhysicsAdapter.toWorld(level, pos);
			}
		}
		catch (Throwable t)
		{
			// 降级原版
		}
		return pos;
	}

	// pos 是否位于某子世界 plot 网格内（物理化结构上）
	public static boolean isInSubLevel(ServerLevel level, BlockPos pos)
	{
		try
		{
			if (ModList.get().isLoaded("sable"))
			{
				return SablePhysicsAdapter.isInSubLevel(level, pos);
			}
		}
		catch (Throwable t)
		{
			// 视为不在子世界
		}
		return false;
	}

	// 把 NPC 登记进子世界跟踪（模盒在结构上时，NPC 随船体站立）
	public static void attachNpc(ServerLevel level, Entity npc, BlockPos localPos)
	{
		try
		{
			if (ModList.get().isLoaded("sable"))
			{
				SablePhysicsAdapter.attachToSubLevel(level, npc, localPos);
			}
		}
		catch (Throwable t)
		{
			// 降级原版
		}
	}
}

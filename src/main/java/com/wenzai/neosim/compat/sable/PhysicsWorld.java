package com.wenzai.neosim.compat.sable;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

// 外部模组兼容门面：自动化建筑引擎的唯一入口。
// 无任何模组硬依赖——适配器由 PhysicsAdapterRegistry 按模组加载状态反射加载，
// 新增兼容模组只需实现 IPhysicsAdapter 并在注册表 init() 追加一行。
// 注意：结构内方块读写集成已停用（见 BLOCK_IO_ENABLED），仅保留投影/判定/NPC 跟踪等辅助能力。
public final class PhysicsWorld
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// 方块读写总开关：false = 停用。
	// 原因：任何对 Sable 子世界的访问都会触发卸载队列自旋（保存卡死），见 关于兼容Sable.md。
	// Sable 修复该并发问题后改为 true 即可恢复（无需改业务代码）。
	private static final boolean BLOCK_IO_ENABLED = false;

	private PhysicsWorld()
	{
	}

	// 结构感知读取：命中结构返回结构方块，否则主世界状态
	public static BlockState getBlockState(ServerLevel level, BlockPos pos)
	{
		IPhysicsAdapter a = blockIoAdapter();
		if (a != null)
		{
			BlockState s = a.getBlockState(level, pos);
			if (s != null) return s;
		}
		return level.getBlockState(pos);
	}

	// 结构感知写入：命中结构写入，否则原版
	public static boolean setBlock(ServerLevel level, BlockPos pos, BlockState state, int flags)
	{
		IPhysicsAdapter a = blockIoAdapter();
		if (a != null && a.setBlock(level, pos, state, flags)) return true;
		return level.setBlock(pos, state, flags);
	}

	// 结构感知空位判定
	public static boolean isEmptyBlock(ServerLevel level, BlockPos pos)
	{
		return getBlockState(level, pos).isAir();
	}

	// 结构感知拆除：命中结构移除，否则原版
	public static boolean destroyBlock(ServerLevel level, BlockPos pos, boolean drop)
	{
		IPhysicsAdapter a = blockIoAdapter();
		if (a != null && a.destroyBlock(level, pos)) return true;
		return level.destroyBlock(pos, drop);
	}

	// 当前是否启用外部模组兼容（调试用）：有任一适配器处于激活状态
	public static boolean isActive()
	{
		return !PhysicsAdapterRegistry.activeAdapters().isEmpty();
	}

	// 局部坐标 → 世界坐标（不属于任何结构时原样返回；用于 NPC 生成/导航等世界坐标场景）。
	// 由第一个"认领"该 pos 的适配器负责投影，多模组互不干扰。
	public static BlockPos toWorld(ServerLevel level, BlockPos pos)
	{
		for (IPhysicsAdapter a : PhysicsAdapterRegistry.activeAdapters())
		{
			try
			{
				if (a.isOnStructure(level, pos)) return a.toWorld(level, pos);
			}
			catch (Throwable t)
			{
				// 单个适配器异常不阻断其他模组，降级原版
				LOGGER.warn("NeoSim-PhysicsWorld: adapter {} toWorld failed, skipped", a.modId(), t);
			}
		}
		return pos;
	}

	// pos 是否位于某模组管理的结构（如物理化结构）上
	public static boolean isOnStructure(ServerLevel level, BlockPos pos)
	{
		for (IPhysicsAdapter a : PhysicsAdapterRegistry.activeAdapters())
		{
			try
			{
				if (a.isOnStructure(level, pos)) return true;
			}
			catch (Throwable t)
			{
				// 单个适配器异常不阻断其他模组，视为不在其结构上
				LOGGER.warn("NeoSim-PhysicsWorld: adapter {} isOnStructure failed, skipped", a.modId(), t);
			}
		}
		return false;
	}

	// 把 NPC 登记进结构跟踪（模盒在结构上时，NPC 随结构移动）。
	// 通知所有可用适配器，各适配器自行判定是否命中其结构。
	public static void attachNpc(ServerLevel level, Entity npc, BlockPos localPos)
	{
		for (IPhysicsAdapter a : PhysicsAdapterRegistry.activeAdapters())
		{
			try
			{
				a.attachNpc(level, npc, localPos);
			}
			catch (Throwable t)
			{
				// 单个适配器异常不阻断其他模组，跳过该适配器
				LOGGER.warn("NeoSim-PhysicsWorld: adapter {} attachNpc failed, skipped", a.modId(), t);
			}
		}
	}

	// 方块读写适配器（受总开关控制；当前停用恒为 null）
	private static IPhysicsAdapter blockIoAdapter()
	{
		if (!BLOCK_IO_ENABLED) return null;
		return PhysicsAdapterRegistry.blockIoAdapter();
	}
}

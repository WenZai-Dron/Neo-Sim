package com.wenzai.neosim.compat.sable;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

// 外部模组兼容适配接口：只依赖 Minecraft 类型，Neo-Sim 引擎只认识这个接口。
// 每个需要兼容的外部模组（Sable / Create Simulated 等）提供一个实现，在类静态块中
// 经 PhysicsAdapterRegistry.register() 注册；未安装的模组其适配器类永远不会被加载。
// 返回 null / false 表示"该位置不属于该模组管理的结构，请走原版逻辑"。
public interface IPhysicsAdapter
{
	// 适配器对应的模组 id（注册去重与日志用）
	String modId();

	// 适配器是否可用（对应模组已加载且功能正常）
	boolean isAvailable();

	// 是否允许方块读写（对结构内方块的读写集成）。
	// 具体模组可按自身稳定情况停用（如 Sable 见 关于兼容Sable.md），默认开启。
	default boolean isBlockIoSupported()
	{
		return true;
	}

	// 结构感知读取：pos 处为结构方块则返回该方块，否则返回 null
	@Nullable
	default BlockState getBlockState(ServerLevel level, BlockPos pos)
	{
		return null;
	}

	// 结构感知写入：pos 属于（或紧贴）结构则写入并返回 true，否则 false
	default boolean setBlock(ServerLevel level, BlockPos pos, BlockState state, int flags)
	{
		return false;
	}

	// 结构感知拆除：pos 处为结构方块则移除并返回 true，否则 false
	default boolean destroyBlock(ServerLevel level, BlockPos pos)
	{
		return false;
	}

	// 局部坐标 → 世界坐标（不属于任何结构时原样返回；用于 NPC 生成/导航等世界坐标场景）
	default BlockPos toWorld(ServerLevel level, BlockPos pos)
	{
		return pos;
	}

	// pos 是否位于该模组管理的结构（如物理化结构）内
	default boolean isOnStructure(ServerLevel level, BlockPos pos)
	{
		return false;
	}

	// 把 NPC 登记进结构跟踪（模盒在结构上时，NPC 随结构移动）
	default void attachNpc(ServerLevel level, Entity npc, BlockPos localPos)
	{
	}
}

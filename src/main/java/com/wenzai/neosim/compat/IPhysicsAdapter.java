package com.wenzai.neosim.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

// 物理世界适配接口：只依赖 Minecraft 类型，Neo-Sim 引擎只认识这个接口。
// 返回 null / false 表示"该位置不属于物理结构，请走原版逻辑"。
public interface IPhysicsAdapter
{
	// 适配器是否可用（对应模组已加载）
	boolean isAvailable();

	// 物理感知读取：pos 处为结构方块则返回该方块，否则返回 null
	BlockState getBlockState(ServerLevel level, BlockPos pos);

	// 物理感知写入：pos 属于（或紧贴）物理结构则写入子世界并返回 true，否则 false
	boolean setBlock(ServerLevel level, BlockPos pos, BlockState state, int flags);

	// 物理感知拆除：pos 处为结构方块则从子世界移除并返回 true，否则 false
	boolean destroyBlock(ServerLevel level, BlockPos pos);
}

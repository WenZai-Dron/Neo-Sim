package com.wenzai.neosim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;

// 重建模盒：放在建筑控制箱旁，读取相邻箱子材料，自动把蓝图应有却成了空气的方块补回去。
// 无 GUI、不需要 NPC；右键只在聊天栏报一行状态。
public class RebuildBox extends Block
{
	public RebuildBox(Properties properties)
	{
		super(properties);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack)
	{
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!level.isClientSide && level instanceof ServerLevel sl && placer instanceof Player player)
		{
			RebuildBoxEngine.onPlaced(sl, pos, player);
		}
	}

	@Override
	public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
									   boolean willHarvest, FluidState fluid)
	{
		if (!level.isClientSide && level instanceof ServerLevel sl)
		{
			RebuildBoxEngine.removeAt(sl, pos);
		}
		return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
	}

	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
											BlockHitResult hitResult)
	{
		if (!level.isClientSide && level instanceof ServerLevel sl)
		{
			RebuildBoxEngine.sendStatus(sl, pos, player);
		}
		return InteractionResult.sidedSuccess(level.isClientSide());
	}
}

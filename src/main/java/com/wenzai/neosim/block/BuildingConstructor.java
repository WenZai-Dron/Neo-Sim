package com.wenzai.neosim.block;

import com.wenzai.neosim.client.ClientBlockInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class  BuildingConstructor extends Block
{
	public BuildingConstructor(Properties properties)
	{
		super(properties);
	}

	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult)
	{
		if (level.isClientSide)
		{
			// 客户端逻辑：专用服务器此分支永不执行，不加载任何客户端类
			ClientBlockInteractions.openBuildingConstructor(pos);
		}

		return InteractionResult.sidedSuccess(level.isClientSide());
	}
}

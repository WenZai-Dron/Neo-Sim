package com.wenzai.neosim.block;

import com.wenzai.neosim.client.ClientBlockInteractions;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

public class FarmingBox extends Block
{
	public FarmingBox(Properties properties)
	{
		super(properties);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack)
	{
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!level.isClientSide && placer instanceof Player player)
		{
			WorkPlotEngine.createFarmPlot((ServerLevel) level, pos, player.getName().getString());
		}
	}

	@Override
	public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid)
	{
		if (!level.isClientSide && level instanceof ServerLevel sl)
		{
			WorkPlotEngine.removePlotAt(sl, pos);
		}
		return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
	}

	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult)
	{
		boolean interactive = isInteractive(pos);
		if (interactive && level.isClientSide)
		{
			ClientBlockInteractions.openFarmingBox(pos);
		}
		return interactive ? InteractionResult.sidedSuccess(level.isClientSide())
				: InteractionResult.PASS;
	}

	// 客户端判定：有盒记录即可打开GUI
	private boolean isInteractive(BlockPos pos)
	{
		if (FMLEnvironment.dist != Dist.CLIENT) return false;
		return ClientBlockInteractions.hasFarmingBoxRecord(pos);
	}
}

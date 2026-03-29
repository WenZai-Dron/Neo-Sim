package com.wenzai.neosim.block;

import com.wenzai.neosim.gui.Run;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class BuildingConstructor extends Block
{
    public BuildingConstructor(Properties properties)
    {
        super(properties);
    }

    public static void OpenGuiRun()
    {
        Minecraft.getInstance().setScreen(new Run(Component.translatable("gui.neosim.run.title")));
    }

    //@Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos , Player player, InteractionHand hand, BlockHitResult hitResult)
    {
        if (level.isClientSide)
        {
            OpenGuiRun();
        }

        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}

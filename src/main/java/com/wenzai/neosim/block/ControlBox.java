package com.wenzai.neosim.block;

import com.wenzai.neosim.client.gui.ControlBoxGui;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class ControlBox extends Block
{
    public ControlBox(Properties properties)
    {
        super(properties);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult)
    {
        // 可交互条件：有文件记录，且无进行中的建造任务（可能有问题）
        boolean interactive = isInteractive(pos);
        if (interactive && level.isClientSide)
        {
            Minecraft.getInstance().setScreen(new ControlBoxGui(pos));
        }

        return interactive ? InteractionResult.sidedSuccess(level.isClientSide())
                : InteractionResult.PASS;
    }

    // 客户端判定：有文件记录，且无进行中的建造任务（可能有问题）
    private boolean isInteractive(BlockPos pos)
    {
        if (com.wenzai.neosim.building.ConstructionEngine.findTask(pos) != null) return false;
        return ControlBoxGui.hasRecord(pos);
    }
}

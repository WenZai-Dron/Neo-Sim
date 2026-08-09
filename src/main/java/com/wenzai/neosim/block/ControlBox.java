package com.wenzai.neosim.block;

import com.wenzai.neosim.client.ClientBlockInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.loading.FMLEnvironment;

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
            // 客户端逻辑：专用服务器此分支永不执行，不加载任何客户端类
            ClientBlockInteractions.openControlBox(pos);
        }

        return interactive ? InteractionResult.sidedSuccess(level.isClientSide())
                : InteractionResult.PASS;
    }

    // 客户端判定：有文件记录，且无进行中的建造任务（可能有问题）
    private boolean isInteractive(BlockPos pos)
    {
        if (com.wenzai.neosim.building.ConstructionEngine.findTask(pos) != null) return false;
        if (FMLEnvironment.dist != net.neoforged.api.distmarker.Dist.CLIENT) return false;
        return ClientBlockInteractions.hasControlBoxRecord(pos);
    }
}

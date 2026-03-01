package com.wenzai.neosim.block;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.gui.Run;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = NeoSim.MOD_ID, value = Dist.CLIENT)
public class BlockInteractionHandler
{

    // 点击方块
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        // 获取点击的方块状态
        BlockPos clickPos = event.getPos();
        BlockState clickedBlock = event.getLevel().getBlockState(clickPos);

        // 判断点击：建筑模盒
        if (clickedBlock.is(ModBlocks.BUILDING_CONSTRUCTOR.get()))
        {
            // 取消默认右键行为，避免触发方块默认交互，如放置方块
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);

            // 打开Run
            Minecraft.getInstance().setScreen(new Run());
        }
    }
}
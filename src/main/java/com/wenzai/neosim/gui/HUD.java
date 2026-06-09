package com.wenzai.neosim.gui;

import com.wenzai.neosim.ClientDataHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

@OnlyIn(Dist.CLIENT)
public class HUD
{
    @SubscribeEvent
    public void renderHUD(RenderGuiEvent.Post event)
    {
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics guiGraphics = event.getGuiGraphics();

        if (mc.level == null || mc.player == null)
        {
            return;
        }

        LocalPlayer player = mc.player;

        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        // 获取客户端缓存的数据
        ClientDataHolder data = ClientDataHolder.getInstance();

        guiGraphics.drawString(mc.font, "模式: " + data.getMode(), 10, 10, 0xFFFFFF);
    }
}

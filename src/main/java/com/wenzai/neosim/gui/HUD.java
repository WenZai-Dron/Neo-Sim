package com.wenzai.neosim.gui;

import com.wenzai.neosim.ClientDataHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
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

        /* int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight(); 以后可能会用*/

        // 获取客户端缓存的数据
        ClientDataHolder data = ClientDataHolder.getInstance();

        String modeStr = switch (data.getMode())
        {
            case 1 -> Component.translatable("gui.neosim.run.buttonNormal").getString();
            case 2 -> Component.translatable("gui.neosim.run.buttonCreative").getString();
            case 3 -> Component.translatable("gui.neosim.run.buttonHardcore").getString();
            default -> "Null";
        };

        String singleOrMultiStr = switch (data.getSingleOrMulti())
        {
            case 1 -> Component.translatable("gui.neosim.run.buttonSingle").getString();
            case 2 -> Component.translatable("gui.neosim.run.buttonMulti").getString();
            default -> "Null";
        };

        String dayOfWeekStr = switch (data.getDayOfWeek())
        {
            case 0 -> Component.translatable("gui.neosim.hud.sunday").getString();
            case 1 -> Component.translatable("gui.neosim.hud.monday").getString();
            case 2 -> Component.translatable("gui.neosim.hud.tuesday").getString();
            case 3 -> Component.translatable("gui.neosim.hud.wednesday").getString();
            case 4 -> Component.translatable("gui.neosim.hud.thursday").getString();
            case 5 -> Component.translatable("gui.neosim.hud.friday").getString();
            case 6 -> Component.translatable("gui.neosim.hud.saturday").getString();
            default -> "Null";
        };

        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(0.8f, 0.8f, 1.0f);
        guiGraphics.drawString(mc.font, Component.translatable("gui.neosim.hud.mode").getString() + ": " + modeStr, 10, 10, 0xFFFFFF);
        guiGraphics.drawString(mc.font, Component.translatable("gui.neosim.hud.singleOrMulti").getString() + ": " + singleOrMultiStr, 110, 10, 0xFFFFFF);
        guiGraphics.drawString(mc.font, dayOfWeekStr, 210, 10, 0xFFFFFF);
        guiGraphics.drawString(mc.font, Component.translatable("gui.neosim.hud.day").getString() + ": " + data.getDay(), 310, 10, 0xFFFFFF);
        guiGraphics.drawString(mc.font, Component.translatable("gui.neosim.hud.credit").getString() + ": " + data.getCredit(), 410, 10, 0xFFFFFF);
        guiGraphics.pose().popPose();
    }
}

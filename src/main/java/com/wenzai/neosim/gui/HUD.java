package com.wenzai.neosim.gui;

import com.wenzai.neosim.ClientDataHolder;
import com.wenzai.neosim.NeoSimClient;
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

        int runTimer = NeoSimClient.getOpenRunGuiTimer();

        // 不渲染
        if (data.getMode() == 0 && runTimer <= 0)
        {
            return;
        }

        // 显示倒计时
        if (data.getMode() == 0 && runTimer > 0)
        {
            String countdown = ((runTimer + 19) / 20) + "";
            guiGraphics.drawString(mc.font, countdown, 10, 10, 0xFFFFFF);
            return;
        }

        String modeStr = switch (data.getMode())
        {
            case 1 -> Component.translatable("gui.neosim.run.buttonNormal").getString();
            case 2 -> Component.translatable("gui.neosim.run.buttonCreative").getString();
            case 3 -> Component.translatable("gui.neosim.run.buttonHardcore").getString();
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

        // 获取并格式化游戏内时间
        long dayTime = mc.level.getDayTime() % 24000;
        long adjustedTicks = (dayTime + 6000) % 24000;
        int hour = (int) (adjustedTicks / 1000);
        int minute = (int) ((adjustedTicks % 1000) * 60 / 1000);
        String timeStr = String.format("%02d:%02d", hour, minute);

        guiGraphics.drawString(mc.font, timeStr + " - "
                + dayOfWeekStr + " - "
                + "第" + data.getDay() + "天" + " - "
                + Component.translatable("gui.neosim.hud.population").getString() + ": " + data.getPopulation() + " - "
                + Component.translatable("gui.neosim.hud.credit").getString() + ": " + data.getCredit()
                , 10, 10, 0xFFFFFF);
    }
}

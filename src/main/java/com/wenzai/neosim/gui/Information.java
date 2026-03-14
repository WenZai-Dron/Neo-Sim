package com.wenzai.neosim.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class Information extends Screen
{
    protected Information(Component title)
    {
        super(title);
    }

    public Information()
    {
        super(Component.translatable("gui.neosim.information.super"));
    }

    short population = 0;
    short Day = 0;
    int Date = 1;

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        // 先渲染背景
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        Font font = this.font;

        guiGraphics.drawString(
                font,
                Component.translatable("gui.neosim.information.population" + "=" + population),
                5, 5,
                0x00000000,
                false
        );
    }
}

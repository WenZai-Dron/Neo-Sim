package com.wenzai.neosim.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class City extends Screen
{
    private Button buttonChoose;

    public City()
    {
        super(Component.translatable("gui.neosim.city.title"));
    }

    // 初始化按钮组件
    @Override
    protected void init()
    {
        Button buttonAdd;

        int btnW = this.width / 3 + this.width / 7;
        int btnH = this.height / 13;
        int btnX = this.width / 2 - btnW / 2;

        buttonAdd = Button.builder(Component.translatable("gui.neosim.city.add"), Button -> {
            onClose();
            Minecraft.getInstance().setScreen(new Add());
        })
                .pos(btnX, this.height / 2 - this.height / 7)
                .size(btnW, btnH)
                .build();
        this.addRenderableWidget(buttonAdd);

        buttonChoose = Button.builder(Component.translatable("gui.neosim.city.choose"), Button -> {
            onClose();
            Minecraft.getInstance().setScreen(new Choose());
        })
                .pos(btnX, this.height / 2 + this.height / 7)
                .size(btnW, btnH)
                .build();
        this.addRenderableWidget(buttonChoose);
    }

    // 渲染组件
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        // 渲染背景
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // 无城市时，选择按钮不可点击
        if (buttonChoose != null)
        {
            buttonChoose.active = Choose.hasAnyCity();
        }

        // 调用父类渲染
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}

package com.wenzai.neosim.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class City extends Screen
{
    public City()
    {
        super(Component.translatable("gui.neosim.city.title"));
    }

    // 初始化按钮组件
    @Override
    protected void init()
    {
        Button buttonAdd, buttonChoose;

        int width = this.width / 3;
        int height = this.height / 2;

        buttonAdd = Button.builder(Component.translatable("gui.neosim.city.add"), Button -> {
            onClose();
            Minecraft.getInstance().setScreen(new CityAdd());
        })
                .pos(width - 35, height - 40)
                .size(width + 70, 20)
                .build();
        this.addRenderableWidget(buttonAdd);

        buttonChoose = Button.builder(Component.translatable("gui.neosim.city.choose"), Button -> {
            onClose();
            // 还没写
        })
                .pos(width - 35, height + 40)
                .size(width + 70, 20)
                .build();
        this.addRenderableWidget(buttonChoose);
    }

    // 渲染组件
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        // 渲染背景
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // 调用父类渲染
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}

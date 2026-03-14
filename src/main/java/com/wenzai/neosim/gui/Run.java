package com.wenzai.neosim.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class Run extends Screen
{
    public Run(Component title)
    {
        super(title);
    }

    public Run()
    {
        super(Component.translatable("gui.neosim.run.title"));
    }
    
    short[] Mode = new short[1];

    @Override
    protected void init()
    {
        // 初始化
        super.init();

        // Button1：普通模式 / Normal Mode
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.neosim.run.button1"),
                        button ->
                        {
                            Mode[0] = 1;
                            onClose();
                        }
                        )
                .pos(140 / 2 - 100, 85)
                .size(200, 20)
                .build());

        // Button2：创造模式 / Creative Mode
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.neosim.run.button2"),
                        button ->
                        {
                            Mode[0] = 2;
                            onClose();
                        }
                        )
                .pos(140 / 2 - 100, 145)
                .size(200, 20)
                .build());

        // Button3：硬核模式 / Hardcore Mode
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.neosim.run.button3"),
                        button ->
                        {
                            Mode[0] = 3;
                            onClose();
                        }
                        )
                .pos(140 / 2 - 100, 205)
                .size(200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        // 先渲染背景
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        Font font = this.font;

        // tip1：作者也不知道会干嘛:) / Author doesn't know what will happen:)
        guiGraphics.drawString(
                font,
                Component.translatable("gui.neosim.run.tip1"),
                150, 110,
                0xFFFFFF00,
                false
        );

        // tip2：无限资金和资源 / Author doesn't know how to translate:(
        guiGraphics.drawString(
                font,
                Component.translatable("gui.neosim.run.tip2"),
                150, 170,
                0xFFFFFF00,
                false
        );

        // tip3：建筑需要所有方块 / Author doesn't know how to translate:(
        guiGraphics.drawString(
                font,
                Component.translatable("gui.neosim.run.tip3"),
                150, 230,
                0xFFFFFF00,
                false
        );
    }
}

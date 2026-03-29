package com.wenzai.neosim.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class Run extends Screen
{
    public Run()
    {
        super(Component.translatable("gui.neosim.run.title"));
    }

    short[] Mode = new short[1];
    short[] SingeOrMuti = new short[1];

    Button button1, button2, button3, button4, button5, button6;

    Component tip1 = Component.translatable("gui.neosim.run.tip1"),
            tip2 = Component.translatable("gui.neosim.run.tip2"),
            tip3 = Component.translatable("gui.neosim.run.tip3");

    Font font = this.font;

    public Run(Component title)
    {
        super(title);
    }

    // 初始化按钮组件
    @Override
    protected void init()
    {
        this.button1 = new Button.Builder(Component.translatable("gui.neosim.run.button1"), Button -> {
            Mode[0] = 1;
            onClose();
        })
            .pos(90, 85)
            .size(135, 20)
            .build();
        this.addWidget(this.button1);
        super.init();

        this.button2 = new Button.Builder(Component.translatable("gui.neosim.run.button2"), Button -> {
            Mode[0] = 2;
            onClose();
        })
                .pos(90, 145)
                .size(135, 20)
                .build();
        this.addWidget(this.button2);
        super.init();

        this.button3 = new Button.Builder(Component.translatable("gui.neosim.run.button3"), Button -> {
            Mode[0] = 3;
            onClose();
        })
                .pos(90, 205)
                .size(135, 20)
                .build();
        this.addWidget(this.button3);
        super.init();

        this.button1 = new Button.Builder(Component.translatable("gui.neosim.run.button4"), Button -> {
            SingeOrMuti[0] = 1;
            onClose();
        })
                .pos(265, 85)
                .size(135, 20)
                .build();
        this.addWidget(this.button4);
        super.init();

        this.button1 = new Button.Builder(Component.translatable("gui.neosim.run.button5"), Button -> {
            SingeOrMuti[0] = 2;
            onClose();
        })
                .pos(265, 145)
                .size(135, 20)
                .build();
        this.addWidget(this.button5);
        super.init();

        this.button1 = new Button.Builder(Component.translatable("gui.neosim.run.button6"), Button -> {
            onClose();
        })
                .pos(265, 205)
                .size(135, 20)
                .build();
        this.addWidget(this.button6);

        super.init();
    }

    // 渲染组件
    @Override
    public void render(GuiGraphics GuiGraphics, int MouseX, int MouseY, float PartialTick)
    {
        this.renderBackground(GuiGraphics, MouseX, MouseY, PartialTick);

        // 设置正常渲染时色彩和透明度
        GuiGraphics.setColor(1, 1, 1, 1);

        GuiGraphics.drawCenteredString(font, tip1, 100, 110, 0xFFFFFF00);
        GuiGraphics.drawCenteredString(font, tip2, 100, 170, 0xFFFFFF00);
        GuiGraphics.drawCenteredString(font, tip3, 100, 230, 0xFFFFFF00);

        this.button1.render(GuiGraphics, MouseX, MouseY, PartialTick);
        this.button2.render(GuiGraphics, MouseX, MouseY, PartialTick);
        this.button3.render(GuiGraphics, MouseX, MouseY, PartialTick);
        this.button4.render(GuiGraphics, MouseX, MouseY, PartialTick);
        this.button5.render(GuiGraphics, MouseX, MouseY, PartialTick);
        this.button6.render(GuiGraphics, MouseX, MouseY, PartialTick);

        super.render(GuiGraphics, MouseX, MouseY, PartialTick);
    }
}

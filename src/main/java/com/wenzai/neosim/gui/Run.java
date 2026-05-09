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

    public short[] Mode = new short[1];
    public short[] SingleOrMulti = new short[1];
    Button Button1, Button2, Button3, Button4, Button5, Button6;
    private final Component tip1 = Component.translatable("gui.neosim.run.tip1"),
                            tip2 = Component.translatable("gui.neosim.run.tip2"),
                            tip3 = Component.translatable("gui.neosim.run.tip3");

    public Run(Component title)
    {
        super(title);
    }

    // 初始化按钮组件
    @Override
    protected void init()
    {
        Button1 = Button.builder(Component.translatable("gui.neosim.run.button1"), Button -> {
            Mode[0] = 1;
            Button2.active = true;
            Button3.active = true;
            Button.active = false;
        })
                .pos(90, 85)
                .size(135, 20)
                .build();
        this.addRenderableWidget(Button1);

        Button2 = Button.builder(Component.translatable("gui.neosim.run.button2"), Button -> {
            Mode[0] = 2;
            Button1.active = true;
            Button3.active = true;
            Button.active = false;
        })
                .pos(90, 145)
                .size(135, 20)
                .build();
        this.addRenderableWidget(Button2);

        Button3 = Button.builder(Component.translatable("gui.neosim.run.button3"), Button -> {
            Mode[0] = 3;
            Button1.active = true;
            Button2.active = true;
            Button.active = false;
        })
                .pos(90, 205)
                .size(135, 20)
                .build();
        this.addRenderableWidget(Button3);

        Button4 = Button.builder(Component.translatable("gui.neosim.run.button4"), Button -> {
            SingleOrMulti[0] = 1;
            Button5.active = true;
            Button.active = false;
        })
                .pos(265, 85)
                .size(135, 20)
                .build();
        this.addRenderableWidget(Button4);

        Button5 = Button.builder(Component.translatable("gui.neosim.run.button5"), Button -> {
            SingleOrMulti[0] = 2;
            Button4.active = true;
            Button.active = false;
        })
                .pos(265, 145)
                .size(135, 20)
                .build();
        this.addRenderableWidget(Button5);

        Button6 = Button.builder(Component.translatable("gui.neosim.run.button6"), Button -> {
            onClose();
        })
                .pos(265, 205)
                .size(135, 20)
                .build();
        this.addRenderableWidget(Button6);
    }

    // 渲染组件
    @Override
    public void render(GuiGraphics GuiGraphics, int MouseX, int MouseY, float PartialTick)
    {
        // 渲染背景
        this.renderBackground(GuiGraphics, MouseX, MouseY, PartialTick);

        // 调用父类渲染，即渲染按钮
        super.render(GuiGraphics, MouseX, MouseY, PartialTick);

        Font font = this.font;

        GuiGraphics.drawCenteredString(font, tip1, 150, 110, 0xFFFFFF00);
        GuiGraphics.drawCenteredString(font, tip2, 150, 170, 0xFFFFFF00);
        GuiGraphics.drawCenteredString(font, tip3, 150, 230, 0xFFFFFF00);
    }
}

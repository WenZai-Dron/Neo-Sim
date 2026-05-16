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

    int mode = 0;
    int singleOrMulti = 0;
    private Button button1, button2, button3, button4, button5, button6;
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
        button1 = Button.builder(Component.translatable("gui.neosim.run.button1"), Button -> {
            mode = 1;
            button2.active = true;
            button3.active = true;
            Button.active = false;
        })
                .pos(90, 85)
                .size(135, 20)
                .build();
        this.addRenderableWidget(button1);

        button2 = Button.builder(Component.translatable("gui.neosim.run.button2"), Button -> {
            mode = 2;
            button1.active = true;
            button3.active = true;
            Button.active = false;
        })
                .pos(90, 145)
                .size(135, 20)
                .build();
        this.addRenderableWidget(button2);

        button3 = Button.builder(Component.translatable("gui.neosim.run.button3"), Button -> {
            mode = 3;
            button1.active = true;
            button2.active = true;
            Button.active = false;
        })
                .pos(90, 205)
                .size(135, 20)
                .build();
        this.addRenderableWidget(button3);

        button4 = Button.builder(Component.translatable("gui.neosim.run.button4"), Button -> {
            singleOrMulti = 1;
            button5.active = true;
            Button.active = false;
        })
                .pos(265, 85)
                .size(135, 20)
                .build();
        this.addRenderableWidget(button4);

        button5 = Button.builder(Component.translatable("gui.neosim.run.button5"), Button -> {
            singleOrMulti = 2;
            button4.active = true;
            Button.active = false;
        })
                .pos(265, 145)
                .size(135, 20)
                .build();
        this.addRenderableWidget(button5);

        button6 = Button.builder(Component.translatable("gui.neosim.run.button6"), Button -> {
            onClose();
        })
                .pos(265, 205)
                .size(135, 20)
                .build();
        this.addRenderableWidget(button6);
        }

    // 渲染组件
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        // 渲染背景
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // 调用父类渲染，即渲染按钮
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        Font font = this.font;

        guiGraphics.drawCenteredString(font, tip1, 150, 110, 0xFFFFFF00);
        guiGraphics.drawCenteredString(font, tip2, 150, 170, 0xFFFFFF00);
        guiGraphics.drawCenteredString(font, tip3, 150, 230, 0xFFFFFF00);
    }
}

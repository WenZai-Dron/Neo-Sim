package com.wenzai.neosim.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicInteger;

public class Run extends Screen
{
    public Run(Component title)
    {
        super(title);
    }

    public Run ()
    {
        // 标题
        super(Component.translatable("gui.neosim.run.title"));
    }

    @Override
    protected void init()
    {
        super.init();

        AtomicInteger GameMode = new AtomicInteger();

        int ButtonX = this.width / 2 - 100;

        // Button1：普通模式
        // Button1：Normal Mode
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.neosim.run.button1"),
                        button ->
                        {
                            GameMode.set(1);
                            onClose();
                        }
                        )
                .pos(ButtonX, 60)
                .size(200, 20)
                .build());

        // Button2：创造模式
        // Button2：Creative Mode
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.neosim.run.button2"),
                        button ->
                        {
                            GameMode.set(2);
                            onClose();
                        }
                        )
                .pos(ButtonX, 120)
                .size(200, 20)
                .build());

        // Button3：硬核模式
        // Button3：Hardcore Mode
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.neosim.run.button3"),
                        button ->
                        {
                            GameMode.set(3);
                            onClose();
                        }
                        )
                .pos(ButtonX, 180)
                .size(200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        // 先绘背景
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int buttonX = this.width / 2 - 100;
        Font font = this.font;

        // tip1：
        guiGraphics.drawString(
                font,
                Component.translatable("gui.neosim.run.tip1"),
                buttonX + 10, 85,
                0xFFFFFF00,
                false
        );

        // tip2：
        guiGraphics.drawString(
                font,
                Component.translatable("gui.neosim.run.tip2"),
                buttonX + 10, 145,
                0xFFFFFF00,
                false
        );

        // tip3：
        guiGraphics.drawString(
                font,
                Component.translatable("gui.neosim.run.tip3"),
                buttonX + 10, 205,
                0xFFFFFF00,
                false
        );
    }
}

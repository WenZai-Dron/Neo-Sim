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
        // 标题
        super(Component.translatable("gui.neosim.run.title"));
    }

    @Override
    protected void init()
    {
        super.init();

        final short[] ModMode = new short[1];

        // Button1：普通模式 / Normal Mode
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.neosim.run.button1"),
                        button ->
                        {
                            ModMode[0] = 1;
                            onClose();
                        }
                        )
                .pos(this.width / 2 - 100, 85)
                .size(200, 20)
                .build());

        // Button2：创造模式 / Creative Mode
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.neosim.run.button2"),
                        button ->
                        {
                            ModMode[0] = 2;
                            onClose();
                        }
                        )
                .pos(this.width / 2 - 100, 145)
                .size(200, 20)
                .build());

        // Button3：硬核模式 / Hardcore Mode
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.neosim.run.button3"),
                        button ->
                        {
                            ModMode[0] = 3;
                            onClose();
                        }
                        )
                .pos(this.width / 2 - 100, 205)
                .size(200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        // 先绘背景
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        Font font = this.font;

        // tip1：作者也不知道会干嘛:) / Author doesn't know what will happen:)
        guiGraphics.drawString(
                font,
                Component.translatable("gui.neosim.run.tip1"),
                this.width / 2 - 90, 110,
                0xFFFFFF00,
                false
        );

        // tip2：无限资金和资源 / Author doesn't know how to translate:(
        guiGraphics.drawString(
                font,
                Component.translatable("gui.neosim.run.tip2"),
                this.width / 2 - 90, 170,
                0xFFFFFF00,
                false
        );

        // tip3：建筑需要所有方块 / Author doesn't know how to translate:(
        guiGraphics.drawString(
                font,
                Component.translatable("gui.neosim.run.tip3"),
                this.width / 2 - 90, 230,
                0xFFFFFF00,
                false
        );
    }
}

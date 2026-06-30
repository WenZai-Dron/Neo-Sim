package com.wenzai.neosim.gui;

import com.wenzai.neosim.UpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

public class Run extends Screen
{
    private static final ResourceLocation LOGO = ResourceLocation.fromNamespaceAndPath("neo_sim", "neo_sim_logo.png");

    public Run()
    {
        super(Component.translatable("gui.neosim.run.title"));
    }

    byte mode = 0;
    private Button buttonNormal, buttonCreative, buttonHardcore, buttonClose;
    private final Component tipNormal = Component.translatable("gui.neosim.run.tipNormal"),
                            tipCreative = Component.translatable("gui.neosim.run.tipCreative"),
                            tipHardcore = Component.translatable("gui.neosim.run.tipHardcore");

    // 初始化按钮组件
    @Override
    protected void init()
    {
        int width = this.width / 3;

        buttonNormal = Button.builder(Component.translatable("gui.neosim.run.buttonNormal"), Button -> {
            mode = 1;
            buttonCreative.active = true;
            buttonHardcore.active = true;
            Button.active = false;
            if ( mode != 0 ) { buttonClose.active = true; }
        })
                .pos(width, 85)
                .size(width + 20, 20)
                .build();
        this.addRenderableWidget(buttonNormal);

        buttonCreative = Button.builder(Component.translatable("gui.neosim.run.buttonCreative"), Button -> {
            mode = 2;
            buttonNormal.active = true;
            buttonHardcore.active = true;
            Button.active = false;
            if ( mode != 0 ) { buttonClose.active = true; }
        })
                .pos(width, 130)
                .size(width + 20, 20)
                .build();
        this.addRenderableWidget(buttonCreative);

        buttonHardcore = Button.builder(Component.translatable("gui.neosim.run.buttonHardcore"), Button -> {
            mode = 3;
            buttonNormal.active = true;
            buttonCreative.active = true;
            Button.active = false;
            if ( mode != 0 ) { buttonClose.active = true; }
        })
                .pos(width, 175)
                .size(width + 20, 20)
                .build();
        this.addRenderableWidget(buttonHardcore);

        buttonClose = Button.builder(Component.translatable("gui.neosim.run.buttonClose"), Button -> {
            PacketDistributor.sendToServer(new UpdatePayload(mode));
            onClose();
            Minecraft.getInstance().setScreen(new City());
        })
                .pos(width - 10, 230)
                .size(width + 40, 20)
                .build();
        this.addRenderableWidget(buttonClose);

        buttonClose.active = false;
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
        int width = this.width / 2 - 30;

        guiGraphics.drawCenteredString(font, tipNormal, width, 110, 0xFFFFFF00);
        guiGraphics.drawCenteredString(font, tipCreative, width - 10, 155, 0xFFFFFF00);
        guiGraphics.drawCenteredString(font, tipHardcore, width - 5, 200, 0xFFFFFF00);

        // 渲染Logo，感觉边缘不得劲
        guiGraphics.blit(LOGO, width - 40, 3, 161, 100, 0.0F, 0.0F, 402, 250, 402, 250);
    }
}

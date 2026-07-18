package com.wenzai.neosim.gui;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.storage.UpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

public class Run extends Screen
{
    private static final ResourceLocation LOGO = ResourceLocation.fromNamespaceAndPath("neo_sim", "neo_sim_logo_run.png");

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
        int btnW = this.width / 3;
        int btnH = this.height / 13;

        buttonNormal = Button.builder(Component.translatable("gui.neosim.run.buttonNormal"), Button -> {
            mode = 1;
            buttonCreative.active = true;
            buttonHardcore.active = true;
            Button.active = false;
            if ( mode != 0 ) { buttonClose.active = true; }
        })
                .pos(btnW, this.height / 3)
                .size(btnW + this.width / 24, btnH)
                .build();
        this.addRenderableWidget(buttonNormal);

        buttonCreative = Button.builder(Component.translatable("gui.neosim.run.buttonCreative"), Button -> {
            mode = 2;
            buttonNormal.active = true;
            buttonHardcore.active = true;
            Button.active = false;
            if ( mode != 0 ) { buttonClose.active = true; }
        })
                .pos(btnW, this.height / 2)
                .size(btnW + this.width / 24, btnH)
                .build();
        this.addRenderableWidget(buttonCreative);

        buttonHardcore = Button.builder(Component.translatable("gui.neosim.run.buttonHardcore"), Button -> {
            mode = 3;
            buttonNormal.active = true;
            buttonCreative.active = true;
            Button.active = false;
            if ( mode != 0 ) { buttonClose.active = true; }
        })
                .pos(btnW, this.height * 2 / 3)
                .size(btnW + this.width / 24, btnH)
                .build();
        this.addRenderableWidget(buttonHardcore);

        buttonClose = Button.builder(Component.translatable("gui.neosim.run.buttonClose"), Button -> {
            PacketDistributor.sendToServer(new UpdatePayload(mode));
            onClose();
            Minecraft.getInstance().setScreen(new City());
        })
                .pos(btnW - this.width / 48, this.height * 5 / 6)
                .size(btnW + this.width / 12, btnH)
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

        int textX = this.width / 2 - this.width / 16;
        int btnH = this.height / 13;
        int textGap = this.height / 54;

        guiGraphics.drawCenteredString(this.font, tipNormal, textX, this.height / 3 + btnH + textGap, 0xFFFFFF00);
        guiGraphics.drawCenteredString(this.font, tipCreative, textX - this.width / 48, this.height / 2 + btnH + textGap, 0xFFFFFF00);
        guiGraphics.drawCenteredString(this.font, tipHardcore, textX - this.width / 96, this.height * 2 / 3 + btnH + textGap, 0xFFFFFF00);

        // 渲染Logo
        guiGraphics.blit(LOGO, textX - this.width / 12, 3, this.width / 3, this.height * 3 / 8, 0.0F, 0.0F, 400, 250, 400, 250);

        NeoSim.LOGGER.info(
                "this.width=" + this.width + " this.height=" + this.height
        );
    }
}

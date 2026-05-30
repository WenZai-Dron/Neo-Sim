package com.wenzai.neosim.gui;

import com.wenzai.neosim.SyncConfigPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public class Run extends Screen
{
    public Run()
    {
        super(Component.translatable("gui.neosim.run.title"));
    }

    int mode = 0;
    int singleOrMulti = 0;
    private Button buttonNormal, buttonCreative, buttonHardcore, buttonSingle, buttonMulti, buttonClose;
    private final Component tipNormal = Component.translatable("gui.neosim.run.tipNormal"),
                            tipCreative = Component.translatable("gui.neosim.run.tipCreative"),
                            tipHardcore = Component.translatable("gui.neosim.run.tipHardcore");

    // 初始化按钮组件
    @Override
    protected void init()
    {
        buttonClose.active = false;

        buttonNormal = Button.builder(Component.translatable("gui.neosim.run.buttonNormal"), Button -> {
            mode = 1;
            buttonCreative.active = true;
            buttonHardcore.active = true;
            Button.active = false;
        })
                .pos(90, 85)
                .size(135, 20)
                .build();
        this.addRenderableWidget(buttonNormal);

        buttonCreative = Button.builder(Component.translatable("gui.neosim.run.buttonCreative"), Button -> {
            mode = 2;
            buttonNormal.active = true;
            buttonHardcore.active = true;
            Button.active = false;
        })
                .pos(90, 145)
                .size(135, 20)
                .build();
        this.addRenderableWidget(buttonCreative);

        buttonHardcore = Button.builder(Component.translatable("gui.neosim.run.buttonHardcore"), Button -> {
            mode = 3;
            buttonNormal.active = true;
            buttonCreative.active = true;
            Button.active = false;
        })
                .pos(90, 205)
                .size(135, 20)
                .build();
        this.addRenderableWidget(buttonHardcore);

        buttonSingle = Button.builder(Component.translatable("gui.neosim.run.buttonSingle"), Button -> {
            singleOrMulti = 1;
            buttonMulti.active = true;
            Button.active = false;
        })
                .pos(265, 85)
                .size(135, 20)
                .build();
        this.addRenderableWidget(buttonSingle);

        buttonMulti = Button.builder(Component.translatable("gui.neosim.run.buttonMulti"), Button -> {
            singleOrMulti = 2;
            buttonSingle.active = true;
            Button.active = false;
        })
                .pos(265, 145)
                .size(135, 20)
                .build();
        this.addRenderableWidget(buttonMulti);

        buttonClose = Button.builder(Component.translatable("gui.neosim.run.buttonClose"), Button -> {
            if ( mode != 0 && singleOrMulti != 0 )
                    {
                        Button.active = true;
                        syncToServer();
                        onClose();
                    }
        })
                .pos(265, 205)
                .size(135, 20)
                .build();
        this.addRenderableWidget(buttonClose);
    }

    // 发送网络包
    private void syncToServer()
    {
        // 0 与 0.0是占位符，服务端将直接无视
        PacketDistributor.sendToServer(new SyncConfigPayload(mode, singleOrMulti, 0, 0, 0, 0.0));
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

        guiGraphics.drawCenteredString(font, tipNormal, 150, 110, 0xFFFFFF00);
        guiGraphics.drawCenteredString(font, tipCreative, 150, 170, 0xFFFFFF00);
        guiGraphics.drawCenteredString(font, tipHardcore, 150, 230, 0xFFFFFF00);
    }
}

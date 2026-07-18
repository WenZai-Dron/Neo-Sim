package com.wenzai.neosim.gui;

import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.storage.FileCreater;
import com.wenzai.neosim.storage.ModSavedData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

public class Add extends Screen
{
    private EditBox inputBox;
    private Button buttonConfirm;

    public Add()
    {
        super(Component.translatable("gui.neosim.cityAdd.title"));
    }

    @Override
    protected void init()
    {
        int inputW = this.width / 3;
        int inputH = this.height / 13;
        int centerY = this.height / 2;
        int btnW = this.width * 5 / 24;
        int btnH = this.height / 13;

        inputBox = new EditBox(this.font, inputW, centerY - inputH, inputW, inputH, Component.translatable("gui.neosim.cityAdd.input"));

        inputBox.setMaxLength(50);

        buttonConfirm = Button.builder(Component.translatable("gui.neosim.cityAdd.buttonConfirm"), Button -> {
            onConfirm();
        })
                .pos(this.width / 2 - btnW / 2, centerY + this.height / 5)
                .size(btnW, btnH)
                .build();
        this.addRenderableWidget(buttonConfirm);
    }

    private void onConfirm()
    {
        String cityName = inputBox.getValue().trim();
        if (!cityName.isEmpty())
        {
            Minecraft mc = Minecraft.getInstance();
            String playerName = mc.getUser().getName();
            if (mc.hasSingleplayerServer())
            {
                String saveName = mc.getSingleplayerServer().getWorldData().getLevelName();
                FileCreater.createCityFolder(cityName, saveName);
                FileCreater.savePlayerToCity(cityName, saveName, playerName);
            }
            else
            {
                FileCreater.createCityFolder(cityName);
                FileCreater.savePlayerToCity(cityName, playerName);
            }
            ModSavedData.setActiveCityName(cityName);

            // 生成第一个NPC
            if (mc.hasSingleplayerServer())
            {
                ServerLevel level = mc.getSingleplayerServer().overworld();
                Manage.ensurePopulationNotEmpty(level);
            }
        }
        onClose();
    }

    // Esc
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        if (keyCode == 256)
        {
            Minecraft.getInstance().setScreen(new City());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        // 渲染背景
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // 调用父类渲染
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 文本输入框无文字时，按钮不可点击
        buttonConfirm.active = !inputBox.getValue().trim().isEmpty();

        // 渲染文本输入框
        this.addRenderableWidget(inputBox);
    }
}

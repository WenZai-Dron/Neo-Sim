package com.wenzai.neosim.gui;

import com.wenzai.neosim.FileCreater;
import com.wenzai.neosim.ModSavedData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

public class CityAdd extends Screen
{
    private EditBox inputBox;
    private Button buttonConfirm;

    public CityAdd()
    {
        super(Component.translatable("gui.neosim.cityAdd.title"));
    }

    @Override
    protected void init()
    {
        int width = this.width / 3;
        int height = this.height / 2;

        inputBox = new EditBox(this.font, width, height - 20, width, 20, Component.translatable("gui.neosim.cityAdd.input"));

        inputBox.setMaxLength(50);

        buttonConfirm = Button.builder(Component.translatable("gui.neosim.cityAdd.buttonConfirm"), Button -> {
            onConfirm();
        })
                .pos(this.width / 2 - 50, height + 50)
                .size(100, 20)
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
                // 客户端：NeoSim/data/<存档名>/<cityName>/
                String saveName = mc.getSingleplayerServer().getWorldData().getLevelName();
                FileCreater.createCityFolder(cityName, saveName);
                FileCreater.savePlayerToCity(cityName, saveName, playerName);
            }
            else
            {
                // 服务端：NeoSim/data/<cityName>/
                FileCreater.createCityFolder(cityName);
                FileCreater.savePlayerToCity(cityName, playerName);
            }
            ModSavedData.setActiveCityName(cityName);
        }
        onClose();
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

package com.wenzai.neosim.gui;

import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.storage.FileCreater;
import com.wenzai.neosim.storage.ModSavedData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class City extends Screen
{
    private static final int MAX_CITY_BUTTONS = 10;

    private int mode = 0;

    private EditBox inputBox;
    private Button buttonConfirm;
    private Button buttonChoose;

    public City()
    {
        super(Component.translatable("gui.neosim.city.title"));
    }

    @Override
    protected void init()
    {
        if (mode == 0)
        {
            initMainMenu();
        }
        else if (mode == 1)
        {
            initAddMode();
        }
        else
        {
            initChooseMode();
        }
    }

    // 主菜单：新增城市/选择城市
    private void initMainMenu()
    {
        int btnW = this.width / 3 + this.width / 7;
        int btnH = this.height / 13;
        int btnX = this.width / 2 - btnW / 2;

        Button buttonAdd = Button.builder(Component.translatable("gui.neosim.city.add"), btn -> {
            mode = 1;
            rebuildWidgets();
        })
                .pos(btnX, this.height / 2 - this.height / 7)
                .size(btnW, btnH)
                .build();
        this.addRenderableWidget(buttonAdd);

        buttonChoose = Button.builder(Component.translatable("gui.neosim.city.choose"), btn -> {
            mode = 2;
            rebuildWidgets();
        })
                .pos(btnX, this.height / 2 + this.height / 7)
                .size(btnW, btnH)
                .build();
        this.addRenderableWidget(buttonChoose);
    }

    // 新增城市
    private void initAddMode()
    {
        int inputW = this.width / 3;
        int inputH = this.height / 13;
        int btnW = this.width * 5 / 24;
        int btnH = this.height / 13;
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        inputBox = new EditBox(this.font, centerX - inputW / 2, centerY - inputH * 3 / 2,
                inputW, inputH, Component.translatable("gui.neosim.city.title"));
        inputBox.setMaxLength(50);
        this.addRenderableWidget(inputBox);

        buttonConfirm = Button.builder(Component.translatable("gui.neosim.city.buttonConfirm"), btn -> {
            onConfirm();
        })
                .pos(centerX - btnW / 2, centerY)
                .size(btnW, btnH)
                .build();
        this.addRenderableWidget(buttonConfirm);
    }

    // 选择城市
    private void initChooseMode()
    {
        List<String> cities = listCities();

        int chooseBtnW = this.width / 3 + this.width / 7;
        int chooseBtnH = this.height / 13;
        int chooseBtnX = this.width / 2 - chooseBtnW / 2;
        int totalHeight = Math.min(cities.size(), MAX_CITY_BUTTONS) * (chooseBtnH + 5);
        int startY = this.height / 2 - totalHeight / 2;

        for (int i = 0; i < Math.min(cities.size(), MAX_CITY_BUTTONS); i++)
        {
            String cityName = cities.get(i);
            int btnY = startY + i * (chooseBtnH + 5);
            Button cityButton = Button.builder(Component.literal(cityName), btn -> {
                onSelectCity(cityName);
            })
                    .pos(chooseBtnX, btnY)
                    .size(chooseBtnW, chooseBtnH)
                    .build();
            this.addRenderableWidget(cityButton);
        }
    }

    // 确认新增城市
    private void onConfirm()
    {
        String cityName = inputBox.getValue().trim();
        if (!cityName.isEmpty())
        {
            Minecraft mc = Minecraft.getInstance();
            String playerName = mc.getUser().getName();
            if (mc.hasSingleplayerServer())
            {
                String saveName = mc.getSingleplayerServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).getParent().getFileName().toString();
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

    // 选择已有城市
    private void onSelectCity(String cityName)
    {
        Minecraft mc = Minecraft.getInstance();
        String playerName = mc.getUser().getName();
        if (mc.hasSingleplayerServer())
        {
            String saveName = mc.getSingleplayerServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).getParent().getFileName().toString();
            FileCreater.savePlayerToCity(cityName, saveName, playerName);
        }
        else
        {
            FileCreater.savePlayerToCity(cityName, playerName);
        }
        ModSavedData.setActiveCityName(cityName);
        onClose();
    }

    // 列出已有城市
    private static List<String> listCities()
    {
        List<String> cities = new ArrayList<>();
        Path dataDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");

        if (!Files.isDirectory(dataDir)) return cities;

        Path searchDir;
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer())
        {
            String saveName = mc.getSingleplayerServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).getParent().getFileName().toString();
            searchDir = dataDir.resolve(saveName);
        }
        else
        {
            searchDir = dataDir;
        }

        if (!Files.isDirectory(searchDir)) return cities;

        try (var entries = Files.list(searchDir))
        {
            for (Path entry : entries.toList())
            {
                if (Files.isDirectory(entry) && Files.exists(entry.resolve("npc")))
                {
                    cities.add(entry.getFileName().toString());
                }
            }
        }
        catch (Exception e)
        {
            return cities;
        }

        return cities;
    }

    // Esc回到主菜单
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        if (keyCode == 256)
        {
            if (mode != 0)
            {
                mode = 0;
                rebuildWidgets();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // 无城市时，选择按钮不可点击
        if (mode == 0 && buttonChoose != null)
        {
            buttonChoose.active = !listCities().isEmpty();
        }

        // 输入框无文字时，确认按钮不可点击
        if (mode == 1 && buttonConfirm != null)
        {
            buttonConfirm.active = !inputBox.getValue().trim().isEmpty();
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}

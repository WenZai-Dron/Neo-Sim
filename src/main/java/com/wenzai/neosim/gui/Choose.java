package com.wenzai.neosim.gui;

import com.wenzai.neosim.storage.FileCreater;
import com.wenzai.neosim.storage.ModSavedData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Choose extends Screen
{
    private static final int MAX_BUTTONS = 10;

    public Choose()
    {
        super(Component.translatable("gui.neosim.cityChoose.title"));
    }

    @Override
    protected void init()
    {
        List<String> cities = listCities();

        int btnW = this.width / 3 + this.width / 7;
        int btnH = this.height / 13;
        int btnX = this.width / 2 - btnW / 2;
        int totalHeight = Math.min(cities.size(), MAX_BUTTONS) * (btnH + 5);
        int startY = this.height / 2 - totalHeight / 2;

        for (int i = 0; i < Math.min(cities.size(), MAX_BUTTONS); i++)
        {
            String cityName = cities.get(i);
            int btnY = startY + i * (btnH + 5);
            Button cityButton = Button.builder(Component.literal(cityName), btn -> {
                onSelectCity(cityName);
            })
                    .pos(btnX, btnY)
                    .size(btnW, btnH)
                    .build();
            this.addRenderableWidget(cityButton);
        }
    }

    private void onSelectCity(String cityName)
    {
        Minecraft mc = Minecraft.getInstance();
        String playerName = mc.getUser().getName();
        if (mc.hasSingleplayerServer())
        {
            String saveName = mc.getSingleplayerServer().getWorldData().getLevelName();
            FileCreater.savePlayerToCity(cityName, saveName, playerName);
        }
        else
        {
            FileCreater.savePlayerToCity(cityName, playerName);
        }
        ModSavedData.setActiveCityName(cityName);
        onClose();
    }

    // 供City复用
    static boolean hasAnyCity()
    {
        return !listCities().isEmpty();
    }

    // 列出城市
    static List<String> listCities()
    {
        List<String> cities = new ArrayList<>();
        Path dataDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");

        if (!Files.isDirectory(dataDir)) return cities;

        Path searchDir;
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer())
        {
            // 客户端
            String saveName = mc.getSingleplayerServer().getWorldData().getLevelName();
            searchDir = dataDir.resolve(saveName);
        }
        else
        {
            // 服务器
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
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

}

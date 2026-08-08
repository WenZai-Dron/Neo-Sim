package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.building.ControlBoxPersistence;
import com.wenzai.neosim.client.BuildingNameLocalizer;
import com.wenzai.neosim.storage.ModSavedData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ControlBoxGui extends Screen
{
    private static final String P = "gui.neosim.ControlBox.";

    private final BlockPos boxPos;
    private final ControlBoxPersistence.ControlBoxRecord record;

    public ControlBoxGui(BlockPos boxPos)
    {
        super(Component.translatable(P + "title"));
        this.boxPos = boxPos;
        this.record = loadRecord(boxPos);
    }

    // 客户端读取控制箱记录
    public static ControlBoxPersistence.ControlBoxRecord loadRecord(BlockPos pos)
    {
        String cityName = ModSavedData.getActiveCityName();
        if (cityName.isEmpty()) return null;

        String saveName = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null)
        {
            saveName = mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        return ControlBoxPersistence.findRecord(saveName, cityName, pos);
    }

    public static boolean hasRecord(BlockPos pos)
    {
        return loadRecord(pos) != null;
    }

    // 生命周期
    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init()
    {
        addRenderableWidget(Button.builder(Component.translatable(P + "close"), b -> onClose())
                .pos(width / 2 - 50, height - 30).size(100, 20).build());
    }

    @Override
    public void render(GuiGraphics gfx, int mx, int my, float pt)
    {
        renderBackground(gfx, mx, my, pt);
        super.render(gfx, mx, my, pt);
        drawInfo(gfx);
    }

    private void drawInfo(GuiGraphics gfx)
    {
        gfx.drawCenteredString(font, Component.translatable(P + "title"), width / 2, 10, 0xFFFFFF);

        if (record == null)
        {
            gfx.drawCenteredString(font, Component.translatable(P + "noRecord"), width / 2, 60, 0xAAAAAA);
            return;
        }

        int x = width / 2 - 100;
        int y = 50;

        // 建筑名
        gfx.drawString(font, Component.translatable(P + "building",
                BuildingNameLocalizer.localize(record.schematicName())), x, y, 0xFFFFFF);
        y += 24;

        // 作者
        String author = record.author() != null && !record.author().isEmpty()
                ? record.author()
                : Component.translatable(P + "none").getString();
        gfx.drawString(font, Component.translatable(P + "author", author), x, y, 0xCCCCCC);
        y += 24;

        // 所建玩家
        String placer = record.placerName() != null && !record.placerName().isEmpty()
                ? record.placerName()
                : Component.translatable(P + "none").getString();
        gfx.drawString(font, Component.translatable(P + "placer", placer), x, y, 0xCCCCCC);
        y += 24;

        // 居民（生活点入住的人）
        List<String> names = record.residents().stream()
                .map(ControlBoxPersistence.Resident::name).toList();
        String residents = names.isEmpty()
                ? Component.translatable(P + "none").getString()
                : String.join(", ", names);
        gfx.drawString(font, Component.translatable(P + "residents", residents), x, y, 0xCCCCCC);
    }

    @Override
    public void onClose()
    {
        if (minecraft != null)
        {
            minecraft.setScreen(null);
            minecraft.mouseHandler.grabMouse();
        }
    }
}

package com.wenzai.neosim.client;

import com.wenzai.neosim.client.gui.BuildingConstructorGui;
import com.wenzai.neosim.client.gui.ControlBoxGui;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public class ClientBlockInteractions
{
    private ClientBlockInteractions() {}

    // 打开建筑模盒GUI
    public static void openBuildingConstructor(BlockPos pos)
    {
        Minecraft.getInstance().setScreen(new BuildingConstructorGui(pos));
    }

    // 打开控制箱GUI
    public static void openControlBox(BlockPos pos)
    {
        Minecraft.getInstance().setScreen(new ControlBoxGui(pos));
    }

    // 控制箱是否有记录
    public static boolean hasControlBoxRecord(BlockPos pos)
    {
        return ControlBoxGui.hasRecord(pos);
    }

    // 清除GUI已选蓝图缓存
    public static void clearSelectedAt(BlockPos pos)
    {
        BuildingConstructorGui.clearSelectedAt(pos);
    }
}

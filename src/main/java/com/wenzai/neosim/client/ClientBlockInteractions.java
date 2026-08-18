package com.wenzai.neosim.client;

import com.wenzai.neosim.client.gui.*;
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

	// 打开农业盒GUI
	public static void openFarmingBox(BlockPos pos)
	{
		Minecraft.getInstance().setScreen(new FarmingBoxGui(pos));
	}

	// 打开矿业盒GUI
	public static void openMiningBox(BlockPos pos)
	{
		Minecraft.getInstance().setScreen(new MiningBoxGui(pos));
	}

	// 农业盒是否有记录
	public static boolean hasFarmingBoxRecord(BlockPos pos)
	{
		return FarmingBoxGui.hasRecord(pos);
	}

	// 矿业盒是否有记录
	public static boolean hasMiningBoxRecord(BlockPos pos)
	{
		return MiningBoxGui.hasRecord(pos);
	}

	// 打开快递盒GUI
	public static void openDeliveryBox(BlockPos pos)
	{
		Minecraft.getInstance().setScreen(new DeliveryBoxGui(pos));
	}

	// 快递盒是否有记录
	public static boolean hasDeliveryBoxRecord(BlockPos pos)
	{
		return DeliveryBoxGui.hasRecord(pos);
	}
}

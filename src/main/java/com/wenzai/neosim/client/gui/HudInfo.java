package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.NeoSimClient;
import com.wenzai.neosim.client.ClientDataHolder;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

// HUD 一行的内容来源：HUD 与城市信息 GUI 共用同一份实现，保证两处显示永远一致
public final class HudInfo
{
	private static final String HUD = "gui.neosim.hud.";
	private static final String RUN = "gui.neosim.run.";
	private static final String CITY_INFO = "gui.neosim.cityinfo.";

	private HudInfo()
	{
	}

	// 游戏内时间 HH:MM（与 HUD 同一算法：dayTime + 6000 偏移）
	public static String gameTime(Minecraft mc)
	{
		int[] parts = timeParts(mc);
		return String.format("%02d:%02d", parts[0], parts[1]);
	}

	// {时, 分}：HUD 用它做缓存比较，GUI 用它排版
	public static int[] timeParts(Minecraft mc)
	{
		if (mc == null || mc.level == null) return new int[] { 0, 0 };
		long dayTime = mc.level.getDayTime() % 24000;
		long adjusted = (dayTime + 6000) % 24000;
		return new int[] { (int) (adjusted / 1000), (int) ((adjusted % 1000) * 60 / 1000) };
	}

	// 星期文案（与 HUD 同表）
	public static String weekday(int index)
	{
		String[] keys = { "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday" };
		return Component.translatable(HUD + keys[index >= 0 && index < keys.length ? index : 0]).getString();
	}

	// 运行模式文案（HUD 不显示模式，GUI 概览页补上）
	public static String modeName(byte mode)
	{
		return switch (mode)
		{
			case 1 -> Component.translatable(RUN + "buttonNormal").getString();
			case 2 -> Component.translatable(RUN + "buttonCreative").getString();
			case 3 -> Component.translatable(RUN + "buttonHardcore").getString();
			default -> Component.translatable(CITY_INFO + "mode.none").getString();
		};
	}

	// 金额文本：唯一实现在 NeoSim.amount（HUD / 城市信息页 / 指令共用）
	public static String amount(double value)
	{
		return NeoSim.amount(value);
	}

	// 单个 HUD 字段的文本（键见 UiSettings.FIELD_KEYS）：HUD 的取值都从这里出
	@Nullable
	public static String field(Minecraft mc, String key)
	{
		ClientDataHolder data = ClientDataHolder.getInstance();

		return switch (key)
		{
			case "city" -> data.getCityName().isEmpty() ? "—" : data.getCityName();
			case "time" -> gameTime(mc);
			case "weekday" -> weekday(data.getDayOfWeek());
			case "day" -> Component.translatable(HUD + "day", data.getDay()).getString();
			case "population" -> Component.translatable(HUD + "population").getString() + ": " + data.getPopulation();
			case "credit" -> Component.translatable(HUD + "credit").getString() + ": " + amount(data.getCredit());
			case "mode" -> modeName(data.getMode());
			default -> null;
		};
	}

	// 开场倒计时秒数：tick 数向上取整到秒
	public static String countdown()
	{
		return ((NeoSimClient.getOpenGuiTimer() + 19) / 20) + "";
	}
}

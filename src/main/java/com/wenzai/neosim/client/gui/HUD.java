package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.NeoSimClient;
import com.wenzai.neosim.client.ClientDataHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

@OnlyIn(Dist.CLIENT)
public class HUD
{
	// L12：HUD 行缓存（仅数据变化时重建字符串；时间每分钟变一次）
	private static String cachedLine = "";
	private static int cachedMode = -1;
	private static int cachedDayOfWeek = -1;
	private static int cachedDay = -1;
	private static short cachedPopulation = -1;
	private static double cachedCredit = Double.NaN;
	private static String cachedCity = "";
	private static int cachedHour = -1;
	private static int cachedMinute = -1;

	@SubscribeEvent
	public void renderHUD(RenderGuiEvent.Post event)
	{
		Minecraft mc = Minecraft.getInstance();
		GuiGraphics guiGraphics = event.getGuiGraphics();

		if (mc.level == null || mc.player == null)
		{
			return;
		}

		LocalPlayer player = mc.player;

		// 获取客户端缓存的数据
		ClientDataHolder data = ClientDataHolder.getInstance();

		int runTimer = NeoSimClient.getOpenGuiTimer();

		// 不渲染
		if (data.getMode() == 0 && runTimer <= 0)
		{
			return;
		}

		// 显示倒计时
		if (data.getMode() == 0 && runTimer > 0)
		{
			String countdown = ((runTimer + 19) / 20) + "";
			guiGraphics.drawString(mc.font, countdown, 10, 10, 0xFFFFFF);
			return;
		}

		// 游戏内时间（分钟粒度，缓存对比用）
		long dayTime = mc.level.getDayTime() % 24000;
		long adjustedTicks = (dayTime + 6000) % 24000;
		int hour = (int) (adjustedTicks / 1000);
		int minute = (int) ((adjustedTicks % 1000) * 60 / 1000);

		if (cachedMode != data.getMode()
				|| cachedDayOfWeek != data.getDayOfWeek()
				|| cachedDay != data.getDay()
				|| cachedPopulation != data.getPopulation()
				|| Double.compare(cachedCredit, data.getCredit()) != 0
				|| !cachedCity.equals(data.getCityName())
				|| cachedHour != hour || cachedMinute != minute)
		{
			String modeStr = switch (data.getMode())
			{
				case 1 -> Component.translatable("gui.neosim.run.buttonNormal").getString();
				case 2 -> Component.translatable("gui.neosim.run.buttonCreative").getString();
				case 3 -> Component.translatable("gui.neosim.run.buttonHardcore").getString();
				default -> "Null";
			};

			String dayOfWeekStr = switch (data.getDayOfWeek())
			{
				case 0 -> Component.translatable("gui.neosim.hud.sunday").getString();
				case 1 -> Component.translatable("gui.neosim.hud.monday").getString();
				case 2 -> Component.translatable("gui.neosim.hud.tuesday").getString();
				case 3 -> Component.translatable("gui.neosim.hud.wednesday").getString();
				case 4 -> Component.translatable("gui.neosim.hud.thursday").getString();
				case 5 -> Component.translatable("gui.neosim.hud.friday").getString();
				case 6 -> Component.translatable("gui.neosim.hud.saturday").getString();
				default -> "Null";
			};

			cachedLine = data.getCityName() + " - "
					+ String.format("%02d:%02d", hour, minute) + " - "
					+ dayOfWeekStr + " - "
					+ Component.translatable("gui.neosim.hud.day", data.getDay()).getString() + " - "
					+ Component.translatable("gui.neosim.hud.population").getString() + ": " + data.getPopulation() + " - "
					+ Component.translatable("gui.neosim.hud.credit").getString() + ": " + String.format("%.2f", data.getCredit());
			cachedMode = data.getMode();
			cachedDayOfWeek = data.getDayOfWeek();
			cachedDay = data.getDay();
			cachedPopulation = data.getPopulation();
			cachedCredit = data.getCredit();
			cachedCity = data.getCityName();
			cachedHour = hour;
			cachedMinute = minute;
		}

		guiGraphics.drawString(mc.font, cachedLine, 10, 10, 0xFFFFFF);
	}
}

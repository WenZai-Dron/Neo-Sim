package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSimClient;
import com.wenzai.neosim.client.ClientDataHolder;
import com.wenzai.neosim.client.ui.UiSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class HUD
{
	// HUD 行缓存：数据、游戏内时间或界面设置（UiSettings.revision）没变就不重建字符串
	private static List<String> cachedLines = List.of();
	private static int cachedRevision = -1;
	private static int cachedMode = -1;
	private static int cachedDayOfWeek = -1;
	private static int cachedDay = -1;
	private static short cachedPopulation = -1;
	private static double cachedCredit = Double.NaN;
	private static String cachedCity = "";
	private static int cachedHour = -1;
	private static int cachedMinute = -1;
	private static int cachedRunTimer = -1;

	@SubscribeEvent
	public void renderHUD(RenderGuiEvent.Post event)
	{
		Minecraft mc = Minecraft.getInstance();
		GuiGraphics guiGraphics = event.getGuiGraphics();

		if (mc.level == null || mc.player == null)
		{
			return;
		}

		// 打开任意界面时隐藏 HUD：原版在 GameRenderer 里无条件渲染 HUD、界面叠加在它之后，
		// 不隐藏的话这行城市信息会透在界面背后（RenderGuiEvent.Pre 取消会连原版血条一起隐藏，故用早退）
		if (Config.HIDE_HUD_IN_GUI.get() && mc.screen != null)
		{
			return;
		}

		UiSettings.Hud settings = UiSettings.hud();

		if (!settings.enabled)
		{
			return;
		}

		// 获取客户端缓存的数据
		ClientDataHolder data = ClientDataHolder.getInstance();
		int runTimer = NeoSimClient.getOpenGuiTimer();

		// 游戏内时间（分钟粒度，缓存对比用）
		int[] time = HudInfo.timeParts(mc);

		if (cachedRevision != UiSettings.revision()
				|| cachedMode != data.getMode()
				|| cachedDayOfWeek != data.getDayOfWeek()
				|| cachedDay != data.getDay()
				|| cachedPopulation != data.getPopulation()
				|| Double.compare(cachedCredit, data.getCredit()) != 0
				|| !cachedCity.equals(data.getCityName())
				|| cachedHour != time[0] || cachedMinute != time[1]
				|| cachedRunTimer != runTimer)
		{
			// 行内容统一由 HudRenderer 生成：字段、顺序、分隔符都来自界面设置
			cachedLines = HudRenderer.lines(mc, settings);
			cachedRevision = UiSettings.revision();
			cachedMode = data.getMode();
			cachedDayOfWeek = data.getDayOfWeek();
			cachedDay = data.getDay();
			cachedPopulation = data.getPopulation();
			cachedCredit = data.getCredit();
			cachedCity = data.getCityName();
			cachedHour = time[0];
			cachedMinute = time[1];
			cachedRunTimer = runTimer;
		}

		if (cachedLines.isEmpty())
		{
			return;
		}

		HudRenderer.draw(guiGraphics, mc.font,
				mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), cachedLines);
	}
}

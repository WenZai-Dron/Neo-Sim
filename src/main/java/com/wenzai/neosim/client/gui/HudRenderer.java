package com.wenzai.neosim.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wenzai.neosim.NeoSimClient;
import com.wenzai.neosim.client.ClientDataHolder;
import com.wenzai.neosim.client.ui.UiSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

// HUD 的行内容与排版只在这里实现一次：HUD 与世界之间只有这一条绘制路径，
// 免得出现"设置里改了位置、世界里的 HUD 没跟着动"的两处实现漂移。
public final class HudRenderer
{
	private HudRenderer()
	{
	}

	// 行内容：按设置里的字段与顺序取值；等待开局（mode == 0）时只顶一行倒计时
	public static List<String> lines(Minecraft mc, UiSettings.Hud hud)
	{
		List<String> values = new ArrayList<>();
		if (hud == null || !hud.enabled) return values;

		ClientDataHolder data = ClientDataHolder.getInstance();

		if (data.getMode() == 0)
		{
			// 等待开局：只顶一行倒计时，其余字段不参与
			if (NeoSimClient.getOpenGuiTimer() > 0)
			{
				values.add(HudInfo.countdown());
			}
			return values;
		}

		for (UiSettings.Field field : hud.fields)
		{
			if (!field.enabled) continue;

			String value = HudInfo.field(mc, field.key);
			if (value != null && !value.isEmpty()) values.add(value);
		}

		return join(hud, values);
	}

	// 单行模式拼成一行，多行模式一个字段一行
	private static List<String> join(UiSettings.Hud hud, List<String> values)
	{
		if (!hud.multiLine && values.size() > 1)
		{
			return List.of(String.join(hud.separator, values));
		}
		return values;
	}

	// 文本块尺寸 {宽, 高}（未乘缩放）：算锚点对齐要用
	public static int[] blockSize(Font font, UiSettings.Hud hud, List<String> lines)
	{
		int lineSizeH = font.lineHeight + hud.lineSpacing;
		int blockSizeW = 0;

		for (String line : lines)
		{
			blockSizeW = Math.max(blockSizeW, font.width(line));
		}

		return new int[] { blockSizeW, lines.isEmpty() ? 0 : lines.size() * lineSizeH - hud.lineSpacing };
	}

	// 世界 HUD：整屏区域内按设置绘制
	public static void draw(GuiGraphics gfx, Font font, int screenWidth, int screenHeight, List<String> lines)
	{
		draw(gfx, font, UiSettings.hud(), lines, 0, 0, screenWidth, screenHeight);
	}

	// 把行按设置画进一个矩形（世界 HUD 传整屏；留了矩形参数方便以后做别的预览宿主）
	public static void draw(GuiGraphics gfx, Font font, UiSettings.Hud hud, List<String> lines,
			int areaPosX, int areaPosY, int areaSizeW, int areaSizeH)
	{
		if (hud == null || !hud.enabled || lines.isEmpty()) return;

		float scale = (float) hud.scale;
		int lineSizeH = font.lineHeight + hud.lineSpacing;
		int[] size = blockSize(font, hud, lines);
		int textSizeW = size[0];
		int textSizeH = size[1];
		float blockSizeW = textSizeW * scale;
		float blockSizeH = textSizeH * scale;

		// 锚点 = 九宫格里的哪一格；偏移是玩家自己加的像素，锚点决定从哪条边算
		float posX = align(horizontal(hud.anchor), areaPosX, areaSizeW, blockSizeW, hud.offsetX);
		float posY = align(vertical(hud.anchor), areaPosY, areaSizeH, blockSizeH, hud.offsetY);

		PoseStack pose = gfx.pose();
		pose.pushPose();
		pose.translate(posX, posY, 0.0F);
		pose.scale(scale, scale, 1.0F);

		if (hud.background)
		{
			int pad = hud.backgroundPadding;
			gfx.fill(-pad, -pad, textSizeW + pad, textSizeH + pad,
					(hud.backgroundAlpha << 24) | (hud.backgroundColor & 0xFFFFFF));
		}

		int linePosY = 0;
		for (String line : lines)
		{
			gfx.drawString(font, line, 0, linePosY, hud.color, hud.shadow);
			linePosY += lineSizeH;
		}

		pose.popPose();
	}

	// 锚点的横向分量：left / center / right
	public static String horizontal(String anchor)
	{
		int split = anchor == null ? -1 : anchor.indexOf('_');
		return split < 0 ? "left" : anchor.substring(split + 1);
	}

	// 锚点的纵向分量：top / middle / bottom
	public static String vertical(String anchor)
	{
		int split = anchor == null ? -1 : anchor.indexOf('_');
		return split < 0 ? "top" : anchor.substring(0, split);
	}

	// 单个轴上的落点：起点 / 居中 / 贴末边，再叠加玩家偏移
	private static float align(String part, int areaStart, int areaSize, float blockSize, int offset)
	{
		float base = switch (part)
		{
			case "center", "middle" -> areaStart + (areaSize - blockSize) / 2.0F;
			case "right", "bottom" -> areaStart + areaSize - blockSize;
			default -> areaStart;
		};
		return base + offset;
	}
}

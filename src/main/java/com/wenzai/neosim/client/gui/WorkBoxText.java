package com.wenzai.neosim.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

// 工作盒主页文本排版：左边界 width/2-120、从 y=45 起，普通行下移 18、职工等级行下移 14（缩进 12）
// 农业盒 / 矿业盒 / 快递盒主页共用这一份，谁也别再手写坐标
public final class WorkBoxText
{
	private static final int LEFT_GAP = 120;
	private static final int TOP_Y = 45;
	private static final int LINE_H = 18;
	private static final int LEVEL_H = 14;
	private static final int LEVEL_INDENT = 12;

	private final GuiGraphics gfx;
	private final Font font;
	private final int posX;
	private int posY;

	public WorkBoxText(GuiGraphics gfx, Font font, int screenWidth)
	{
		this.gfx = gfx;
		this.font = font;
		this.posX = screenWidth / 2 - LEFT_GAP;
		this.posY = TOP_Y;
	}

	// 普通行：画完下移一行
	public void line(Component text, int color)
	{
		gfx.drawString(font, text, posX, posY, color);
		posY += LINE_H;
	}

	// 职工行：姓名在当前行，等级在下一行缩进 LEVEL_INDENT；整块下移 LEVEL_H + LINE_H
	public void workerLine(Component name, Component level)
	{
		gfx.drawString(font, name, posX, posY, 0xFFFFFF);
		gfx.drawString(font, level, posX + LEVEL_INDENT, posY + LEVEL_H, 0xCCCCCC);
		posY += LEVEL_H + LINE_H;
	}
}

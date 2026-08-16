package com.wenzai.neosim.util;

// 防玩家删改：从文件读回的坐标统一钳制到合法范围
public final class SafeCoord
{
	// 世界边界
	public static final int WORLD_BORDER = 30_000_000;

	// 主世界建造高度
	public static final int MIN_Y = -64;
	public static final int MAX_Y = 319;

	private SafeCoord() {}

	public static int clampInt(int v, int min, int max)
	{
		return Math.max(min, Math.min(max, v));
	}

	// 钳制到世界边界
	public static int clampX(int v)
	{
		return clampInt(v, -WORLD_BORDER, WORLD_BORDER);
	}

	// 钳制到主世界建造高度
	public static int clampY(int v)
	{
		return clampInt(v, MIN_Y, MAX_Y);
	}
}

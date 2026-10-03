package com.wenzai.neosim.compat.attached;

import java.util.Locale;

import javax.annotation.Nullable;

// 依附方式：决定方块在第几轮放置、按哪种支撑修正朝向、是否做 canSurvive 兜底预检
// AUTO：未命中任何规则时的默认，第一轮放置但放置前做 canSurvive 预检，不满足就推迟
// NONE：明确移出依附方块表，第一轮放置且不做预检
// ANY：第二轮放置，只延后，不修正朝向
// WALL：第二轮放置，按实际支撑面修正水平朝向
// GROUND：第二轮放置，要求下方非空气
// CEILING：第二轮放置，要求上方非空气（灯笼 / 链 / 挂式告示牌等）
public enum AttachMode
{
	AUTO,
	NONE,
	ANY,
	WALL,
	GROUND,
	CEILING;

	// 放置轮次：1=实心轮，2=依附轮
	public int phase()
	{
		return this == ANY || this == WALL || this == GROUND || this == CEILING ? 2 : 1;
	}

	public boolean attached()
	{
		return phase() == 2;
	}

	// 是否做 canSurvive 预检兜底（NONE 是玩家明确声明"不是依附方块"，跳过预检）
	public boolean precheck()
	{
		return this != NONE;
	}

	public String id()
	{
		return name().toLowerCase(Locale.ROOT);
	}

	public String translationKey()
	{
		return "gui.neosim.config.mode." + id();
	}

	// GUI 循环切换用
	public AttachMode next()
	{
		AttachMode[] values = values();
		return values[(ordinal() + 1) % values.length];
	}

	@Nullable
	public static AttachMode byId(String id)
	{
		if (id == null) return null;
		String key = id.trim().toLowerCase(Locale.ROOT);
		for (AttachMode mode : values())
		{
			if (mode.id().equals(key)) return mode;
		}
		return null;
	}
}

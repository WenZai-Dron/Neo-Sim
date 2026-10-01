package com.wenzai.neosim.npc;

import java.util.Locale;

// NPC 命名风格：中文为"姓+名"，英文为"名 空格 姓"
public enum NameLocale
{
	ZH("zh"),
	EN("en");

	private final String key;

	NameLocale(String key)
	{
		this.key = key;
	}

	public String key()
	{
		return key;
	}

	// 存档/配置键解析；无法识别返回 null
	public static NameLocale fromKey(String key)
	{
		if (key == null) return null;
		return switch (key.trim().toLowerCase(Locale.ROOT))
		{
			case "zh" -> ZH;
			case "en" -> EN;
			default -> null;
		};
	}

	// 客户端上报的语言代码解析（zh_cn / zh_tw / en_us ...）；非中文一律按英文池
	public static NameLocale fromLanguage(String language)
	{
		if (language != null && language.toLowerCase(Locale.ROOT).startsWith("zh")) return ZH;
		return EN;
	}
}

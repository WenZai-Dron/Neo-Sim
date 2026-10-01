package com.wenzai.neosim.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// 建筑名本地化：中文环境查汉化表，其他语言原样显示蓝图自带英文名
public class BuildingNameLocalizer
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String RESOURCE = "/assets/neo_sim/buildings/zh_cn_names.json";
	private static final Map<String, String> TRANSLATIONS = new HashMap<>();
	private static boolean loaded;

	private BuildingNameLocalizer()
	{
	}

	// 返回显示名：中文环境返回中文名（未翻译则原样返回）；其他语言一律返回蓝图原始英文名
	public static String localize(String originalName)
	{
		if (originalName == null) return "";
		if (!isChineseLocale()) return originalName;
		ensureLoaded();
		String zh = TRANSLATIONS.get(originalName);
		return zh != null ? zh : originalName;
	}

	// 当前游戏语言是否为中文（zh_cn / zh_tw / zh_hk ...）
	private static boolean isChineseLocale()
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) return false;
		String lang = mc.getLanguageManager().getSelected();
		return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("zh");
	}

	private static void ensureLoaded()
	{
		if (loaded) return;
		loaded = true;
		try (InputStream is = BuildingNameLocalizer.class.getResourceAsStream(RESOURCE))
		{
			if (is == null)
			{
				LOGGER.warn("NeoSim-BuildingNameLocalizer: translation table not found at {}", RESOURCE);
				return;
			}
			try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8))
			{
				JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
				for (Map.Entry<String, JsonElement> e : root.entrySet())
				{
					TRANSLATIONS.put(e.getKey(), e.getValue().getAsString());
				}
			}
			LOGGER.info("NeoSim-BuildingNameLocalizer: loaded {} building name translations", TRANSLATIONS.size());
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-BuildingNameLocalizer: failed to load translations", e);
		}
	}
}

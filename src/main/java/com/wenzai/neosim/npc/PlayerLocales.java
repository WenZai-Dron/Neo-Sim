package com.wenzai.neosim.npc;

import com.wenzai.neosim.storage.CityManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

// 服务端记录各客户端上报的语言，用于"新建 NPC 用谁的命名池"
public final class PlayerLocales
{
	// 玩家语言表：玩家 UUID 到客户端上报语言
	private static final Map<UUID, NameLocale> PLAYERS = new ConcurrentHashMap<>();

	// 城市语言表：城市名到该城市最近一次玩家语言（无玩家上下文的自动补人/出生用它兜底）
	private static final Map<String, NameLocale> CITIES = new ConcurrentHashMap<>();

	private PlayerLocales()
	{
	}

	// 客户端上报入口：解析语言代码并同时更新玩家表与（已知城市时）城市表
	public static void update(UUID player, String language, String cityName)
	{
		NameLocale locale = NameLocale.fromLanguage(language);
		if (player != null) PLAYERS.put(player, locale);
		if (cityName != null && !cityName.isEmpty()) CITIES.put(cityName, locale);
	}

	public static void remove(UUID player)
	{
		if (player != null) PLAYERS.remove(player);
	}

	@Nullable
	public static NameLocale of(UUID player)
	{
		return player == null ? null : PLAYERS.get(player);
	}

	@Nullable
	public static NameLocale ofCity(String cityName)
	{
		return cityName == null || cityName.isEmpty() ? null : CITIES.get(cityName);
	}

	// 玩家入城成功后，把其已知语言绑定到该城市（供无玩家上下文的自动补人使用）
	public static void bindCity(UUID player, String cityName)
	{
		NameLocale locale = player == null ? null : PLAYERS.get(player);
		if (locale != null && cityName != null && !cityName.isEmpty()) CITIES.put(cityName, locale);
	}

	// 城市最近一次语言未知时的兜底：取该城市在线玩家的语言
	// （玩家先上报语言、之后才入城的情形，城市表里不会有记录）
	@Nullable
	public static NameLocale firstOnlineInCity(String cityName)
	{
		if (cityName == null || cityName.isEmpty()) return null;
		MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		if (server == null) return null;
		for (ServerPlayer player : server.getPlayerList().getPlayers())
		{
			if (!cityName.equals(CityManager.getCity(player.getUUID()))) continue;
			NameLocale locale = PLAYERS.get(player.getUUID());
			if (locale != null) return locale;
		}
		return null;
	}

	public static void clear()
	{
		PLAYERS.clear();
		CITIES.clear();
	}
}

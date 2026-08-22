package com.wenzai.neosim.storage;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.npc.Manage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// 服务端权威的"玩家 → 城市"会话表。
// 替代全局静态 activeCityName 的多人语义：每个玩家独立属于一个城市；
// 城市只在有玩家在线时演化（LifeSystem 遍历 onlineCities）。
// 模式为全服全局（立项基线 0.1），不按城市存储。
public final class CityManager
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final ConcurrentMap<UUID, String> CITY_BY_PLAYER = new ConcurrentHashMap<>();

	private CityManager()
	{
	}

	// ---- 会话 ----

	public static String getCity(UUID uuid)
	{
		return CITY_BY_PLAYER.getOrDefault(uuid, "");
	}

	public static String getCity(Player player)
	{
		return getCity(player.getUUID());
	}

	// 玩家加入：从档案解析并登记；无档案则登记空串（等待客户端创建/加入城市）
	public static void onPlayerJoin(ServerLevel level, Player player)
	{
		String city = FileCreater.findPlayerCity(level, player.getName().getString());
		CITY_BY_PLAYER.put(player.getUUID(), city);
		if (!city.isEmpty())
		{
			LOGGER.info("NeoSim-CityManager: {} -> city '{}'", player.getName().getString(), city);
		}
	}

	public static void onPlayerLogout(UUID uuid)
	{
		CITY_BY_PLAYER.remove(uuid);
	}

	// 在线玩家所属的不同城市（按会话表去重；join/logout 维护，等价于在线玩家）
	public static Set<String> onlineCities(ServerLevel level)
	{
		Set<String> cities = new LinkedHashSet<>();
		for (String c : CITY_BY_PLAYER.values())
		{
			if (c != null && !c.isEmpty()) cities.add(c);
		}
		return cities;
	}

	// 服务器停止：清空会话表，防止下个存档读到残留
	public static void clear()
	{
		CITY_BY_PLAYER.clear();
	}

	// ---- 城市操作（服务端权威；替代客户端 City.java 的本地文件写入）----

	// 创建城市：建目录 + 写 player.json + 初始化 CityData + 补第一个市民。返回 null=成功，否则为提示文本
	public static String createCity(ServerLevel level, Player player, String cityName)
	{
		if (cityName == null || cityName.isBlank()) return "§c城市名不能为空";
		if (cityName.contains("/") || cityName.contains("\\") || cityName.contains(".."))
		{
			return "§c城市名含有非法字符";
		}
		if (Manage.cityExists(level, cityName)) return "§c城市已存在";
		if (!getCity(player.getUUID()).isEmpty()) return "§c你已经加入了城市";

		FileCreater.createCityFolder(level, cityName);
		FileCreater.savePlayerToCity(level, cityName, player.getName().getString());
		SimData.CityData.write(level, cityName, SimData.CityData.DEFAULT);
		CITY_BY_PLAYER.put(player.getUUID(), cityName);

		// 生成第一个市民并同步数据（HUD 刷新）
		Manage.replenishPopulation(level, cityName);
		ModSavedData.get(level).syncCityToClients(level, cityName);
		LOGGER.info("NeoSim-CityManager: created city '{}' by {}", cityName, player.getName().getString());
		return null;
	}

	// 加入已有城市。返回 null=成功，否则为提示文本
	public static String joinCity(ServerLevel level, Player player, String cityName)
	{
		if (cityName == null || cityName.isBlank()) return "§c城市名不能为空";
		if (!Manage.cityExists(level, cityName)) return "§c城市不存在";
		String mine = getCity(player.getUUID());
		if (!mine.isEmpty()) return mine.equals(cityName) ? null : "§c你已经加入了其他城市";

		FileCreater.savePlayerToCity(level, cityName, player.getName().getString());
		CITY_BY_PLAYER.put(player.getUUID(), cityName);
		ModSavedData.get(level).syncCityToClients(level, cityName);
		LOGGER.info("NeoSim-CityManager: {} joined city '{}'", player.getName().getString(), cityName);
		return null;
	}
}

package com.wenzai.neosim.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@EventBusSubscriber(modid = NeoSim.MOD_ID)
public class FileCreater
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// player.json 成员缓存（城市→玩家集合按文件路径+mtime 键控）
	private static final java.util.Map<Path, PlayerListCache> PLAYER_CACHE = new java.util.HashMap<>();

	// 单文件缓存项（mtime 未变直接命中）
	private static final class PlayerListCache
	{
		final long mtime;
		final Set<String> players;

		PlayerListCache(long mtime, Set<String> players)
		{
			this.mtime = mtime;
			this.players = players;
		}
	}

	// 服务器停止时清空（防跨存档残留）
	public static void clearPlayerCache()
	{
		PLAYER_CACHE.clear();
	}

	public static void createNeoSimFolders()
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path neoSimDir = gameDir.resolve("NeoSim");
		Path skinDir = neoSimDir.resolve("Skins");
		Path buildingsDir = neoSimDir.resolve("Buildings");
		Path dataDir = neoSimDir.resolve("data");

		if (!Files.exists(skinDir))
		{
			try
			{
				Files.createDirectories(skinDir);
				LOGGER.info("NeoSim-createNeoSimFolders: Succeed, {}", skinDir.toAbsolutePath());
			}
			catch (IOException e)
			{
				LOGGER.error("NeoSim-createNeoSimFolders: Fail, {}", e.getMessage(), e);
			}
		}

		if (!Files.exists(buildingsDir))
		{
			try
			{
				Files.createDirectories(buildingsDir);
				LOGGER.info("NeoSim-createNeoSimFolders: Succeed, {}", buildingsDir.toAbsolutePath());
			}
			catch (IOException e)
			{
				LOGGER.error("NeoSim-createNeoSimFolders: Fail, {}", e.getMessage(), e);
			}
		}

		if (!Files.exists(dataDir))
		{
			try
			{
				Files.createDirectories(dataDir);
				LOGGER.info("NeoSim-createNeoSimFolders: Succeed, {}", dataDir.toAbsolutePath());
			}
			catch (IOException e)
			{
				LOGGER.error("NeoSim-createNeoSimFolders: Fail, {}", e.getMessage(), e);
			}
		}
	}

	// 服务端
	public static void createCityFolder(String cityName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path cityDir = gameDir.resolve("NeoSim").resolve("data").resolve(cityName);

		if (!Files.exists(cityDir))
		{
			try
			{
				Files.createDirectories(cityDir);
				LOGGER.info("NeoSim-createCityFolder: Succeed, {}", cityDir.toAbsolutePath());
			}
			catch (IOException e)
			{
				LOGGER.error("NeoSim-createCityFolder: Fail, {}", e.getMessage(), e);
			}
		}

		createNpcFolder(cityDir);
	}

	// 客户端
	public static void createCityFolder(String cityName, String saveName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path cityDir = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName);

		if (!Files.exists(cityDir))
		{
			try
			{
				Files.createDirectories(cityDir);
				LOGGER.info("NeoSim-createCityFolder: Succeed, {}", cityDir.toAbsolutePath());
			}
			catch (IOException e)
			{
				LOGGER.error("NeoSim-createCityFolder: Fail, {}", e.getMessage(), e);
			}
		}

		createNpcFolder(cityDir);
	}

	private static void createNpcFolder(Path cityDir)
	{
		Path npcDir = cityDir.resolve("npc");

		if (!Files.exists(npcDir))
		{
			try
			{
				Files.createDirectories(npcDir);
				LOGGER.info("NeoSim-createNpcFolder: Succeed, {}", npcDir.toAbsolutePath());
			}
			catch (IOException e)
			{
				LOGGER.error("NeoSim-createNpcFolder: Fail, {}", e.getMessage(), e);
			}
		}
	}

	// 服务端
	public static void savePlayerToCity(String cityName, String playerName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path cityDir = gameDir.resolve("NeoSim").resolve("data").resolve(cityName);
		Path playerFile = cityDir.resolve("player.json");
		writePlayerJson(playerFile, playerName);
		createInitialDataJson(cityDir);
	}

	// 客户端
	public static void savePlayerToCity(String cityName, String saveName, String playerName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path cityDir = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName);
		Path playerFile = cityDir.resolve("player.json");
		writePlayerJson(playerFile, playerName);
		createInitialDataJson(cityDir);
	}

	// 在城市目录下创建初始data.json
	private static void createInitialDataJson(Path cityDir)
	{
		Path dataFile = cityDir.resolve("data.json");
		if (!Files.exists(dataFile))
		{
			JsonObject json = new JsonObject();
			SimData.CityData.DEFAULT.toJson(json);
			JsonUtil.write(dataFile, json);
			LOGGER.info("NeoSim-createInitialDataJson: Succeed, {}", dataFile.toAbsolutePath());
		}
	}

	// 服务端：检查玩家是否在player.json中
	public static boolean isPlayerInCity(String cityName, String playerName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path playerFile = gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("player.json");
		return checkPlayerInFile(playerFile, playerName);
	}

	// 客户端：检查玩家是否在player.json中
	public static boolean isPlayerInCity(String cityName, String saveName, String playerName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path playerFile = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName).resolve("player.json");
		return checkPlayerInFile(playerFile, playerName);
	}

	// 列出所有已有城市
	public static List<String> listCities(ServerLevel level)
	{
		Path dataDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		if (!level.getServer().isDedicatedServer())
		{
			dataDir = dataDir.resolve(level.getServer().getWorldData().getLevelName());
		}
		if (!Files.isDirectory(dataDir)) return List.of();
		try (var entries = Files.list(dataDir))
		{
			return entries.filter(Files::isDirectory)
					.filter(d -> Files.exists(d.resolve("player.json")))
					.map(d -> d.getFileName().toString())
					.sorted()
					.toList();
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-listCities: Fail, {}", e.getMessage(), e);
			return List.of();
		}
	}

	// 玩家列表文件损坏：备份.bak 后重建默认
	private static void repairCorruptedPlayerJson(Path playerFile)
	{
		JsonUtil.backupCorrupted(playerFile);
		JsonObject json = new JsonObject();
		json.add("players", new JsonArray());
		JsonUtil.write(playerFile, json);
		LOGGER.warn("NeoSim-player.json corrupted, backed up and rebuilt: {}", playerFile.toAbsolutePath());
	}

	// player.json 成员关系内存化——按文件路径缓存 mtime + 玩家集合，mtime 未变直接命中，
	// 消灭公告/同步循环里每玩家每次读盘 + 解析（isPlayerInCity 高频调用点：ModSavedData.syncCityToClients / LifeSystem 公告 / CityLivingManager）
	private static boolean checkPlayerInFile(Path playerFile, String playerName)
	{
		if (!Files.exists(playerFile))
		{
			return false;
		}

		// mtime 未变 → 命中缓存集合
		Set<String> players = null;
		PlayerListCache cached = PLAYER_CACHE.get(playerFile);
		try
		{
			long mtime = Files.getLastModifiedTime(playerFile).toMillis();
			if (cached != null && cached.mtime == mtime)
			{
				players = cached.players;
			}
			else
			{
				JsonObject json = JsonUtil.readObject(playerFile);
				if (json == null)
				{
					repairCorruptedPlayerJson(playerFile);
					return false;
				}
				players = new HashSet<>();
				for (JsonElement e : JsonUtil.getArray(json, "players"))
				{
					if (e.isJsonPrimitive()) players.add(e.getAsString());
				}
				PLAYER_CACHE.put(playerFile, new PlayerListCache(mtime, players));
			}
		}
		catch (Exception e)
		{
			// 读失败按"不在列表"处理（保持旧行为）
			return false;
		}
		return players.contains(playerName);
	}

	// 写 player.json 后失效缓存（加入/创建城市时调用，避免旧 mtime 内容）
	private static void invalidatePlayerCache(Path playerFile)
	{
		PLAYER_CACHE.remove(playerFile);
	}

	private static void writePlayerJson(Path playerFile, String playerName)
	{
		List<String> players = new ArrayList<>();

		// 读取玩家列表
		if (Files.exists(playerFile))
		{
			JsonObject json = JsonUtil.readObject(playerFile);
			if (json == null)
			{
				repairCorruptedPlayerJson(playerFile);
			}
			else
			{
				for (JsonElement e : JsonUtil.getArray(json, "players"))
				{
					if (e.isJsonPrimitive()) players.add(e.getAsString());
				}
			}
		}

		// 去重添加
		if (!players.contains(playerName))
		{
			players.add(playerName);
		}

		// 写入
		JsonObject json = new JsonObject();
		JsonArray arr = new JsonArray();
		for (String p : players)
		{
			arr.add(p);
		}
		json.add("players", arr);
		JsonUtil.write(playerFile, json);
		invalidatePlayerCache(playerFile);
		LOGGER.info("NeoSim-writePlayerJson: Succeed, {}", playerFile.toAbsolutePath());
	}

	// 服务端：查找玩家所属城市
	public static String findPlayerCity(String playerName)
	{
		Path dataDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		if (!Files.isDirectory(dataDir)) return "";

		try (var entries = Files.list(dataDir))
		{
			for (Path entry : entries.toList())
			{
				if (Files.isDirectory(entry) && Files.exists(entry.resolve("npc")))
				{
					Path playerFile = entry.resolve("player.json");
					if (checkPlayerInFile(playerFile, playerName))
					{
						return entry.getFileName().toString();
					}
				}
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-findPlayerCity: Fail, {}", e.getMessage(), e);
		}
		return "";
	}

	// 客户端：查找玩家所属城市
	public static String findPlayerCity(String saveName, String playerName)
	{
		Path dataDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data").resolve(saveName);
		if (!Files.isDirectory(dataDir)) return "";

		try (var entries = Files.list(dataDir))
		{
			for (Path entry : entries.toList())
			{
				if (Files.isDirectory(entry) && Files.exists(entry.resolve("npc")))
				{
					Path playerFile = entry.resolve("player.json");
					if (checkPlayerInFile(playerFile, playerName))
					{
						return entry.getFileName().toString();
					}
				}
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-findPlayerCity: Fail, {}", e.getMessage(), e);
		}
		return "";
	}

	// ---- 按 ServerLevel 自动分流的统一入口（新代码一律使用这些）----

	public static void createCityFolder(ServerLevel level, String cityName)
	{
		if (level.getServer().isDedicatedServer())
		{
			createCityFolder(cityName);
		}
		else
		{
			createCityFolder(cityName, level.getServer().getWorldData().getLevelName());
		}
	}

	public static void savePlayerToCity(ServerLevel level, String cityName, String playerName)
	{
		if (level.getServer().isDedicatedServer())
		{
			savePlayerToCity(cityName, playerName);
		}
		else
		{
			savePlayerToCity(cityName, level.getServer().getWorldData().getLevelName(), playerName);
		}
	}

	public static boolean isPlayerInCity(ServerLevel level, String cityName, String playerName)
	{
		return level.getServer().isDedicatedServer()
				? isPlayerInCity(cityName, playerName)
				: isPlayerInCity(cityName, level.getServer().getWorldData().getLevelName(), playerName);
	}

	public static String findPlayerCity(ServerLevel level, String playerName)
	{
		return level.getServer().isDedicatedServer()
				? findPlayerCity(playerName)
				: findPlayerCity(level.getServer().getWorldData().getLevelName(), playerName);
	}

	@SubscribeEvent
	public static void onServerStarting(ServerStartingEvent event)
	{
		createNeoSimFolders();
	}
}

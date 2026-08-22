package com.wenzai.neosim.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.network.ServerToClientPayloads;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ModSavedData
{
	private static final Path DATA_DIR = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");

	private static ModSavedData INSTANCE;
	private static String activeCityName = "";

	private Path dataFile;
	private SimData data = SimData.DEFAULT;
	// 历史字段：Run/City 向导不再由这两个标志门控（改为按"模式已选/已入城"需求状态判断，见 NeoSim.handlePlayerJoin）；
	// 保留读写仅为兼容旧 data.json，不参与任何行为。
	private boolean runGuiSent = false;
	private final Set<String> joinedPlayers = new HashSet<>();

	// 实例所属世界
	private ServerLevel level;

	private ModSavedData()
	{
	}

	public static void setActiveCityName(String name)
	{
		activeCityName = name;
	}

	public static String getActiveCityName()
	{
		return activeCityName;
	}

	private static Path resolvePath(ServerLevel level)
	{
		boolean isDedicated = level.getServer().isDedicatedServer();
		if (isDedicated)
		{
			return DATA_DIR.resolve("data.json");
		}
		else
		{
			String saveName = level.getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).getParent().getFileName().toString();
			return DATA_DIR.resolve(saveName).resolve("data.json");
		}
	}

	// 文件持久化
	private void loadFromFile()
	{
		if (Files.exists(dataFile))
		{
			JsonObject json = JsonUtil.readObject(dataFile);
			if (json == null)
			{
				// 内容被篡改/清空：备份.bak后按默认值重建
				JsonUtil.backupCorrupted(dataFile);
				saveToFile();
				NeoSim.LOGGER.warn("NeoSim-loadFromFile: corrupted, backed up and rebuilt: {}", dataFile);
				return;
			}
			data = SimData.DEFAULT;
			data = data.withMode(JsonUtil.getByte(json, "mode", data.mode()));
			data = data.withDayOfWeek(JsonUtil.getInt(json, "dayOfWeek", data.dayOfWeek()));
			runGuiSent = JsonUtil.getBoolean(json, "runGuiSent", false);
			for (JsonElement e : JsonUtil.getArray(json, "joinedPlayers"))
			{
				if (e.isJsonPrimitive()) joinedPlayers.add(e.getAsString());
			}
			NeoSim.LOGGER.info("NeoSim-loadFromFile: {}", dataFile);
		}
		else
		{
			saveToFile();
		}
	}

	private void saveToFile()
	{
		JsonObject json = new JsonObject();
		json.addProperty("mode", data.mode());
		json.addProperty("dayOfWeek", data.dayOfWeek());
		json.addProperty("runGuiSent", runGuiSent);
		JsonArray arr = new JsonArray();
		for (String uuid : joinedPlayers)
		{
			arr.add(uuid);
		}
		json.add("joinedPlayers", arr);
		JsonUtil.write(dataFile, json);
		NeoSim.LOGGER.debug("NeoSim-saveToFile: {}", dataFile);
	}

	// 网络同步：全局数据（mode/dayOfWeek）按在线城市逐城合并发送；无城市玩家单独发全局数据
	private void syncToClients(ServerLevel level)
	{
		for (String city : CityManager.onlineCities(level))
		{
			syncCityToClients(level, city);
		}
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers())
		{
			if (CityManager.getCity(player.getUUID()).isEmpty())
			{
				PacketDistributor.sendToPlayer(player,
						new ServerToClientPayloads.SyncDataPayload(getData(), ""));
			}
		}
	}

	public SimData getData()
	{
		SimData.CityData city = readCityData();
		if (city == null) return data;
		return data.withPopulation(city.population())
				.withDay(city.day())
				.withCredit(city.credit());
	}

	// 按城市读取（模式为全服全局，沿用 data.mode()；Task 3.x 已立项不做城市模式）
	public SimData getData(String city)
	{
		if (city == null || city.isEmpty())
		{
			return getData();
		}
		SimData.CityData cd = SimData.CityData.read(level, city);
		if (cd == null) return getData();
		return data.withPopulation(cd.population())
				.withDay(cd.day())
				.withCredit(cd.credit());
	}

	// 全量替换并同步一次
	public void setData(SimData newData, ServerLevel level)
	{
		this.data = data.withMode(newData.mode()).withDayOfWeek(newData.dayOfWeek());
		saveToFile();
		SimData.CityData city = readCityData();
		writeCityData(level, (city != null ? city : SimData.CityData.DEFAULT)
				.withPopulation(newData.population())
				.withDay(newData.day())
				.withCredit(newData.credit()));
		syncToClients(level);
	}

	public byte getMode()
	{
		return data.mode();
	}

	public void setMode(byte mode, ServerLevel level)
	{
		this.data = data.withMode(mode);
		saveToFile();
		syncToClients(level);
	}

	public int getDayOfWeek()
	{
		return data.dayOfWeek();
	}

	public void setDayOfWeek(int dayOfWeek, ServerLevel level)
	{
		this.data = data.withDayOfWeek(dayOfWeek);
		saveToFile();
		syncToClients(level);
	}

	public short getPopulation()
	{
		SimData.CityData city = readCityData();
		return city != null ? city.population() : data.population();
	}

	// 按城市读取人口（内存缓存，不再目录列举）
	public short getPopulation(String city)
	{
		if (city == null || city.isEmpty())
		{
			return getPopulation();
		}
		SimData.CityData cd = SimData.CityData.read(level, city);
		return cd != null ? cd.population() : data.population();
	}

	public void setPopulation(short population, ServerLevel level)
	{
		int maxPop = Config.MAX_POPULATION.get();
		short clamped = population > maxPop ? (short) maxPop : population;
		SimData.CityData city = readCityData();
		writeCityData(level, (city != null ? city : SimData.CityData.DEFAULT).withPopulation(clamped));
		syncToClients(level);
	}

	// 按城市写入人口（多人：各城市独立人口）
	public void setPopulation(String city, short population, ServerLevel level)
	{
		int maxPop = Config.MAX_POPULATION.get();
		short clamped = population > maxPop ? (short) maxPop : population;
		if (city == null || city.isEmpty())
		{
			setPopulation(clamped, level);
			return;
		}
		SimData.CityData cd = SimData.CityData.read(level, city);
		SimData.CityData.write(level, city, (cd != null ? cd : SimData.CityData.DEFAULT).withPopulation(clamped));
		syncCityToClients(level, city);
	}

	public int getDay()
	{
		SimData.CityData city = readCityData();
		return city != null ? city.day() : data.day();
	}

	public void setDay(int day, ServerLevel level)
	{
		SimData.CityData city = readCityData();
		writeCityData(level, (city != null ? city : SimData.CityData.DEFAULT).withDay(day));
		syncToClients(level);
	}

	public double getCredit()
	{
		SimData.CityData city = readCityData();
		return city != null ? city.credit() : data.credit();
	}

	public void setCredit(double credit, ServerLevel level)
	{
		SimData.CityData city = readCityData();
		writeCityData(level, (city != null ? city : SimData.CityData.DEFAULT).withCredit(credit));
		syncToClients(level);
	}

	// 按城市写入信用点（多人：各城市独立结算，收租/扣款按城市）
	public void setCredit(String city, double credit, ServerLevel level)
	{
		if (city == null || city.isEmpty())
		{
			setCredit(credit, level);
			return;
		}
		SimData.CityData cd = SimData.CityData.read(level, city);
		SimData.CityData.write(level, city, (cd != null ? cd : SimData.CityData.DEFAULT).withCredit(credit));
		syncCityToClients(level, city);
	}

	// 数据同步给城市的在线玩家
	public void syncCityToClients(ServerLevel level, String cityName)
	{
		SimData.CityData city = SimData.CityData.read(level, cityName);
		SimData view = getData()
				.withPopulation(city.population())
				.withDay(city.day())
				.withCredit(city.credit());
		ServerToClientPayloads.SyncDataPayload payload = new ServerToClientPayloads.SyncDataPayload(view, cityName);
		boolean dedicated = level.getServer().isDedicatedServer();
		String saveName = dedicated ? null : level.getServer().getWorldData().getLevelName();
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers())
		{
			String pname = player.getName().getString();
			boolean inCity = dedicated
					? FileCreater.isPlayerInCity(cityName, pname)
					: FileCreater.isPlayerInCity(cityName, saveName, pname);
			if (inCity)
			{
				PacketDistributor.sendToPlayer(player, payload);
			}
		}
	}

	// 城市级数据读写（活跃城市）
	private SimData.CityData readCityData()
	{
		String city = getActiveCityName();
		if (city.isEmpty() || level == null) return null;
		return SimData.CityData.read(level, city);
	}

	private void writeCityData(ServerLevel level, SimData.CityData newData)
	{
		String city = getActiveCityName();
		if (city.isEmpty()) return;
		SimData.CityData.write(level, city, newData);
	}

	public boolean isRunGuiSent()
	{
		return runGuiSent;
	}

	public void setRunGuiSent(boolean sent)
	{
		this.runGuiSent = sent;
		saveToFile();
	}

	public boolean isPlayerJoined(UUID uuid)
	{
		return joinedPlayers.contains(uuid.toString());
	}

	public void markPlayerJoined(UUID uuid)
	{
		joinedPlayers.add(uuid.toString());
		saveToFile();
	}

	public void incrementDay(ServerLevel level)
	{
		this.data = data.withDayOfWeek((data.dayOfWeek() + 1) % 7);

		saveToFile();
		// day 按在线城市推进（多人：各城市独立日期）
		for (String city : CityManager.onlineCities(level))
		{
			SimData.CityData cd = SimData.CityData.read(level, city);
			SimData.CityData.write(level, city, cd.withDay(cd.day() + 1));
			syncCityToClients(level, city);
		}
	}

	// 获取实例
	public static ModSavedData get(ServerLevel level)
	{
		Path expectedPath = resolvePath(level);
		if (INSTANCE == null || !expectedPath.equals(INSTANCE.dataFile))
		{
			INSTANCE = new ModSavedData();
			INSTANCE.dataFile = expectedPath;
			INSTANCE.loadFromFile();
		}
		INSTANCE.level = level;
		return INSTANCE;
	}

	// L3：服务器停止时置空单例（释放 ServerLevel 引用，防关档后钉住旧世界）
	public static void resetInstance()
	{
		INSTANCE = null;
		activeCityName = "";
	}
}

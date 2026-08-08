package com.wenzai.neosim.storage;

import com.google.gson.*;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.network.ServerToClientPayloads;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ModSavedData
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_DIR = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");

    private static ModSavedData INSTANCE;
    private static String activeCityName = "";

    private Path dataFile;
    private SimData data = SimData.DEFAULT;
    private boolean runGuiSent = false;
    private final Set<String> joinedPlayers = new HashSet<>();
    
    // 实例所属世界
    private ServerLevel level;

    private ModSavedData() {}

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
            try (Reader reader = Files.newBufferedReader(dataFile))
            {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                data = SimData.DEFAULT;
                if (json.has("mode")) data = data.withMode(json.get("mode").getAsByte());
                if (json.has("dayOfWeek")) data = data.withDayOfWeek(json.get("dayOfWeek").getAsInt());
                runGuiSent = json.has("runGuiSent") && json.get("runGuiSent").getAsBoolean();
                if (json.has("joinedPlayers"))
                {
                    JsonArray arr = json.getAsJsonArray("joinedPlayers");
                    for (JsonElement e : arr)
                    {
                        joinedPlayers.add(e.getAsString());
                    }
                }
                NeoSim.LOGGER.info("NeoSim-loadFromFile: {}", dataFile);
            }
            catch (IOException e)
            {
                NeoSim.LOGGER.error("NeoSim-loadFromFile: {}", e.getMessage(), e);
            }
        }
        else
        {
            saveToFile();
        }
    }

    private void saveToFile()
    {
        try
        {
            Files.createDirectories(dataFile.getParent());
            try (Writer writer = Files.newBufferedWriter(dataFile))
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
                GSON.toJson(json, writer);
                NeoSim.LOGGER.info("NeoSim-saveToFile: {}", dataFile);
            }
        }
        catch (IOException e)
        {
            NeoSim.LOGGER.error("NeoSim-saveToFile: {}", e.getMessage(), e);
        }
    }

    // 网络同步：仅同步给player.json中含有的玩家vbb
    private void syncToClients(ServerLevel level)
    {
        ServerToClientPayloads.SyncDataPayload payload = new ServerToClientPayloads.SyncDataPayload(getData(), getActiveCityName());
        String cityName = getActiveCityName();
        if (cityName.isEmpty())
        {
            level.players().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
            return;
        }
        boolean isDedicated = level.getServer().isDedicatedServer();
        String saveName = isDedicated ? null : level.getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).getParent().getFileName().toString();
        level.players().forEach(player -> {
            String playerName = player.getName().getString();
            boolean authorized;
            if (isDedicated)
            {
                authorized = FileCreater.isPlayerInCity(cityName, playerName);
            }
            else
            {
                authorized = FileCreater.isPlayerInCity(cityName, saveName, playerName);
            }
            if (authorized)
            {
                PacketDistributor.sendToPlayer(player, payload);
            }
        });
    }

    public SimData getData()
    {
        SimData.CityData city = readCityData();
        if (city == null) return data;
        return data.withPopulation(city.population())
                .withDay(city.day())
                .withCredit(city.credit());
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

    public byte getMode() { return data.mode(); }
    public void setMode(byte mode, ServerLevel level)
    {
        this.data = data.withMode(mode);
        saveToFile();
        syncToClients(level);
    }

    public int getDayOfWeek() { return data.dayOfWeek(); }
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
    public void setPopulation(short population, ServerLevel level)
    {
        int maxPop = Config.MAX_POPULATION.get();
        short clamped = population > maxPop ? (short) maxPop : population;
        SimData.CityData city = readCityData();
        writeCityData(level, (city != null ? city : SimData.CityData.DEFAULT).withPopulation(clamped));
        syncToClients(level);
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

    public boolean isRunGuiSent() { return runGuiSent; }
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
        SimData.CityData city = readCityData();
        if (city != null)
        {
            writeCityData(level, city.withDay(city.day() + 1));
        }
        syncToClients(level);
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
}

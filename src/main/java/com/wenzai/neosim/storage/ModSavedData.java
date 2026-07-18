package com.wenzai.neosim.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wenzai.neosim.NeoSim;
import net.minecraft.server.level.ServerLevel;
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
            String saveName = level.getServer().getWorldData().getLevelName();
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
                data = SimData.fromJson(json);
                runGuiSent = json.get("runGuiSent").getAsBoolean();
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
                data.toJson(json);
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

    // 网络同步：仅同步给player.json中含有的玩家
    private void syncToClients(ServerLevel level)
    {
        SyncDataPayload payload = new SyncDataPayload(data);
        String cityName = getActiveCityName();
        if (cityName.isEmpty())
        {
            level.players().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
            return;
        }
        boolean isDedicated = level.getServer().isDedicatedServer();
        String saveName = isDedicated ? null : level.getServer().getWorldData().getLevelName();
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

    // 获取内部数据，用于构造网络包
    public SimData getData() { return data; }

    // 全量替换数据并同步一次
    public void setData(SimData newData, ServerLevel level)
    {
        this.data = newData;
        saveToFile();
        syncToClients(level);
    }

    // 单独Getter/Setter，每次调用会触发同步
    public byte getMode() { return data.mode(); }
    public void setMode(byte mode, ServerLevel level)
    {
        this.data = data.withMode(mode);
        saveToFile();
        syncToClients(level);
    }

    public short getPopulation() { return data.population(); }
    public void setPopulation(short population, ServerLevel level)
    {
        short clamped = population > 200 ? 200 : population;
        this.data = data.withPopulation(clamped);
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

    public int getDay() { return data.day(); }
    public void setDay(int day, ServerLevel level)
    {
        this.data = data.withDay(day);
        saveToFile();
        syncToClients(level);
    }

    public double getCredit() { return data.credit(); }
    public void setCredit(double credit, ServerLevel level)
    {
        double rounded = Math.round(credit * 100.0) / 100.0;
        this.data = data.withCredit(rounded);
        saveToFile();
        syncToClients(level);
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
        this.data = data.withDay(data.day() + 1)
                        .withDayOfWeek((data.dayOfWeek() + 1) % 7);
        saveToFile();
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
        return INSTANCE;
    }
}

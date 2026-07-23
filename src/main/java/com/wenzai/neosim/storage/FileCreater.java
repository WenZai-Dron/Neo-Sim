package com.wenzai.neosim.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = NeoSim.MOD_ID)
public class FileCreater
{
    private static final Logger LOGGER = LogUtils.getLogger();

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
            try
            {
                Files.createDirectories(cityDir);
                try (Writer writer = Files.newBufferedWriter(dataFile))
                {
                    JsonObject json = new JsonObject();
                    SimData.DEFAULT.toJson(json);
                    json.addProperty("runGuiSent", false);
                    json.add("joinedPlayers", new JsonArray());
                    GSON.toJson(json, writer);
                    LOGGER.info("NeoSim-createInitialDataJson: Succeed, {}", dataFile.toAbsolutePath());
                }
            }
            catch (IOException e)
            {
                LOGGER.error("NeoSim-createInitialDataJson: Fail, {}", e.getMessage(), e);
            }
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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean checkPlayerInFile(Path playerFile, String playerName)
    {
        if (!Files.exists(playerFile))
        {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(playerFile))
        {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            JsonArray arr = json.getAsJsonArray("players");
            for (JsonElement e : arr)
            {
                if (e.getAsString().equals(playerName))
                {
                    return true;
                }
            }
        }
        catch (IOException e)
        {
            LOGGER.error("NeoSim-checkPlayerInFile: Fail, {}", e.getMessage(), e);
        }
        return false;
    }

    private static void writePlayerJson(Path playerFile, String playerName)
    {
        List<String> players = new ArrayList<>();

        // 读取玩家列表
        if (Files.exists(playerFile))
        {
            try (Reader reader = Files.newBufferedReader(playerFile))
            {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                JsonArray arr = json.getAsJsonArray("players");
                for (JsonElement e : arr)
                {
                    players.add(e.getAsString());
                }
            }
            catch (IOException e)
            {
                LOGGER.error("NeoSim-writePlayerJson(read): Fail, {}", e.getMessage(), e);
            }
        }

        // 去重添加
        if (!players.contains(playerName))
        {
            players.add(playerName);
        }

        // 写入
        try
        {
            Files.createDirectories(playerFile.getParent());
            try (Writer writer = Files.newBufferedWriter(playerFile))
            {
                JsonObject json = new JsonObject();
                JsonArray arr = new JsonArray();
                for (String p : players)
                {
                    arr.add(p);
                }
                json.add("players", arr);
                GSON.toJson(json, writer);
                LOGGER.info("NeoSim-writePlayerJson: Succeed, {}", playerFile.toAbsolutePath());
            }
        }
        catch (IOException e)
        {
            LOGGER.error("NeoSim-writePlayerJson(write): Fail, {}", e.getMessage(), e);
        }
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

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event)
    {
        createNeoSimFolders();
    }
}

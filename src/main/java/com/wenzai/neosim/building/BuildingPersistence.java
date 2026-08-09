// 建筑实例JSON持久化：按城市存储所有使用中的模盒状态（BuildingConstructor.json）

package com.wenzai.neosim.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.schematic.SchematicRegistry;
import com.wenzai.neosim.util.SafeJson;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BuildingPersistence
{
    private static final Logger LOGGER = LogUtils.getLogger();

    // 新格式：player.json 同目录下的 BuildingConstructor.json（按城市）
    private static Path getCityPath(ServerLevel level, String cityName)
    {
        boolean dedicated = level.getServer().isDedicatedServer();
        if (dedicated)
        {
            return FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data")
                    .resolve(cityName).resolve("BuildingConstructor.json");
        }
        String saveName = level.getServer().getWorldData().getLevelName();
        return FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data")
                .resolve(saveName).resolve(cityName).resolve("BuildingConstructor.json");
    }

    // 按城市保存使用中的模盒状态
    public static void saveToCity(ServerLevel level, String cityName, List<BuildingInstance> buildings)
    {
        writeBuildings(getCityPath(level, cityName), buildings);
    }

    // 按城市加载使用中的模盒状态
    public static List<BuildingInstance> loadFromCity(ServerLevel level, String cityName)
    {
        return readBuildings(getCityPath(level, cityName));
    }

    private static void writeBuildings(Path file, List<BuildingInstance> buildings)
    {
        JsonArray arr = new JsonArray();
        for (BuildingInstance b : buildings)
        {
            arr.add(buildingToJson(b));
        }
        SafeJson.write(file, arr);
        LOGGER.info("NeoSim-BuildingPersistence: saved {} buildings to {}", buildings.size(), file);
    }

    private static List<BuildingInstance> readBuildings(Path file)
    {
        List<BuildingInstance> buildings = new ArrayList<>();
        if (!Files.exists(file)) return buildings;

        JsonArray arr = SafeJson.readArray(file);
        if (arr == null) return buildings;
        
        // 逐条隔离：单条记录损坏只跳过该条，不影响其余建筑恢复
        for (JsonElement e : arr)
        {
            try
            {
                if (!e.isJsonObject()) continue;
                BuildingInstance b = buildingFromJson(e.getAsJsonObject());
                if (b != null) buildings.add(b);
            }
            catch (Exception ex)
            {
                LOGGER.error("NeoSim-BuildingPersistence: skip bad building record, error={}", ex.getMessage(), ex);
            }
        }
        LOGGER.info("NeoSim-BuildingPersistence: loaded {} buildings from {}", buildings.size(), file);
        return buildings;
    }

    private static JsonObject buildingToJson(BuildingInstance b)
    {
        JsonObject obj = new JsonObject();
        obj.addProperty("schematicName", b.getSchematicName());
        obj.addProperty("cx", b.getControlBoxPos().getX());
        obj.addProperty("cy", b.getControlBoxPos().getY());
        obj.addProperty("cz", b.getControlBoxPos().getZ());
        obj.addProperty("rotation", b.getRotation().name());
        obj.addProperty("mirror", b.getMirror().name());
        if (b.getFacing() != null)
        {
            obj.addProperty("facing", b.getFacing().name());
        }
        obj.addProperty("buildProgress", b.getBuildProgress());
        obj.addProperty("buildingComplete", b.isBuildingComplete());
        obj.addProperty("phaseTwo", b.isPhaseTwo());
        obj.addProperty("paused", b.isPaused());
        obj.addProperty("state", b.getState().name());
        if (b.getAssignedBuilder() != null)
        {
            obj.addProperty("assignedBuilder", b.getAssignedBuilder().toString());
        }
        if (b.getBuilderName() != null)
        {
            obj.addProperty("builderName", b.getBuilderName());
        }
        obj.addProperty("author", b.getAuthor() != null ? b.getAuthor() : "Unknown");
        if (b.getPlacerName() != null)
        {
            obj.addProperty("placerName", b.getPlacerName());
        }
        if (b.getConstructorPos() != null)
        {
            obj.addProperty("conX", b.getConstructorPos().getX());
            obj.addProperty("conY", b.getConstructorPos().getY());
            obj.addProperty("conZ", b.getConstructorPos().getZ());
        }
        if (b.getWorkerName() != null)
        {
            obj.addProperty("workerName", b.getWorkerName());
        }
        return obj;
    }

    private static BuildingInstance buildingFromJson(JsonObject obj)
    {
        BuildingInstance b = new BuildingInstance();
        b.setSchematicName(getStr(obj, "schematicName", ""));
        b.setControlBoxPos(new BlockPos(
                getInt(obj, "cx", 0), getInt(obj, "cy", 0), getInt(obj, "cz", 0)));
        b.setRotation(Rotation.valueOf(getStr(obj, "rotation", "NONE")));
        b.setMirror(Mirror.valueOf(getStr(obj, "mirror", "NONE")));
        
        String facing = getStr(obj, "facing", null);
        if (facing != null)
        {
            try
            {
                b.setFacing(net.minecraft.core.Direction.valueOf(facing));
            }
            catch (IllegalArgumentException ignored) {}
        }
        b.setBuildProgress(getInt(obj, "buildProgress", 0));
        b.setBuildingComplete(getBool(obj, "buildingComplete", false));
        b.setPhaseTwo(getBool(obj, "phaseTwo", false));
        b.setPaused(getBool(obj, "paused", false));
        b.setState(BuildingInstance.BuildState.valueOf(
                getStr(obj, "state", "IDLE")));
        if (obj.has("assignedBuilder"))
        {
            b.setAssignedBuilder(UUID.fromString(obj.get("assignedBuilder").getAsString()));
        }
        b.setBuilderName(getStr(obj, "builderName", null));
        b.setAuthor(getStr(obj, "author", "Unknown"));
        b.setPlacerName(getStr(obj, "placerName", null));
        if (obj.has("conX"))
        {
            b.setConstructorPos(new BlockPos(
                    getInt(obj, "conX", 0), getInt(obj, "conY", 0), getInt(obj, "conZ", 0)));
        }
        b.setWorkerName(getStr(obj, "workerName", null));

        // 恢复蓝图引用
        if (!b.getSchematicName().isEmpty())
        {
            b.setSchematic(SchematicRegistry.getInstance().get(b.getSchematicName()));
        }
        return b;
    }

    private static String getStr(JsonObject o, String k, String def)
    {
        return SafeJson.getString(o, k, def);
    }

    private static int getInt(JsonObject o, String k, int def)
    {
        return SafeJson.getInt(o, k, def);
    }

    private static boolean getBool(JsonObject o, String k, boolean def)
    {
        return SafeJson.getBoolean(o, k, def);
    }
}

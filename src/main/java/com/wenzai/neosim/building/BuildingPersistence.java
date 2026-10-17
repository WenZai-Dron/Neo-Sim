package com.wenzai.neosim.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.schematic.SchematicRegistry;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public class BuildingPersistence
{
	// 工具类：只有静态成员，禁止实例化
	private BuildingPersistence()
	{
	}

	private static final Logger LOGGER = LogUtils.getLogger();

	private static final String FILE_NAME = "BuildingConstructor.json";

	// 新格式：player.json 同目录下的 BuildingConstructor.json（按城市）
	private static Path getCityPath(ServerLevel level, String cityName)
	{
		boolean dedicated = level.getServer().isDedicatedServer();
		if (dedicated)
		{
			return FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data")
					.resolve(cityName).resolve(FILE_NAME);
		}
		String saveName = level.getServer().getWorldData().getLevelName();
		return FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data")
				.resolve(saveName).resolve(cityName).resolve(FILE_NAME);
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

	// 删除某栋建筑的记录并立即落盘（列表清空也照样写回：留记录等于给读档留一个必然被恢复的任务）
	// 完工/取消后必须调它，否则引擎读档时会把已解雇的工人重新雇回来接着盖
	public static boolean removeAt(ServerLevel level, BuildingInstance building)
	{
		if (building == null) return false;

		String city = building.getCachedCity(level);
		if (city != null && !city.isEmpty())
		{
			return removeFromFile(getCityPath(level, city), building);
		}

		// 放置者已不在任何城市（退城/换档）：退回扫描全部城市目录
		Path dataDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		if (!level.getServer().isDedicatedServer())
		{
			dataDir = dataDir.resolve(level.getServer().getWorldData().getLevelName());
		}
		if (!Files.isDirectory(dataDir)) return false;

		try (Stream<Path> dirs = Files.list(dataDir))
		{
			for (Path dir : dirs.filter(Files::isDirectory).toList())
			{
				Path file = dir.resolve(FILE_NAME);
				if (!Files.exists(file)) continue;
				if (removeFromFile(file, building)) return true;
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-BuildingPersistence: removeAt failed", e);
		}
		return false;
	}

	private static void writeBuildings(Path file, List<BuildingInstance> buildings)
	{
		JsonArray arr = new JsonArray();
		for (BuildingInstance b : buildings)
		{
			arr.add(buildingToJson(b));
		}
		JsonUtil.write(file, arr);
		LOGGER.debug("NeoSim-BuildingPersistence: saved {} buildings to {}", buildings.size(), file);
	}

	private static List<BuildingInstance> readBuildings(Path file)
	{
		List<BuildingInstance> buildings = new ArrayList<>();
		if (!Files.exists(file)) return buildings;

		JsonArray arr = JsonUtil.readArray(file);
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

	// 从单个城市文件里删掉这条记录；没命中返回 false（不做无意义的整文件重写）
	private static boolean removeFromFile(Path file, BuildingInstance building)
	{
		List<BuildingInstance> buildings = readBuildings(file);
		if (!buildings.removeIf(b -> sameRecord(b, building))) return false;

		writeBuildings(file, buildings);

		// 坐标优先模盒；记录缺模盒坐标时用控制箱
		BlockPos at = building.getConstructorPos() != null
				? building.getConstructorPos() : building.getControlBoxPos();
		LOGGER.info("NeoSim-BuildingPersistence: removed building record {} ({} left in {})",
				at, buildings.size(), file);
		return true;
	}

	// 同一条记录：模盒坐标优先，记录缺模盒坐标时退回控制箱坐标
	private static boolean sameRecord(BuildingInstance a, BuildingInstance b)
	{
		BlockPos aCon = a.getConstructorPos();
		BlockPos bCon = b.getConstructorPos();
		if (aCon != null && bCon != null) return aCon.equals(bCon);

		BlockPos aBox = a.getControlBoxPos();
		BlockPos bBox = b.getControlBoxPos();
		return aBox != null && bBox != null && aBox.equals(bBox);
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

		// 防删改：坐标钳制
		b.setControlBoxPos(new BlockPos(
				JsonUtil.clampX(getInt(obj, "cx", 0)),
				JsonUtil.clampY(getInt(obj, "cy", 0)),
				JsonUtil.clampX(getInt(obj, "cz", 0))));
		b.setRotation(Rotation.valueOf(getStr(obj, "rotation", "NONE")));
		b.setMirror(Mirror.valueOf(getStr(obj, "mirror", "NONE")));

		String facing = getStr(obj, "facing", null);
		if (facing != null)
		{
			try
			{
				b.setFacing(net.minecraft.core.Direction.valueOf(facing));
			}
			catch (IllegalArgumentException ignored)
			{
			}
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
					JsonUtil.clampX(getInt(obj, "conX", 0)),
					JsonUtil.clampY(getInt(obj, "conY", 0)),
					JsonUtil.clampX(getInt(obj, "conZ", 0))));
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
		return JsonUtil.getString(o, k, def);
	}

	private static int getInt(JsonObject o, String k, int def)
	{
		return JsonUtil.getInt(o, k, def);
	}

	private static boolean getBool(JsonObject o, String k, boolean def)
	{
		return JsonUtil.getBoolean(o, k, def);
	}
}

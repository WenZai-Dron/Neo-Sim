package com.wenzai.neosim.block;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.util.SafeCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

public class MarkerPersistence
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private MarkerPersistence() {}

	// 防删改：标记Y钳制范围（正常标记都在世界高度内，放宽到±4096只是防离谱数值）
	private static final int MARKER_MIN_Y = -4096;
	private static final int MARKER_MAX_Y = 4096;

	// 当前存档名（适配服务器）
	@Nullable
	public static String saveNameOf(ServerLevel level)
	{
		return level.getServer().isDedicatedServer() ? null
				: level.getServer().getWorldData().getLevelName();
	}

	private static Path getPath(@Nullable String saveName)
	{
		Path base = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		return (saveName == null || saveName.isEmpty())
				? base.resolve("Marker.json")
				: base.resolve(saveName).resolve("Marker.json");
	}

	// 读取某维度的标记列表（坏条目跳过、坐标越界条目剔除，不让一条脏数据清空全部标记）
	public static List<BlockPos> load(@Nullable String saveName, String dimension)
	{
		Path file = getPath(saveName);
		if (!Files.exists(file)) return List.of();
		try (Reader r = Files.newBufferedReader(file))
		{
			JsonObject root = GSON.fromJson(r, JsonObject.class);
			if (root == null || !root.has(dimension)) return List.of();
			JsonElement dimEl = root.get(dimension);
			if (dimEl == null || !dimEl.isJsonArray()) return List.of();
			List<BlockPos> out = new ArrayList<>();
			for (JsonElement e : dimEl.getAsJsonArray())
			{
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				if (!o.has("x") || !o.has("y") || !o.has("z")) continue;
				if (!o.get("x").isJsonPrimitive() || !o.get("y").isJsonPrimitive()
						|| !o.get("z").isJsonPrimitive()) continue;
				int x, y, z;
				try
				{
					x = o.get("x").getAsInt();
					y = o.get("y").getAsInt();
					z = o.get("z").getAsInt();
				}
				catch (NumberFormatException ex)
				{
					continue;
				}
				if (Math.abs(x) > SafeCoord.WORLD_BORDER || Math.abs(z) > SafeCoord.WORLD_BORDER) continue;
				if (y < MARKER_MIN_Y || y > MARKER_MAX_Y) continue;
				out.add(new BlockPos(x, y, z));
			}
			return out;
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-MarkerPersistence: load failed", e);
			return List.of();
		}
	}

	// 保存某维度的标记列表（保留其他维度）
	public static void save(@Nullable String saveName, String dimension, List<BlockPos> markers)
	{
		Path file = getPath(saveName);
		JsonObject root = new JsonObject();
		if (Files.exists(file))
		{
			try (Reader r = Files.newBufferedReader(file))
			{
				JsonObject existing = GSON.fromJson(r, JsonObject.class);
				if (existing != null) root = existing;
			}
			catch (Exception ignored) {}
		}
		JsonArray arr = new JsonArray();
		for (BlockPos p : markers)
		{
			JsonObject o = new JsonObject();
			o.addProperty("x", p.getX());
			o.addProperty("y", p.getY());
			o.addProperty("z", p.getZ());
			arr.add(o);
		}
		root.add(dimension, arr);
		try
		{
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file))
			{
				GSON.toJson(root, w);
			}
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-MarkerPersistence: save failed", e);
		}
	}
}

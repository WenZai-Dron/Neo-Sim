package com.wenzai.neosim.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

import javax.annotation.Nullable;

// NeoSim/Json/ 内容表读写：
// 内置规则打包在 jar 内（assets/neo_sim/json/<分类>/<同名文件>），外部文件在 {游戏根目录}/NeoSim/Json/<分类>/，
// 外部优先；解析失败单文件跳过并写 _state/errors.txt，不拖垮其他分类。
public final class JsonContent
{
	private static final Logger LOGGER = LogUtils.getLogger();

	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	// 目录变更轮询：记录上次看到的最新修改时间（_state 目录自身不参与，避免自触发）
	private static long lastSeenMillis = -1L;

	private JsonContent()
	{
	}

	// 内容根目录：{游戏根目录}/NeoSim/Json
	public static Path root()
	{
		return FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("Json");
	}

	// 机器读写区
	public static Path stateDir()
	{
		return root().resolve("_state");
	}

	public static Path externalPath(String category, String fileName)
	{
		return root().resolve(category).resolve(fileName);
	}

	private static Path backupPath(String category, String fileName)
	{
		return root().resolve(category).resolve(fileName + ".bak");
	}

	// jar 内置资源路径
	public static String builtinResource(String category, String fileName)
	{
		return "/assets/neo_sim/json/" + category + "/" + fileName;
	}

	// 读取内置资源；缺失或损坏返回 null
	@Nullable
	public static JsonObject readBuiltin(String category, String fileName)
	{
		String path = builtinResource(category, fileName);
		try (InputStream is = JsonContent.class.getResourceAsStream(path))
		{
			if (is == null)
			{
				LOGGER.warn("NeoSim-JsonContent: builtin {} not found", path);
				return null;
			}
			try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8))
			{
				return JsonParser.parseReader(reader).getAsJsonObject();
			}
		}
		catch (Exception e)
		{
			recordError(path, e);
			return null;
		}
	}

	// 读取外部文件；不存在返回 null（不算错误）
	@Nullable
	public static JsonObject readExternal(String category, String fileName)
	{
		Path path = externalPath(category, fileName);
		if (!Files.exists(path)) return null;
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			return JsonParser.parseReader(reader).getAsJsonObject();
		}
		catch (Exception e)
		{
			recordError(path.toString(), e);
			return null;
		}
	}

	// 首次运行：把内置内容原样落到外部文件，玩家打开即可编辑（已存在则不动）
	public static void ensureExternal(String category, String fileName)
	{
		Path path = externalPath(category, fileName);
		if (Files.exists(path)) return;

		JsonObject builtin = readBuiltin(category, fileName);
		if (builtin == null) return;

		try
		{
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8))
			{
				GSON.toJson(builtin, writer);
			}
			LOGGER.info("NeoSim-JsonContent: created template {}", path);
		}
		catch (Exception e)
		{
			recordError(path.toString(), e);
		}
	}

	// 保存外部文件：先备份 .bak 再覆盖写
	public static boolean save(String category, String fileName, JsonObject content)
	{
		Path path = externalPath(category, fileName);
		try
		{
			Files.createDirectories(path.getParent());
			if (Files.exists(path))
			{
				Files.copy(path, backupPath(category, fileName), StandardCopyOption.REPLACE_EXISTING);
			}
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8))
			{
				GSON.toJson(content, writer);
			}
			return true;
		}
		catch (Exception e)
		{
			recordError(path.toString(), e);
			return false;
		}
	}

	// 清空错误文件（每次整体重载前调用，保证 errors.txt 只反映最近一次加载）
	public static void clearErrors()
	{
		try
		{
			Files.deleteIfExists(stateDir().resolve("errors.txt"));
		}
		catch (Exception ignored)
		{
		}
	}

	// 最近一次解析错误（GUI 状态行用）
	public static String readErrors()
	{
		Path path = stateDir().resolve("errors.txt");
		try
		{
			return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8).trim() : "";
		}
		catch (Exception e)
		{
			return "";
		}
	}

	// 加载摘要（各内容表各写一份：loaded-compat-attached_blocks.json、loaded-crops.json）
	public static void writeState(JsonObject state, String name)
	{
		Path path = stateDir().resolve("loaded-" + name + ".json");
		try
		{
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8))
			{
				GSON.toJson(state, writer);
			}
		}
		catch (Exception e)
		{
			LOGGER.warn("NeoSim-JsonContent: failed to write loaded.json — {}", e.getMessage());
		}
	}

	private static void recordError(String source, Exception e)
	{
		LOGGER.error("NeoSim-JsonContent: {} — {}", source, e.getMessage());
		Path path = stateDir().resolve("errors.txt");
		try
		{
			Files.createDirectories(path.getParent());
			String line = "[" + LocalDateTime.now().format(STAMP) + "] " + source + " — " + e.getMessage() + System.lineSeparator();
			Files.writeString(path, line, StandardCharsets.UTF_8,
					Files.exists(path) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
		}
		catch (Exception ignored)
		{
		}
	}

	// 目录内容是否变化（新增/修改任意文件即视为变化；_state 不参与）
	public static synchronized boolean pollChanged()
	{
		long newest = newestMillis(root());
		if (newest != lastSeenMillis)
		{
			lastSeenMillis = newest;
			return lastSeenMillis > 0L;
		}
		return false;
	}

	private static long newestMillis(Path dir)
	{
		if (!Files.isDirectory(dir)) return 0L;
		long newest = 0L;
		try (Stream<Path> stream = Files.walk(dir, 3))
		{
			for (Path p : stream.toList())
			{
				if (Files.isDirectory(p)) continue;
				if (p.startsWith(stateDir())) continue;
				newest = Math.max(newest, Files.getLastModifiedTime(p).toMillis());
			}
		}
		catch (Exception ignored)
		{
		}
		return newest;
	}
}

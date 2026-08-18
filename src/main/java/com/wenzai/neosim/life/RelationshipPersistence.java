// 关系文件读写（C3：内存缓存 + 脏标记合并落盘）

package com.wenzai.neosim.life;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class RelationshipPersistence
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// C3：关系内存缓存（键=relationships 目录路径，天然区分存档/城市）+ 脏键集合（合并窗口落盘）
	private static final Map<Path, Map<String, Relationship.RelationshipData>> CACHE = new HashMap<>();
	private static final Map<Path, Set<String>> DIRTY_KEYS = new HashMap<>();

	private RelationshipPersistence() {}

	private static Path relationshipsDir(ServerLevel level, String cityName)
	{
		if (level.getServer() == null || cityName == null || cityName.isEmpty()) return null;
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path cityDir = level.getServer().isDedicatedServer()
				? gameDir.resolve("NeoSim").resolve("data").resolve(cityName)
				: gameDir.resolve("NeoSim").resolve("data")
						.resolve(level.getServer().getWorldData().getLevelName()).resolve(cityName);
		return cityDir.resolve("relationships");
	}

	// 该城市的关系缓存（首次访问整目录载入）
	private static Map<String, Relationship.RelationshipData> cityCache(ServerLevel level, String city)
	{
		Path dir = relationshipsDir(level, city);
		if (dir == null) return Collections.emptyMap();
		return CACHE.computeIfAbsent(dir, k -> loadAllFromDisk(level, city));
	}

	private static void markDirty(Path dir, String key)
	{
		if (dir == null || key == null) return;
		DIRTY_KEYS.computeIfAbsent(dir, k -> new HashSet<>()).add(key);
	}

	// 读取一对居民的关系
	public static Relationship.RelationshipData loadPair(ServerLevel level, String city, String a, String b)
	{
		if (a == null || b == null || a.isEmpty() || b.isEmpty()) return null;
		return cityCache(level, city).get(pairKey(a, b));
	}

	// 读取某城市全部关系（婚姻扫描；命中缓存不再逐个文件读）
	public static List<Relationship.RelationshipData> loadAll(ServerLevel level, String city)
	{
		return new ArrayList<>(cityCache(level, city).values());
	}

	// 婚姻改姓：更新缓存并即时删除旧文件（新文件由 flush 写入）
	public static void renameAllFor(ServerLevel level, String city, String oldName, String newName)
	{
		Path dir = relationshipsDir(level, city);
		if (dir == null || oldName == null || oldName.isEmpty()) return;
		Map<String, Relationship.RelationshipData> cache = cityCache(level, city);
		if (cache.isEmpty()) return;

		List<Map.Entry<String, Relationship.RelationshipData>> renamed = new ArrayList<>();
		for (Map.Entry<String, Relationship.RelationshipData> e : new ArrayList<>(cache.entrySet()))
		{
			Relationship.RelationshipData rel = e.getValue();
			String f1 = rel.folk1(), f2 = rel.folk2();
			if (!f1.equals(oldName) && !f2.equals(oldName)) continue;
			if (f1.equals(oldName)) f1 = newName;
			if (f2.equals(oldName)) f2 = newName;
			cache.remove(e.getKey());
			deleteFile(dir, e.getKey());
			renamed.add(Map.entry(pairKey(f1, f2),
					new Relationship.RelationshipData(f1, f2, rel.level(), rel.subLevel())));
		}
		for (Map.Entry<String, Relationship.RelationshipData> e : renamed)
		{
			cache.put(e.getKey(), e.getValue());
			markDirty(dir, e.getKey());
		}
	}

	// 解析一对关系
	private static Relationship.RelationshipData parsePair(JsonObject json, String fallback1, String fallback2)
	{
		String f1 = JsonUtil.getString(json, "folk1", fallback1);
		String f2 = JsonUtil.getString(json, "folk2", fallback2);
		if (f1.isEmpty() || f2.isEmpty()) return null;
		int sub = JsonUtil.getInt(json, "subLevel", 0);
		return new Relationship.RelationshipData(f1, f2, parseLevel(json), sub);
	}

	// 写入一对关系（改动即存 → 内存缓存 + 脏标记，周期 flush 落盘）
	public static void save(ServerLevel level, String city, Relationship.RelationshipData rel)
	{
		Path dir = relationshipsDir(level, city);
		if (dir == null || rel == null) return;
		Map<String, Relationship.RelationshipData> cache = cityCache(level, city);
		String key = pairKey(rel.folk1(), rel.folk2());
		cache.put(key, rel);
		markDirty(dir, key);
	}

	public static void removePair(ServerLevel level, String city, String a, String b)
	{
		Path dir = relationshipsDir(level, city);
		if (dir == null) return;
		String key = pairKey(a, b);
		Map<String, Relationship.RelationshipData> cache = cityCache(level, city);
		cache.remove(key);
		deleteFile(dir, key);   // 删除即时落盘（关系清理正确性）
	}

	// 删除含该居民名的全部关系文件（死亡清理：即时删除 + 缓存移除）
	public static void removeAllFor(ServerLevel level, String city, String name)
	{
		Path dir = relationshipsDir(level, city);
		if (dir == null || name == null || name.isEmpty()) return;
		Map<String, Relationship.RelationshipData> cache = cityCache(level, city);
		for (String key : new ArrayList<>(cache.keySet()))
		{
			if (key.startsWith(name + "_") || key.endsWith("_" + name))
			{
				cache.remove(key);
				deleteFile(dir, key);
			}
		}
	}

	// L9：每日清理低等级过期关系文件（关系对 O(P²) 无上限 → 只有最近仍在互动的低等级关系保留文件；
	// 高等级（GOODFRIEND+，含婚姻门槛 BESTFRIENDS）永不清理）
	private static final long STALE_MS = 14L * 24L * 60L * 60L * 1000L;

	public static void cleanupStale(ServerLevel level, String city)
	{
		Path dir = relationshipsDir(level, city);
		if (dir == null || !Files.isDirectory(dir)) return;
		Map<String, Relationship.RelationshipData> cache = cityCache(level, city);

		long now = System.currentTimeMillis();
		// 遍历缓存键（文件名 = 键），低等级且 mtime 过旧则删除
		for (String key : new ArrayList<>(cache.keySet()))
		{
			Relationship.RelationshipData rel = cache.get(key);
			if (rel == null) continue;
			if (rel.level() == Relationship.RelationshipLevel.BESTFRIENDS
					|| rel.level() == Relationship.RelationshipLevel.GOODFRIEND
					|| rel.level() == Relationship.RelationshipLevel.FRIEND) continue;
			Path file = dir.resolve(key + ".json");
			try
			{
				if (Files.exists(file) && now - Files.getLastModifiedTime(file).toMillis() > STALE_MS)
				{
					cache.remove(key);
					deleteFile(dir, key);
					LOGGER.debug("NeoSim-RelationshipPersistence: cleanup stale {} in {}", key, city);
				}
			}
			catch (IOException ignored) {}
		}
	}

	// 周期 flush：脏键写盘（每 100 tick 由 NeoSim 调用 + ServerStopping）
	public static void flushDirty()
	{
		for (Map.Entry<Path, Set<String>> e : DIRTY_KEYS.entrySet())
		{
			Path dir = e.getKey();
			Map<String, Relationship.RelationshipData> cache = CACHE.get(dir);
			for (String key : e.getValue())
			{
				Relationship.RelationshipData rel = cache != null ? cache.get(key) : null;
				if (rel != null)
				{
					writeFile(dir, rel);
				}
			}
		}
		DIRTY_KEYS.clear();
	}

	// 服务端停止：flush 后清缓存，防止跨存档残留
	public static void flushAndClear()
	{
		flushDirty();
		CACHE.clear();
		DIRTY_KEYS.clear();
	}

	private static void writeFile(Path dir, Relationship.RelationshipData rel)
	{
		JsonObject json = new JsonObject();
		json.addProperty("folk1", rel.folk1());
		json.addProperty("folk2", rel.folk2());
		json.addProperty("level", rel.level().name());
		json.addProperty("subLevel", rel.subLevel());
		JsonUtil.write(dir.resolve(pairKey(rel.folk1(), rel.folk2()) + ".json"), json);
	}

	private static void deleteFile(Path dir, String key)
	{
		try
		{
			Files.deleteIfExists(dir.resolve(key + ".json"));
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-RelationshipPersistence: delete failed, error={}", e.getMessage(), e);
		}
	}

	// 文件名键：字典序规范化
	private static String pairKey(String a, String b)
	{
		return a.compareTo(b) <= 0 ? a + "_" + b : b + "_" + a;
	}

	// 整目录载入（首次访问）
	private static Map<String, Relationship.RelationshipData> loadAllFromDisk(ServerLevel level, String city)
	{
		Map<String, Relationship.RelationshipData> map = new HashMap<>();
		Path dir = relationshipsDir(level, city);
		if (dir == null || !Files.isDirectory(dir)) return map;

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
		{
			for (Path file : stream)
			{
				if (!file.getFileName().toString().endsWith(".json")) continue;
				JsonObject json = JsonUtil.readObject(file);
				if (json == null)
				{
					JsonUtil.backupCorrupted(file);
					LOGGER.warn("NeoSim-RelationshipPersistence: corrupted file skipped, {}", file);
					continue;
				}
				try
				{
					Relationship.RelationshipData rel = parsePair(json, "", "");
					if (rel != null) map.put(pairKey(rel.folk1(), rel.folk2()), rel);
				}
				catch (Exception ex)
				{
					LOGGER.error("NeoSim-RelationshipPersistence: loadAll failed, file={}, error={}",
							file, ex.getMessage(), ex);
				}
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-RelationshipPersistence: loadAll failed, error={}", e.getMessage(), e);
		}
		return map;
	}

	private static Relationship.RelationshipLevel parseLevel(JsonObject json)
	{
		if (!json.has("level")) return Relationship.RelationshipLevel.AQUAINTANCE;
		try
		{
			return Relationship.RelationshipLevel.valueOf(json.get("level").getAsString());
		}
		catch (IllegalArgumentException e)
		{
			return Relationship.RelationshipLevel.AQUAINTANCE;
		}
	}
}

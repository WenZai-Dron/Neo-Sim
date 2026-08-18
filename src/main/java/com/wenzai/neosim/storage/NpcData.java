package com.wenzai.neosim.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class NpcData
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// NPC 写盘去抖队列（弱引用：实体被卸载/GC 后自动消失，防泄漏）。
	// 卸载路径在 discard 前会强制写盘，因此这里只兜底"仍加载"实体的合并窗口落盘。
	private static final Set<Entity> DIRTY_NPCS = Collections.newSetFromMap(new WeakHashMap<Entity, Boolean>());

	// 标记 NPC 脏（高频写盘路径调用，周期 flush 合并落盘）
	public static void markDirty(Entity entity)
	{
		if (entity != null) DIRTY_NPCS.add(entity);
	}

	// 周期 flush：把仍存活的脏 NPC 全部写盘（每 100 tick 由 NeoSim 调用）
	public static void flushDirty()
	{
		for (Entity e : DIRTY_NPCS)
		{
			if (e.isAlive() && !e.isRemoved())
			{
				e.syncToJsonNow();
			}
		}
		DIRTY_NPCS.clear();
	}

	// ---- 轻量读取（流式解析，只取所需字段，避免完整 Gson 解析 + 日志）----

	// NPC 档案文件路径（按服务端环境解析）
	private static Path npcFile(ServerLevel level, String cityName, String npcName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		if (level.getServer() != null && level.getServer().isDedicatedServer())
		{
			return gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc")
					.resolve(npcName + ".json");
		}
		return gameDir.resolve("NeoSim").resolve("data")
				.resolve(level.getServer().getWorldData().getLevelName())
				.resolve(cityName).resolve("npc").resolve(npcName + ".json");
	}

	// 轻量读取 position：档案缺失/损坏返回 null
	@javax.annotation.Nullable
	public static BlockPos readPosition(ServerLevel level, String cityName, String npcName)
	{
		Path file = npcFile(level, cityName, npcName);
		if (!Files.exists(file)) return null;
		try (java.io.Reader r = Files.newBufferedReader(file))
		{
			com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(r);
			reader.beginObject();
			while (reader.hasNext())
			{
				String key = reader.nextName();
				if (key.equals("position") && reader.peek() == com.google.gson.stream.JsonToken.BEGIN_OBJECT)
				{
					double x = 0, y = 0, z = 0;
					reader.beginObject();
					while (reader.hasNext())
					{
						String pk = reader.nextName();
						double v = reader.nextDouble();
						if (pk.equals("x")) x = v;
						else if (pk.equals("y")) y = v;
						else if (pk.equals("z")) z = v;
					}
					reader.endObject();
					return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
				}
				reader.skipValue();
			}
			reader.endObject();
			return null;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	// 轻量读取 home 是否存在：-1=档案缺失/损坏，0=存在但无 home，1=存在且有 home
	public static int homeStatus(ServerLevel level, String cityName, String npcName)
	{
		Path file = npcFile(level, cityName, npcName);
		if (!Files.exists(file)) return -1;
		try (java.io.Reader r = Files.newBufferedReader(file))
		{
			com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(r);
			reader.beginObject();
			while (reader.hasNext())
			{
				String key = reader.nextName();
				if (key.equals("home"))
				{
					return reader.peek() == com.google.gson.stream.JsonToken.NULL ? 0 : 1;
				}
				reader.skipValue();
			}
			reader.endObject();
			return 0;
		}
		catch (Exception e)
		{
			return -1;
		}
	}

	private NpcData() {}

	// 客户端路径
	public static void save(Entity entity, String cityName, String saveName)
	{
		String npcName = entity.getNpcName();
		if (npcName.isEmpty())
		{
			LOGGER.warn("NeoSim-NpcData: save: NPC name is empty. UUID={}", entity.getUUID());
			return;
		}

		Path gameDir = FMLPaths.GAMEDIR.get();
		Path npcDir = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName).resolve("npc");

		writeNpcJson(npcDir, npcName, entity);
	}

	public static JsonObject load(String npcName, String cityName, String saveName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path npcFile = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName).resolve("npc").resolve(npcName + ".json");

		return readNpcJson(npcFile);
	}

	// 服务端路径
	public static void save(Entity entity, String cityName)
	{
		String npcName = entity.getNpcName();
		if (npcName.isEmpty())
		{
			LOGGER.warn("NeoSim-NpcData: save: NPC name is empty. UUID={}", entity.getUUID());
			return;
		}

		Path gameDir = FMLPaths.GAMEDIR.get();
		Path npcDir = gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc");

		writeNpcJson(npcDir, npcName, entity);
	}

	public static JsonObject load(String npcName, String cityName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path npcFile = gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc").resolve(npcName + ".json");

		return readNpcJson(npcFile);
	}

	// 按服务端环境解析加载单个NPC的文件
	public static JsonObject load(ServerLevel level, String cityName, String npcName)
	{
		if (npcName.isEmpty() || cityName.isEmpty() || level.getServer() == null) return null;
		if (level.getServer().isDedicatedServer())
		{
			return load(npcName, cityName);
		}
		else
		{
			String saveName = level.getServer().getWorldData().getLevelName();
			return load(npcName, cityName, saveName);
		}
	}

	// 直接改写某NPC文件的族谱字段：死亡摘除时处理未加载亲戚
	public static void patchGenealogy(ServerLevel level, String cityName, String npcName,
									  List<String> parents, List<String> children)
	{
		if (npcName.isEmpty() || cityName.isEmpty() || level.getServer() == null) return;

		Path gameDir = FMLPaths.GAMEDIR.get();
		Path npcDir = level.getServer().isDedicatedServer()
				? gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc")
				: gameDir.resolve("NeoSim").resolve("data")
						.resolve(level.getServer().getWorldData().getLevelName())
						.resolve(cityName).resolve("npc");
		Path npcFile = npcDir.resolve(npcName + ".json");
		if (!Files.exists(npcFile)) return;

		JsonObject json = JsonUtil.readObject(npcFile);
		if (json == null)
		{
			// 内容被篡改：备份后跳过（该NPC数据视为无效）
			JsonUtil.backupCorrupted(npcFile);
			LOGGER.warn("NeoSim-NpcData: patchGenealogy: corrupted file skipped, {}", npcFile.toAbsolutePath());
			return;
		}

		JsonArray parentsArr = new JsonArray();
		if (parents != null)
		{
			for (String p : parents)
			{
				if (p != null && !p.isEmpty()) parentsArr.add(p);
			}
		}
		if (parentsArr.size() > 0) json.add("parents", parentsArr);
		else json.remove("parents");

		JsonArray childrenArr = new JsonArray();
		if (children != null)
		{
			for (String c : children)
			{
				if (c != null && !c.isEmpty()) childrenArr.add(c);
			}
		}
		if (childrenArr.size() > 0) json.add("children", childrenArr);
		else json.remove("children");

		JsonUtil.write(npcFile, json);
		LOGGER.info("NeoSim-NpcData: patchGenealogy: Succeed, {}", npcFile.toAbsolutePath());
	}

	// 直接改写某NPC文件的伴侣字段（伴侣死亡时清理未加载的一方）
	public static void patchPartner(ServerLevel level, String cityName, String npcName, String partner)
	{
		if (npcName.isEmpty() || cityName.isEmpty() || level.getServer() == null) return;

		Path gameDir = FMLPaths.GAMEDIR.get();
		Path npcDir = level.getServer().isDedicatedServer()
				? gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc")
				: gameDir.resolve("NeoSim").resolve("data")
						.resolve(level.getServer().getWorldData().getLevelName())
						.resolve(cityName).resolve("npc");
		Path npcFile = npcDir.resolve(npcName + ".json");
		if (!Files.exists(npcFile)) return;

		JsonObject json = JsonUtil.readObject(npcFile);
		if (json == null)
		{
			// 内容被篡改：备份后跳过（该NPC数据视为无效）
			JsonUtil.backupCorrupted(npcFile);
			LOGGER.warn("NeoSim-NpcData: patchPartner: corrupted file skipped, {}", npcFile.toAbsolutePath());
			return;
		}

		if (partner == null || partner.isEmpty()) json.remove("partner");
		else json.addProperty("partner", partner);

		JsonUtil.write(npcFile, json);
		LOGGER.info("NeoSim-NpcData: patchPartner: Succeed, {}", npcFile.toAbsolutePath());
	}

	// 修改某未加载NPC的年龄
	public static void patchAge(ServerLevel level, String cityName, String npcName, short age)
	{
		patchJson(level, cityName, npcName, json -> json.addProperty("age", age));
	}

	// 修改某未加载NPC的孕期进度
	public static void patchPregnancy(ServerLevel level, String cityName, String npcName, float stage)
	{
		patchJson(level, cityName, npcName, json ->
		{
			if (stage > 0.0F) json.addProperty("pregnancy", stage);
			else json.remove("pregnancy");
		});
	}

	// 清除某未加载NPC的生活点登记
	public static void patchClearHome(ServerLevel level, String cityName, String npcName)
	{
		patchJson(level, cityName, npcName, json ->
		{
			json.remove("home");
			json.remove("homeBuilding");
		});
	}

	// 读-改-写单个NPC文件（仅服务端数据侧操作）
	private static void patchJson(ServerLevel level, String cityName, String npcName,
								  java.util.function.Consumer<JsonObject> editor)
	{
		if (npcName == null || npcName.isEmpty() || cityName == null || cityName.isEmpty()
				|| level.getServer() == null) return;

		Path gameDir = FMLPaths.GAMEDIR.get();
		Path npcDir = level.getServer().isDedicatedServer()
				? gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc")
				: gameDir.resolve("NeoSim").resolve("data")
						.resolve(level.getServer().getWorldData().getLevelName())
						.resolve(cityName).resolve("npc");
		Path npcFile = npcDir.resolve(npcName + ".json");
		if (!Files.exists(npcFile)) return;

		JsonObject json = JsonUtil.readObject(npcFile);
		if (json == null)
		{
			// 内容被篡改：备份后跳过（该NPC数据视为无效）
			JsonUtil.backupCorrupted(npcFile);
			LOGGER.warn("NeoSim-NpcData: patchJson: corrupted file skipped, {}", npcFile.toAbsolutePath());
			return;
		}

		editor.accept(json);

		JsonUtil.write(npcFile, json);
		LOGGER.info("NeoSim-NpcData: patchJson: Succeed, {}", npcFile.toAbsolutePath());
	}

	// 列出某城市所有已保存的NPC
	public static List<String> listNpcNames(String cityName, String saveName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path npcDir = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName).resolve("npc");
		return listNpcFiles(npcDir);
	}

	public static List<String> listNpcNames(String cityName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path npcDir = gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc");
		return listNpcFiles(npcDir);
	}

	// 按服务端环境解析列出
	public static List<String> listNpcNames(ServerLevel level, String cityName)
	{
		if (level.getServer() == null || cityName == null || cityName.isEmpty()) return List.of();
		if (level.getServer().isDedicatedServer())
		{
			return listNpcNames(cityName);
		}
		return listNpcNames(cityName, level.getServer().getWorldData().getLevelName());
	}

	// 死亡时删除文件
	public static void delete(String npcName, String cityName, String saveName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path npcFile = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName).resolve("npc").resolve(npcName + ".json");
		deleteNpcJson(npcFile);
	}

	public static void delete(String npcName, String cityName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path npcFile = gameDir.resolve("NeoSim").resolve("data").resolve(cityName).resolve("npc").resolve(npcName + ".json");
		deleteNpcJson(npcFile);
	}

	// 按服务端环境解析删除
	public static void delete(ServerLevel level, String cityName, String npcName)
	{
		if (level.getServer() == null || cityName == null || cityName.isEmpty()
				|| npcName == null || npcName.isEmpty()) return;
		if (level.getServer().isDedicatedServer())
		{
			delete(npcName, cityName);
		}
		else
		{
			delete(npcName, cityName, level.getServer().getWorldData().getLevelName());
		}
	}

	private static void deleteNpcJson(Path npcFile)
	{
		try
		{
			if (Files.deleteIfExists(npcFile))
			{
				LOGGER.info("NeoSim-NpcData: delete: Succeed, {}", npcFile.toAbsolutePath());
			}
			else
			{
				LOGGER.warn("NeoSim-NpcData: delete: File not found, {}", npcFile.toAbsolutePath());
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-NpcData: delete: Fail, path={}, error={}", npcFile.toAbsolutePath(), e.getMessage(), e);
		}
	}

	private static List<String> listNpcFiles(Path npcDir)
	{
		List<String> names = new ArrayList<>();
		if (!Files.exists(npcDir)) return names;

		try (Stream<Path> stream = Files.list(npcDir))
		{
			stream.filter(Files::isRegularFile)
				  .map(Path::getFileName)
				  .map(Path::toString)
				  .filter(name -> name.endsWith(".json"))
				  .map(name -> name.substring(0, name.length() - 5))
				  .forEach(names::add);
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-NpcData: listNpcFiles: Fail, path={}, error={}", npcDir.toAbsolutePath(), e.getMessage(), e);
		}
		return names;
	}

	// 写入
	private static void writeNpcJson(Path npcDir, String npcName, Entity entity)
	{
		Path npcFile = npcDir.resolve(npcName + ".json");

		try
		{
			Files.createDirectories(npcDir);

			{
				JsonObject json = new JsonObject();

				// 姓名
				json.addProperty("name", entity.getNpcName());
				json.addProperty("surname", entity.getNpcSurname());
				json.addProperty("givenName", entity.getNpcGivenName());

				// 性别
				json.addProperty("sex", entity.getSex());

				// 皮肤
				json.addProperty("skin", entity.getSkin());

				// UUID
				json.addProperty("uuid", entity.getUUID().toString());

				// 位置
				JsonObject pos = new JsonObject();
				pos.addProperty("x", entity.getX());
				pos.addProperty("y", entity.getY());
				pos.addProperty("z", entity.getZ());
				json.add("position", pos);

				// 朝向
				json.addProperty("yaw", entity.getYRot());
				json.addProperty("pitch", entity.getXRot());

				// 生命值
				json.addProperty("health", entity.getHealth());
				json.addProperty("maxHealth", entity.getMaxHealth());

				// 年龄
				json.addProperty("age", entity.getAge());

				// 职业等级
				JsonObject job = new JsonObject();
				job.addProperty("architect", entity.getJobArchitect());
				job.addProperty("farmer", entity.getJobFarmer());
				job.addProperty("miner", entity.getJobMiner());
				job.addProperty("courier", entity.getJobCourier());
				json.add("job", job);

				// 生活点
				BlockPos home = entity.getHomePos();
				if (home != null)
				{
					JsonObject homeObj = new JsonObject();
					homeObj.addProperty("x", home.getX());
					homeObj.addProperty("y", home.getY());
					homeObj.addProperty("z", home.getZ());
					json.add("home", homeObj);
					json.addProperty("homeBuilding", entity.getHomeBuilding());
				}

				// 孕期进度
				if (entity.getPregnancyStage() > 0.0F)
				{
					json.addProperty("pregnancy", entity.getPregnancyStage());
				}

				// 关系与族谱
				if (!entity.getPartner().isEmpty())
				{
					json.addProperty("partner", entity.getPartner());
				}
				List<String> parents = entity.getParentNames();
				if (!parents.isEmpty())
				{
					JsonArray parentsArr = new JsonArray();
					for (String p : parents) parentsArr.add(p);
					json.add("parents", parentsArr);
				}
				List<String> children = entity.getChildren();
				if (!children.isEmpty())
				{
					JsonArray childrenArr = new JsonArray();
					for (String c : children) childrenArr.add(c);
					json.add("children", childrenArr);
				}

				JsonUtil.write(npcFile, json);
				LOGGER.debug("NeoSim-NpcData: save: Succeed, {}", npcFile.toAbsolutePath());
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-NpcData: save: Fail, path={}, error={}", npcFile.toAbsolutePath(), e.getMessage(), e);
		}
	}

	// 读取
	private static JsonObject readNpcJson(Path npcFile)
	{
		if (!Files.exists(npcFile))
		{
			LOGGER.warn("NeoSim-NpcData: load: File not found, {}", npcFile.toAbsolutePath());
			return null;
		}

		JsonObject json = JsonUtil.readObject(npcFile);
		if (json == null)
		{
			// 内容被篡改/清空：备份.bak后删除原文件，该NPC数据视为不存在，游戏继续运行
			JsonUtil.backupCorrupted(npcFile);
			try
			{
				Files.deleteIfExists(npcFile);
				LOGGER.warn("NeoSim-NpcData: load: corrupted, backed up and removed, {}", npcFile.toAbsolutePath());
			}
			catch (IOException e)
			{
				LOGGER.error("NeoSim-NpcData: load: corrupted file removal fail, path={}, error={}",
						npcFile.toAbsolutePath(), e.getMessage(), e);
			}
			return null;
		}
		LOGGER.debug("NeoSim-NpcData: load: Succeed, {}", npcFile.toAbsolutePath());
		return json;
	}
}

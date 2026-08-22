package com.wenzai.neosim.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.life.Genealogy;
import com.wenzai.neosim.life.LifeSystem;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Manage
{
	private Manage()
	{
	}

	// 按城市人口（直接用 ModSavedData 内存人口，不再每次目录列举数文件）
	public static short getPopulation(ServerLevel level, String cityName)
	{
		return ModSavedData.get(level).getPopulation(cityName);
	}

	// 检查城市是否存在
	public static boolean cityExists(ServerLevel level, String cityName)
	{
		Path gameDir = FMLPaths.GAMEDIR.get();
		Path cityDir;
		if (level.getServer().isDedicatedServer())
		{
			cityDir = gameDir.resolve("NeoSim").resolve("data").resolve(cityName);
		}
		else
		{
			String saveName = level.getServer().getWorldData().getLevelName();
			cityDir = gameDir.resolve("NeoSim").resolve("data").resolve(saveName).resolve(cityName);
		}
		return Files.exists(cityDir);
	}

	// 持续补人
	public static void replenishPopulation(ServerLevel level, String cityName)
	{
		if (cityName.isEmpty()) return;

		// 人口已达上限：不再补人
		if (getPopulation(level, cityName) >= Config.MAX_POPULATION.get()) return;

		// 城市有档案但无实体（服务器重启）：先恢复玩家附近的
		if (getPopulation(level, cityName) > 0)
		{
			respawnNearPlayers(level, cityName);

			// 恢复后仍无任何已加载实体（档案位置都远离玩家）：本次不补人，等玩家靠近再按需恢复
			if (!hasLoadedNpc(level, cityName)) return;

			// 仍有流浪者（含未加载档案中的流浪者）：等人入住，不补
			if (hasWanderer(level, cityName)) return;
		}

		spawnWithAnnouncement(level, cityName);
	}

	// 城市内是否有流浪者：已加载实体+未加载档案双查
	private static boolean hasWanderer(ServerLevel level, String cityName)
	{
		Set<String> loadedNames = new HashSet<>();
		for (Entity npc : NpcRegistry.byCity(cityName))
		{
			loadedNames.add(npc.getNpcName());
			if (npc.getHomePos() == null)
			{
				return true;
			}
		}

		// 未加载的档案也计入：无生活点即流浪者（只读 home 字段，不完整解析）
		for (String name : NpcData.listNpcNames(level, cityName))
		{
			if (loadedNames.contains(name)) continue;
			int home = NpcData.homeStatus(level, cityName, name);
			if (home < 0) continue;   // 档案缺失
			if (home == 0) return true;  // 有档案且无家 → 流浪者
		}
		return false;
	}

	// 城市是否有已加载的NPC实体（C1：索引 O(1)）
	private static boolean hasLoadedNpc(ServerLevel level, String cityName)
	{
		return !NpcRegistry.byCity(cityName).isEmpty();
	}

	// 自动入城：生成新NPC并公告
	public static void spawnWithAnnouncement(ServerLevel level, String cityName)
	{
		if (getPopulation(level, cityName) >= Config.MAX_POPULATION.get()) return;

		Entity npc = Entity.NPC.get().create(level);
		if (npc == null)
		{
			NeoSim.LOGGER.error("NeoSim-spawnWithAnnouncement: Fail to create NPC");
			return;
		}

		// 随机姓名与性别
		Entity.generateAndSetName(npc);
		npc.setNpcName(npc.getNpcName());

		// 随机皮肤
		npc.setSkin(Entity.randomSkin(npc.getSex()));

		// 记录所属城市，用于死亡时删除文件
		npc.setCityName(cityName);

		// 有空位则分配生活点（先于保存）
		CityLivingManager.tryAssignHome(level, npc);

		// 放置在世界出生点
		BlockPos spawnPos = level.getSharedSpawnPos();
		npc.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D, 0.0F, 0.0F);

		// 加入世界
		level.addFreshEntity(npc);

		// 保存数据
		if (level.getServer().isDedicatedServer())
		{
			NpcData.save(npc, cityName);
		}
		else
		{
			String saveName = level.getServer().getWorldData().getLevelName();
			NpcData.save(npc, cityName, saveName);
		}

		// 更新人口（内存值 +1，不再目录列举）
		short pop = getPopulation(level, cityName);
		ModSavedData.get(level).setPopulation(cityName, (short) (pop + 1), level);

		// 公告
		LifeSystem.announce(level, cityName, LifeSystem.tpl(Config.ANNOUNCE_SPAWN, npc.getNpcName()));
		NeoSim.LOGGER.info("NeoSim-spawnWithAnnouncement: Spawned {} (sex={}) in city {}",
				npc.getNpcName(), npc.getSex(), cityName);
	}

	// 从文件恢复城市中所有NPC（服务器重启/全量恢复）
	public static void restoreAll(ServerLevel level, String cityName)
	{
		List<String> npcNames = NpcData.listNpcNames(level, cityName);

		if (npcNames.isEmpty())
		{
			NeoSim.LOGGER.info("NeoSim-restoreAll: No NPC files found for city {}", cityName);
			return;
		}

		int restored = 0;
		for (String npcName : npcNames)
		{
			if (spawnSingle(level, cityName, npcName) != null)
			{
				restored++;
			}
		}

		// 恢复不改变人口：档案本已计入 CityData.population，实体只是重新实体化
		NeoSim.LOGGER.info("NeoSim-restoreAll: Restored {} NPCs for city {}", restored, cityName);
	}

	// 从文件恢复单个NPC
	public static Entity spawnSingle(ServerLevel level, String cityName, String npcName)
	{
		return spawnSingle(level, cityName, npcName, null);
	}

	// 从文件恢复单个NPC
	public static Entity spawnSingle(ServerLevel level, String cityName, String npcName, BlockPos atPos)
	{
		JsonObject json = NpcData.load(level, cityName, npcName);
		if (json == null) return null;

		Entity npc = Entity.NPC.get().create(level);
		if (npc == null)
		{
			NeoSim.LOGGER.error("NeoSim-spawnSingle: Fail to create NPC for {}", npcName);
			return null;
		}

		// 恢复姓名
		CompoundTag tag = npc.getPersistentData();
		String surname = JsonUtil.getString(json, "surname", null);
		if (surname != null) tag.putString(Entity.KEY_SURNAME, surname);
		String givenName = JsonUtil.getString(json, "givenName", null);
		if (givenName != null) tag.putString(Entity.KEY_GIVEN_NAME, givenName);
		String fullName = JsonUtil.getString(json, "name", null);
		if (fullName != null) tag.putString(Entity.KEY_FULL_NAME, fullName);
		npc.setNpcName(JsonUtil.getString(json, "name", ""));

		// 记录所属城市，用于死亡时删除文件
		npc.setCityName(cityName);

		// L2：恢复文件中的 UUID（spawnSingle 不新建随机 UUID，保证关系/临产等按 UUID 的逻辑跨重载稳定）
		String uuidStr = JsonUtil.getString(json, "uuid", null);
		if (uuidStr != null && !uuidStr.isEmpty())
		{
			try
			{
				npc.setUUID(java.util.UUID.fromString(uuidStr));
			}
			catch (IllegalArgumentException ignored)
			{
			}
		}

		// 恢复性别
		String sex = JsonUtil.getString(json, "sex", null);
		if (sex != null) npc.setSex(sex);

		// 恢复皮肤
		String skin = JsonUtil.getString(json, "skin", null);
		if (skin != null) npc.setSkin(skin);

		// 恢复位置
		if (atPos != null)
		{
			npc.moveTo(atPos.getX() + 0.5D, atPos.getY() + 1.0D, atPos.getZ() + 0.5D, 0.0F, 0.0F);
		}
		else
		{
			JsonObject pos = JsonUtil.getObject(json, "position");
			if (pos != null)
			{
				double x = JsonUtil.getDouble(pos, "x", 0);
				double y = JsonUtil.getDouble(pos, "y", 64);
				double z = JsonUtil.getDouble(pos, "z", 0);
				float yaw = JsonUtil.getFloat(json, "yaw", 0F);
				float pitch = JsonUtil.getFloat(json, "pitch", 0F);
				npc.moveTo(x, y, z, yaw, pitch);
			}
		}

		// 恢复生命值
		if (json.has("health"))
		{
			npc.setHealth(JsonUtil.getFloat(json, "health", npc.getMaxHealth()));
		}

		// 恢复年龄
		if (json.has("age")) npc.setAge(JsonUtil.getShort(json, "age", (short) 18));

		// 恢复职业等级
		JsonObject job = JsonUtil.getObject(json, "job");
		if (job != null)
		{
			if (job.has("architect")) npc.setJobArchitect(JsonUtil.getByte(job, "architect", (byte) 1));
			if (job.has("farmer")) npc.setJobFarmer(JsonUtil.getByte(job, "farmer", (byte) 1));
			if (job.has("miner")) npc.setJobMiner(JsonUtil.getByte(job, "miner", (byte) 1));
			if (job.has("courier")) npc.setJobCourier(JsonUtil.getByte(job, "courier", (byte) 1));
		}

		// 恢复生活点
		JsonObject home = JsonUtil.getObject(json, "home");
		if (home != null)
		{
			BlockPos homePos = new BlockPos(
					JsonUtil.getInt(home, "x", 0), JsonUtil.getInt(home, "y", 0), JsonUtil.getInt(home, "z", 0));
			String homeBuilding = JsonUtil.getString(json, "homeBuilding", "");
			npc.setHome(homePos, homeBuilding);
		}

		// 恢复孕期进度
		if (json.has("pregnancy")) npc.setPregnancyStage(JsonUtil.getFloat(json, "pregnancy", 0F));

		// 恢复关系与族谱
		String partner = JsonUtil.getString(json, "partner", null);
		if (partner != null) npc.setPartner(partner);
		if (json.has("parents"))
		{
			JsonArray parents = JsonUtil.getArray(json, "parents");
			String p1 = parents.size() > 0 && parents.get(0).isJsonPrimitive() ? parents.get(0).getAsString() : "";
			String p2 = parents.size() > 1 && parents.get(1).isJsonPrimitive() ? parents.get(1).getAsString() : "";
			npc.setParents(p1, p2);
		}
		if (json.has("children"))
		{
			JsonArray children = JsonUtil.getArray(json, "children");
			List<String> childList = new java.util.ArrayList<>();
			for (JsonElement e : children)
			{
				if (e.isJsonPrimitive()) childList.add(e.getAsString());
			}
			npc.setChildren(childList);
		}

		level.addFreshEntity(npc);
		NeoSim.LOGGER.info("NeoSim-spawnSingle: Restored {} (sex={}) at ({}, {}, {})",
				npc.getNpcName(), npc.getSex(), npc.getX(), npc.getY(), npc.getZ());
		return npc;
	}

	// 卸载阈值：远离所有玩家超过该距离则卸载
	private static final double DESPAWN_DISTANCE = 50.0D;

	// 恢复阈值：玩家进入该距离才从文件恢复，与卸载阈值留滞后带避免来回抖动
	private static final double RESPAWN_DISTANCE = 40.0D;

	// 远离所有玩家的NPC卸载
	public static void despawnFarFromPlayers(ServerLevel level, String cityName)
	{
		List<? extends Player> players = level.players();
		// C1：城市实体集合快照（避免遍历中实体被卸载导致并发修改）
		for (Entity npc : new java.util.ArrayList<>(NpcRegistry.byCity(cityName)))
		{
			// GUI冻结中：不卸载
			if (npc.isFrozen()) continue;

			// 雇佣：建筑进度全局推进，不因玩家远离而停
			if (npc.hasJob()) continue;
			if (nearestPlayerDistSqr(npc, players) > DESPAWN_DISTANCE * DESPAWN_DISTANCE)
			{
				despawnNpc(level, npc);
			}
		}
	}

	// 恢复在玩家附近的未加载NPC
	public static void respawnNearPlayers(ServerLevel level, String cityName)
	{
		List<? extends Player> players = level.players();
		if (players.isEmpty()) return;

		Set<String> loadedNames = new HashSet<>();
		for (Entity npc : NpcRegistry.byCity(cityName))
		{
			loadedNames.add(npc.getNpcName());
		}

		for (String name : NpcData.listNpcNames(level, cityName))
		{
			if (loadedNames.contains(name)) continue;
			// 轻量读取仅取 position，不完整解析
			BlockPos pos = NpcData.readPosition(level, cityName, name);
			if (pos == null) continue;

			for (Player p : players)
			{
				double dx = p.getX() - pos.getX();
				double dy = p.getY() - pos.getY();
				double dz = p.getZ() - pos.getZ();
				if (dx * dx + dy * dy + dz * dz < RESPAWN_DISTANCE * RESPAWN_DISTANCE)
				{
					spawnSingle(level, cityName, name);
					break;
				}
			}
		}
	}

	// 快照（最新状态写回文件）后卸载实体
	public static void despawnNpc(ServerLevel level, Entity npc)
	{
		String name = npc.getNpcName();
		String city = npc.getCityName();
		// L2：卸载时清除临产目标缓存（防残留）
		com.wenzai.neosim.life.ReproductionSystem.clearBirthTarget(name);
		npc.syncToJsonNow();
		npc.discard();
		NeoSim.LOGGER.info("NeoSim-despawnNpc: Unloaded {} (city={})", name, city);
	}

	// 数据侧死亡：实体未加载，只动文件与记录
	public static void dieUnloaded(ServerLevel level, String cityName, String npcName)
	{
		JsonObject json = NpcData.load(level, cityName, npcName);
		if (json == null) return;

		int age = json.has("age") ? json.get("age").getAsShort() : 0;
		LifeSystem.announce(level, cityName, LifeSystem.tpl(Config.ANNOUNCE_DEATH_TEMPLATE,
				npcName, Config.ANNOUNCE_DEATH_CAUSE_OLD_AGE.get(),
				LifeSystem.tpl(Config.ANNOUNCE_DEATH_REMARK_OLD, age)));

		// 族谱摘除（含未加载亲戚的文件改写）+删关系文件
		Genealogy.onDeath(level, cityName, npcName);

		// 退房
		CityLivingManager.releaseHomeByName(level, cityName, npcName);

		// 删档+人口同步（内存值 -1）
		NpcData.delete(level, cityName, npcName);
		short pop = getPopulation(level, cityName);
		ModSavedData.get(level).setPopulation(cityName, (short) Math.max(0, pop - 1), level);
		NeoSim.LOGGER.info("NeoSim-dieUnloaded: '{}' died of old age while unloaded (age {})", npcName, age);
	}

	// 到最近玩家的距离平方
	private static double nearestPlayerDistSqr(Entity npc, List<? extends Player> players)
	{
		double best = Double.MAX_VALUE;
		for (Player p : players)
		{
			double d = npc.distanceToSqr(p);
			if (d < best) best = d;
		}
		return best;
	}

	// 在指定城市生成NPC，姓名与性别随机
	public static void spawnAt(ServerLevel level, BlockPos pos, String cityName)
	{
		// 人口上限检查
		short currentPop = getPopulation(level, cityName);
		if (currentPop >= Config.MAX_POPULATION.get())
		{
			NeoSim.LOGGER.warn("NeoSim-spawnAt: Population at max ({}), city: {}", Config.MAX_POPULATION.get(), cityName);
			return;
		}

		Entity npc = Entity.NPC.get().create(level);
		if (npc == null)
		{
			NeoSim.LOGGER.error("NeoSim-spawnAt: Fail to create NPC");
			return;
		}

		// 随机姓名与性别
		Entity.generateAndSetName(npc);
		npc.setNpcName(npc.getNpcName());

		// 随机皮肤
		npc.setSkin(Entity.randomSkin(npc.getSex()));

		// 记录所属城市，用于死亡时删除文件
		npc.setCityName(cityName);

		// 有空位则分配生活点（先于保存）
		CityLivingManager.tryAssignHome(level, npc);

		// 放置到指定位置（其实就是指令使用者的原地）
		npc.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);

		// 加入世界
		level.addFreshEntity(npc);

		// 保存数据
		if (level.getServer().isDedicatedServer())
		{
			NpcData.save(npc, cityName);
		}
		else
		{
			String saveName = level.getServer().getWorldData().getLevelName();
			NpcData.save(npc, cityName, saveName);
		}

		// 更新人口（内存值 +1）
		short pop = getPopulation(level, cityName);
		ModSavedData.get(level).setPopulation(cityName, (short) (pop + 1), level);

		NeoSim.LOGGER.info("NeoSim-spawnAt: Spawned {} (sex={}) in city {} at ({}, {}, {})",
				npc.getNpcName(), npc.getSex(), cityName, npc.getX(), npc.getY(), npc.getZ());
	}

	// 生成第一个NPC
	public static class NpcAdd
	{
		private NpcAdd()
		{
		}

		static void spawn(ServerLevel level)
		{
			Entity npc = Entity.NPC.get().create(level);
			if (npc == null)
			{
				NeoSim.LOGGER.error("NeoSim-NpcAdd: Fail");
				return;
			}

			// 分配姓名
			Entity.generateAndSetName(npc);
			npc.setNpcName(npc.getNpcName());

			// 随机皮肤
			npc.setSkin(Entity.randomSkin(npc.getSex()));

			// 记录所属城市，用于死亡时删除文件
			String cityName = ModSavedData.getActiveCityName();
			npc.setCityName(cityName);

			// 有空位则分配生活点（先于保存）
			CityLivingManager.tryAssignHome(level, npc);

			// 放置在世界出生点
			BlockPos spawnPos = level.getSharedSpawnPos();
			npc.moveTo(
					spawnPos.getX() + 0.5D,
					spawnPos.getY(),
					spawnPos.getZ() + 0.5D,
					0.0F,
					0.0F
			);

			// 加入世界
			level.addFreshEntity(npc);

			// 保存数据
			if (cityName.isEmpty())
			{
				NeoSim.LOGGER.warn("NeoSim-Add: cityName is empty");
			}
			else
			{
				if (level.getServer().isDedicatedServer())
				{
					NpcData.save(npc, cityName);
				}
				else
				{
					String saveName = level.getServer().getWorldData().getLevelName();
					NpcData.save(npc, cityName, saveName);
				}
			}

			NeoSim.LOGGER.info("NeoSim-Add: Spawned {} at ({}, {}, {})",
					npc.getNpcName(), npc.getX(), npc.getY(), npc.getZ());
		}
	}
}

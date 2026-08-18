package com.wenzai.neosim.npc;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.building.BuildingInstance;
import com.wenzai.neosim.building.ConstructionTask;
import com.wenzai.neosim.building.ControlBoxPersistence;
import com.wenzai.neosim.building.ControlBoxPersistence.ControlBoxRecord;
import com.wenzai.neosim.building.ControlBoxPersistence.Resident;
import com.wenzai.neosim.life.LifeSystem;
import com.wenzai.neosim.schematic.BuildingType;
import com.wenzai.neosim.storage.FileCreater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 城市级生活点池：完工住宅注册空位、无家NPC入住、退房/驱逐时空位自动归还
public class CityLivingManager
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private CityLivingManager() {}

	// 建筑完工：注册生活点空位
	public static void onBuildingCompleted(ServerLevel level, BuildingInstance building)
	{
		String city = ConstructionTask.cityOf(building, level);
		if (city == null || city.isEmpty()) return;

		ControlBoxRecord rec = ControlBoxPersistence.findRecord(level, city, building.getControlBoxPos());
		if (rec == null) return;

		int free = freeSlotCount(rec, building);
		if (free <= 0) return;

		// 建造者优先入住
		String workerName = building.getWorkerName();
		if (workerName != null && !workerName.isEmpty())
		{
			Entity builder = findNpc(level, workerName);
			if (builder != null && builder.getHomePos() == null)
			{
				assignHome(level, city, rec, building, builder);
				free--;
			}
		}

		// 剩余空位分配给城市内无家NPC（C1：城市实体索引，免全服扫描）
		if (free > 0)
		{
			for (Entity npc : new java.util.ArrayList<>(NpcRegistry.byCity(city)))
			{
				if (free <= 0) break;
				if (npc.getHomePos() != null) continue;
				if (assignHome(level, city, rec, building, npc))
				{
					free--;
				}
			}
		}
	}

	// NPC生成时：城市有空位则分配，无则保持流浪
	public static void tryAssignHome(ServerLevel level, Entity npc)
	{
		String city = npc.getCityName();
		if (city.isEmpty() || npc.getHomePos() != null) return;

		for (ControlBoxRecord rec : ControlBoxPersistence.load(level, city))
		{
			// 空位从记录中算，只知道建筑类型
			if (freeSlotCount(rec) > 0)
			{
				assignHome(level, city, rec, null, npc);
				return;
			}
		}
	}

	// 退房（NPC死亡等）：从记录居民列表移除，空位归还
	public static void releaseHome(ServerLevel level, Entity npc)
	{
		String name = npc.getNpcName();
		String city = npc.getCityName();
		if (name.isEmpty() || city.isEmpty()) return;

		if (releaseHomeByName(level, city, name))
		{
			npc.clearHomeAndSync();
		}
	}

	// 按名退房（成年离家/死亡）：仅动记录
	public static boolean releaseHomeByName(ServerLevel level, String cityName, String name)
	{
		if (name == null || name.isEmpty() || cityName == null || cityName.isEmpty()) return false;

		List<ControlBoxRecord> all = ControlBoxPersistence.load(level, cityName);
		boolean changed = false;
		for (ControlBoxRecord rec : all)
		{
			if (rec.residents().removeIf(r -> r.name().equals(name)))
			{
				changed = true;
				break;
			}
		}
		if (changed)
		{
			ControlBoxPersistence.save(level, cityName, all);
			LOGGER.info("NeoSim-CityLivingManager: '{}' checked out of home", name);
		}
		return changed;
	}

	// 记录被删除（模盒/控制箱被破坏）时，全部居民失去家（应该有问题）
	public static void evictResidents(ServerLevel level, ControlBoxRecord record)
	{
		int count = record.residents().size();
		for (Resident r : record.residents())
		{
			Entity npc = findNpc(level, r.name());
			if (npc != null)
			{
				npc.clearHomeAndSync();
			}
		}
		record.residents().clear();
		LOGGER.info("NeoSim-CityLivingManager: evicted {} residents", count);
	}

	// 驱逐单个住户：清实体家 + 记录移除 + 落盘 + 公告；返回是否成功
	public static boolean evictResident(ServerLevel level, String cityName, ControlBoxRecord rec, String residentName)
	{
		if (residentName == null || residentName.isEmpty()) return false;

		Resident target = null;
		for (Resident r : rec.residents())
		{
			if (r.name().equals(residentName))
			{
				target = r;
				break;
			}
		}
		if (target == null) return false;

		Entity npc = findNpc(level, residentName);
		if (npc != null)
		{
			npc.clearHomeAndSync();
		}

		rec.residents().remove(target);
		ControlBoxPersistence.updateRecord(level, cityName, rec);
		announce(level, cityName, LifeSystem.tpl(Config.ANNOUNCE_EVICT_RESIDENT,
				residentName, rec.schematicName()));
		LOGGER.info("NeoSim-CityLivingManager: evicted '{}' from '{}'", residentName, rec.schematicName());
		return true;
	}

	// 清空全部住户：逐个清实体家 + 清空列表 + 落盘 + 公告；返回驱逐人数
	public static int evictAllResidents(ServerLevel level, String cityName, ControlBoxRecord rec)
	{
		int count = rec.residents().size();
		for (Resident r : rec.residents())
		{
			Entity npc = findNpc(level, r.name());
			if (npc != null)
			{
				npc.clearHomeAndSync();
			}
		}
		rec.residents().clear();
		ControlBoxPersistence.updateRecord(level, cityName, rec);
		announce(level, cityName, LifeSystem.tpl(Config.ANNOUNCE_EVICT_ALL,
				rec.schematicName(), count));
		LOGGER.info("NeoSim-CityLivingManager: evicted all {} residents from '{}'", count, rec.schematicName());
		return count;
	}

	// 手动安排无家NPC入住：校验本城+无家，复用空位分配；返回null=成功，否则为玩家聊天提示
	@Nullable
	public static String moveInHomeless(ServerLevel level, String cityName, ControlBoxRecord rec, String npcName)
	{
		if (npcName == null || npcName.isEmpty()) return "§c没有指定 NPC";
		Entity npc = findNpc(level, npcName);
		if (npc == null) return "§c找不到 NPC: §f" + npcName;
		if (!cityName.equals(npc.getCityName())) return "§c" + npcName + " §e不属于这座城市";
		if (npc.getHomePos() != null) return "§c" + npcName + " §e已经有家了";
		if (!assignToExistingHome(level, cityName, npc, rec)) return "§c没有空房间";
		return null;
	}

	// 空位数量（无生活点的住宅按1个控制箱位计）
	public static int freeSlotCount(ControlBoxRecord rec)
	{
		return freeSlotCount(rec, null);
	}

	private static int freeSlotCount(ControlBoxRecord rec, BuildingInstance building)
	{
		int slots = rec.livingPoints().size();
		if (slots == 0 && isResidential(building))
		{
			// 控制箱位
			slots = 1;
		}
		return slots - rec.residents().size();
	}

	private static boolean isResidential(BuildingInstance building)
	{
		return building != null
				&& building.getSchematic() != null
				&& building.getSchematic().getType() == BuildingType.RESIDENTIAL;
	}

	// 入住：取第一个未被占用的生活点，登记并公告
	private static boolean assignHome(ServerLevel level, String cityName,
									  ControlBoxRecord rec, BuildingInstance building, Entity npc)
	{
		BlockPos slot = findFreeSlot(rec, building);
		if (slot == null) return false;

		// 生活点被堵：向上最多8格找空气——仅用于NPC站立/回家坐标，不污染占用记录
		BlockPos stand = findAirAbove(level, slot);

		registerResident(level, cityName, rec, npc, slot, stand);
		return true;
	}

	// 婚姻同居：把NPC登记进已有住宅的空位
	public static boolean assignToExistingHome(ServerLevel level, String cityName, Entity npc, ControlBoxRecord rec)
	{
		BlockPos slot = findFreeSlot(rec, null);
		if (slot == null) return false;

		// 生活点被堵：向上最多8格找空气——仅用于NPC站立/回家坐标，不污染占用记录
		BlockPos stand = findAirAbove(level, slot);

		registerResident(level, cityName, rec, npc, slot, stand);
		return true;
	}

	// 找第一个未被占用的生活点（无生活点记录时按1个控制箱位计）；占用按生活点原坐标（列）判定
	private static BlockPos findFreeSlot(ControlBoxRecord rec, BuildingInstance building)
	{
		if (!rec.livingPoints().isEmpty())
		{
			Set<Long> occupied = new HashSet<>();
			for (Resident r : rec.residents())
			{
				occupied.add(columnKey(r.x(), r.z()));
			}
			for (BlockPos p : rec.livingPoints())
			{
				if (!occupied.contains(columnKey(p.getX(), p.getZ())))
				{
					return p;
				}
			}
			return null;
		}
		if (isResidential(building) || building == null)
		{
			// 无生活点的住宅按1个控制箱位计
			BlockPos slot = rec.boxPos();
			for (Resident r : rec.residents())
			{
				if (r.x() == slot.getX() && r.z() == slot.getZ())
				{
					return null;
				}
			}
			return slot;
		}
		return null;
	}

	// 登记入住：记录生活点原坐标（占用判定依据），NPC回家坐标用抬升后的站立点
	private static void registerResident(ServerLevel level, String cityName, ControlBoxRecord rec,
										 Entity npc, BlockPos slot, BlockPos stand)
	{
		npc.setHomeAndSync(stand, rec.schematicName());
		rec.residents().add(new Resident(npc.getNpcName(), slot.getX(), slot.getY(), slot.getZ()));
		ControlBoxPersistence.updateRecord(level, cityName, rec);

		announce(level, cityName, LifeSystem.tpl(Config.ANNOUNCE_MOVE_IN,
				npc.getNpcName(), rec.schematicName()));
		LOGGER.info("NeoSim-CityLivingManager: '{}' moved into '{}' at {} (stand {})", npc.getNpcName(),
				rec.schematicName(), slot, stand);
	}

	// (x,z) 列打包成唯一long key
	private static long columnKey(int x, int z)
	{
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	// 生活点被堵：向上最多8格找空气
	private static BlockPos findAirAbove(ServerLevel level, BlockPos pos)
	{
		BlockPos.MutableBlockPos p = pos.mutable();
		for (int i = 0; i < 8; i++)
		{
			if (level.getBlockState(p).canBeReplaced())
			{
				return p.immutable();
			}
			p.move(Direction.UP);
		}
		return pos;
	}

	private static Entity findNpc(ServerLevel level, String name)
	{
		return NpcRegistry.findByName(name);
	}

	// 公告给该城市在线玩家
	private static void announce(ServerLevel level, String cityName, String msg)
	{
		if (level.getServer() == null) return;
		boolean dedicated = level.getServer().isDedicatedServer();
		String saveName = dedicated ? null : level.getServer().getWorldData().getLevelName();
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers())
		{
			boolean inCity = dedicated
					? FileCreater.isPlayerInCity(cityName, player.getName().getString())
					: FileCreater.isPlayerInCity(cityName, saveName, player.getName().getString());
			if (inCity)
			{
				player.displayClientMessage(Component.literal(msg), false);
			}
		}
	}
}

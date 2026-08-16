// 婚姻与同居

package com.wenzai.neosim.life;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.building.ControlBoxPersistence;
import com.wenzai.neosim.building.ControlBoxPersistence.ControlBoxRecord;
import com.wenzai.neosim.building.ControlBoxPersistence.Resident;
import com.wenzai.neosim.life.Relationship.RelationshipData;
import com.wenzai.neosim.life.Relationship.RelationshipLevel;
import com.wenzai.neosim.npc.CityLivingManager;
import com.wenzai.neosim.npc.Entity;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MarriageSystem
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Random RANDOM = new Random();

	private MarriageSystem() {}

	// 每分钟扫描城市内关系对
	public static void onServerTick(ServerLevel level, String city)
	{
		if (city == null || city.isEmpty()) return;

		for (RelationshipData rel : RelationshipPersistence.loadAll(level, city))
		{
			if (rel.level() != RelationshipLevel.BESTFRIENDS || rel.subLevel() < marriageSubLevel()) continue;
			attemptMarriage(level, city, rel.folk1(), rel.folk2());
		}
	}

	// 结婚条件判定+掷硬币（任一条件不满足即跳过）
	private static void attemptMarriage(ServerLevel level, String city, String nameA, String nameB)
	{
		if (nameA == null || nameB == null || nameA.isEmpty() || nameB.isEmpty() || nameA.equals(nameB)) return;

		Entity a = findLoaded(level, nameA);
		Entity b = findLoaded(level, nameB);
		if (a == null || b == null) return;

		// 条件：关系度
		RelationshipData rel = RelationshipPersistence.loadPair(level, city, nameA, nameB);
		if (rel == null || rel.level() != RelationshipLevel.BESTFRIENDS || rel.subLevel() < marriageSubLevel()) return;

		// 条件：异性
		if (a.getSex().equals(b.getSex())) return;

		// 条件：双方都有家
		if (a.getHomePos() == null || b.getHomePos() == null) return;

		// 条件：双方都单身
		if (!a.getPartner().isEmpty() || !b.getPartner().isEmpty()) return;

		// 条件：双方成年
		if (!a.isAdult() || !b.isAdult()) return;

		// 条件：非血亲
		if (Genealogy.isBloodRelated(level, city, nameA, nameB)) return;

		// 掷硬币
		boolean married = RANDOM.nextDouble() < marriageChance();
		performMarriage(level, city, a, b, married);
	}

	// 执行结婚：改姓+搬家+伴侣互记+公告
	private static void performMarriage(ServerLevel level, String city, Entity a, Entity b, boolean married)
	{
		Entity husband = "male".equals(a.getSex()) ? a : b;
		Entity wife = husband == a ? b : a;

		// 搬家整合：选空位多的一方的家作新家（平手保留男方）
		ControlBoxRecord husbandRec = residentRecordOf(level, city, husband);
		ControlBoxRecord wifeRec = residentRecordOf(level, city, wife);
		ControlBoxRecord keepRec = pickHome(husbandRec, wifeRec);
		boolean wifeMoves = keepRec != null && keepRec != wifeRec;
		boolean husbandMoves = keepRec != null && keepRec != husbandRec;

		// 退旧家必须早于改名
		if (wifeMoves)
		{
			CityLivingManager.releaseHome(level, wife);
		}
		else if (husbandMoves)
		{
			CityLivingManager.releaseHome(level, husband);
		}

		// MARRIED：女方随夫姓
		if (married)
		{
			String oldWifeName = wife.getNpcName();
			String surname = husband.getNpcSurname();
			wife.setNpcName(surname.isEmpty() ? wife.getNpcSurname() : surname, wife.getNpcGivenName());

			// 改名后同步关系文件、族谱引用与住宅居民登记
			if (!oldWifeName.equals(wife.getNpcName()))
			{
				RelationshipPersistence.renameAllFor(level, city, oldWifeName, wife.getNpcName());
				Genealogy.onRename(level, city, wife, oldWifeName);

				// 女方留在保留家中（自己家被保留或男方搬来）：居民登记同步改名
				if (keepRec != null && !wifeMoves)
				{
					renameResident(level, city, keepRec, oldWifeName, wife.getNpcName());
				}
			}
		}

		// 入住保留的家
		if (wifeMoves)
		{
			CityLivingManager.assignToExistingHome(level, city, wife, keepRec);
		}
		else if (husbandMoves)
		{
			CityLivingManager.assignToExistingHome(level, city, husband, keepRec);
		}

		// 伴侣互记
		husband.setPartner(wife.getNpcName());
		wife.setPartner(husband.getNpcName());
		husband.syncToJson();
		wife.syncToJson();

		// 公告
		String hName = husband.getNpcName();
		String wName = wife.getNpcName();
		if (married)
		{
			LifeSystem.announce(level, city, LifeSystem.tpl(Config.ANNOUNCE_MARRIAGE, hName, wName));
			LOGGER.info("NeoSim-MarriageSystem: '{}' married '{}'", hName, wName);
		}
		else
		{
			LifeSystem.announce(level, city, LifeSystem.tpl(Config.ANNOUNCE_COHABIT, hName, wName));
			LOGGER.info("NeoSim-MarriageSystem: '{}' and '{}' started cohabiting", hName, wName);
		}
	}

	// 空位更多的一方留作新家（平手保留男方家）
	private static ControlBoxRecord pickHome(ControlBoxRecord husbandRec, ControlBoxRecord wifeRec)
	{
		if (husbandRec == null) return wifeRec;
		if (wifeRec == null) return husbandRec;
		if (CityLivingManager.freeSlotCount(wifeRec) > CityLivingManager.freeSlotCount(husbandRec))
		{
			return wifeRec;
		}
		return husbandRec;
	}

	// 某NPC所在的居民登记记录
	private static ControlBoxRecord residentRecordOf(ServerLevel level, String city, Entity npc)
	{
		for (ControlBoxRecord rec : ControlBoxPersistence.load(level, city))
		{
			for (Resident r : rec.residents())
			{
				if (r.name().equals(npc.getNpcName())) return rec;
			}
		}
		return null;
	}

	// 居民登记改名
	private static void renameResident(ServerLevel level, String city, ControlBoxRecord rec,
									   String oldName, String newName)
	{
		List<Resident> updated = new ArrayList<>();
		for (Resident r : rec.residents())
		{
			updated.add(r.name().equals(oldName) ? new Resident(newName, r.x(), r.y(), r.z()) : r);
		}
		ControlBoxRecord updatedRec = new ControlBoxRecord(rec.x(), rec.y(), rec.z(), rec.schematicName(),
				rec.originX(), rec.originY(), rec.originZ(), rec.placerName(), rec.author(),
				rec.livingPoints(), updated, rec.rent());
		ControlBoxPersistence.updateRecord(level, city, updatedRec);
	}

	// 解除同居：满级关系破裂时清理双方伴侣互记
	public static void dissolve(ServerLevel level, String city, String a, String b)
	{
		boolean changed = false;
		Entity ea = findLoaded(level, a);
		if (ea != null && ea.getPartner().equals(b))
		{
			ea.setPartner("");
			ea.syncToJson();
			changed = true;
		}
		Entity eb = findLoaded(level, b);
		if (eb != null && eb.getPartner().equals(a))
		{
			eb.setPartner("");
			eb.syncToJson();
			changed = true;
		}
		if (changed)
		{
			LifeSystem.announce(level, city, LifeSystem.tpl(Config.ANNOUNCE_BREAKUP, a, b));
			LOGGER.info("NeoSim-MarriageSystem: '{}' and '{}' dissolved cohabitation", a, b);
		}
	}

	private static Entity findLoaded(ServerLevel level, String name)
	{
		if (name == null || name.isEmpty()) return null;
		for (net.minecraft.world.entity.Entity e : level.getAllEntities())
		{
			if (e instanceof Entity npc && name.equals(npc.getNpcName()))
			{
				return npc;
			}
		}
		return null;
	}

	private static double marriageChance()
	{
		try
		{
			return Config.LIFE_MARRIAGE_CHANCE.get();
		}
		catch (IllegalStateException ignored)
		{
			// 配置尚未加载，使用默认值
			return 0.5;
		}
	}

	// 结婚所需关系度
	private static int marriageSubLevel()
	{
		try
		{
			return Config.LIFE_MARRIAGE_SUBLEVEL.get();
		}
		catch (IllegalStateException ignored)
		{
			// 配置尚未加载，使用默认值
			return 100;
		}
	}
}

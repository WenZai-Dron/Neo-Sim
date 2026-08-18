// 生育系统：孕期推进->诊所分娩->新生儿落地

package com.wenzai.neosim.life;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.building.ControlBoxPersistence;
import com.wenzai.neosim.building.ControlBoxPersistence.ControlBoxRecord;
import com.wenzai.neosim.building.ControlBoxPersistence.Resident;
import com.wenzai.neosim.npc.CityLivingManager;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.npc.NpcGoals;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;

import java.util.*;

public class ReproductionSystem
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Random RANDOM = new Random();

	// 女方超过该年龄不再受孕
	// 夜晚每秒进度
	private static final float MATING_STEP = 0.02F;

	// 到达分娩地点的判定阈值）
	private static final double BIRTH_REACH_RANGE = 9.0D;

	// 职业等级上限
	private static final int JOB_MAX_LEVEL = 10;

	// 临产目标缓存，分娩后清除（L2：按 NPC 名字 key——名字稳定、防 UUID 丢失/跨存档残留；死亡时由 Entity.die 清除）
	private static final Map<String, BlockPos> BIRTH_TARGETS = new HashMap<>();

	// L2：按 NPC 名清除临产目标（死亡/卸载时调用）
	public static void clearBirthTarget(String npcName)
	{
		if (npcName != null && !npcName.isEmpty()) BIRTH_TARGETS.remove(npcName);
	}

	// L2：服务器停止时清空（防跨存档残留）
	public static void clearAllBirthTargets()
	{
		BIRTH_TARGETS.clear();
	}

	private ReproductionSystem() {}

	// 每天清晨：孕期推进，临产者出发去Clinic/Hospital
	public static void onDayStart(ServerLevel level, String city)
	{
		if (city.isEmpty()) return;

		for (Entity npc : loadedNpcs(level, city))
		{
			float stage = npc.getPregnancyStage();
			if (stage <= 0.0F) continue;

			if (stage >= 1.0F)
			{
				goToClinic(level, city, npc);
			}
			else
			{
				npc.setPregnancyStage(stage + pregnancyStep());
				npc.syncToJson();
			}
		}

		// 未加载孕妇：数据侧推进，达1.0留待实体恢复后清晨去诊所
		Set<String> loadedNames = new HashSet<>();
		for (Entity npc : loadedNpcs(level, city)) loadedNames.add(npc.getNpcName());

		for (String name : NpcData.listNpcNames(level, city))
		{
			if (loadedNames.contains(name)) continue;
			JsonObject json = NpcData.load(level, city, name);
			if (json == null || !json.has("pregnancy")) continue;
			float stage = json.get("pregnancy").getAsFloat();
			if (stage <= 0.0F || stage >= 1.0F) continue;
			NpcData.patchPregnancy(level, city, name, Math.min(1.0F, stage + pregnancyStep()));
		}
	}

	// 夜晚每分钟：符合条件者发起
	public static void onMinuteNight(ServerLevel level, String city)
	{
		if (level.getDayTime() % 24000 < 12000) return;

		for (Entity npc : loadedNpcs(level, city))
		{
			// 女性+未孕+未在进度中+未超生育年龄
			if (!"female".equals(npc.getSex())) continue;
			if (npc.getPregnancyStage() > 0.0F) continue;
			if (npc.getMatingStage() >= 0.0F) continue;
			if (npc.getAge() >= pregnancyMaxAge()) continue;

			// 有伴侣且双方夜晚都在家
			if (npc.getPartner().isEmpty()) continue;
			if (!bothAtHome(level, npc)) continue;

			npc.setMatingStage(0.0F);
		}
	}

	// 每秒：推进造人进度（白天重置），临产到达分娩点后分娩
	public static void onSecondTick(ServerLevel level, String city)
	{
		boolean night = level.getDayTime() % 24000 >= 12000;

		for (Entity npc : loadedNpcs(level, city))
		{
			// 进度
			float mating = npc.getMatingStage();
			if (mating >= 0.0F)
			{
				if (!night || !bothAtHome(level, npc))
				{
					// 天亮或一方离家：中断
					npc.setMatingStage(-1.0F);
				}
				else
				{
					npc.setMatingStage(mating + MATING_STEP);
					if (npc.getMatingStage() >= 1.0F)
					{
						npc.setMatingStage(-1.0F);
						tryPregnant(level, city, npc);
					}
				}
			}

			// 临产分娩
			if (npc.getPregnancyStage() >= 1.0F)
			{
				tryGiveBirth(level, city, npc);
			}
		}
	}

	// 完成：掷骰怀孕
	private static void tryPregnant(ServerLevel level, String city, Entity mother)
	{
		if (RANDOM.nextDouble() >= pregnancyChance()) return;

		mother.setPregnancyStage(pregnancyStartStage());
		mother.syncToJson();

		// 双方心形粒子
		Entity father = findLoaded(level, mother.getPartner());
		if (father != null)
		{
			level.sendParticles(ParticleTypes.HEART, father.getX(), father.getY() + 2.0D, father.getZ(),
					4, 0.3D, 0.3D, 0.3D, 0.05D);
		}
		level.sendParticles(ParticleTypes.HEART, mother.getX(), mother.getY() + 2.0D, mother.getZ(),
				4, 0.3D, 0.3D, 0.3D, 0.05D);

		LifeSystem.announce(level, city,
				LifeSystem.tpl(Config.ANNOUNCE_PREGNANCY, mother.getPartner(), mother.getNpcName()));
		LOGGER.info("NeoSim-Reproduction: {} is pregnant", mother.getNpcName());
	}

	// 临产：走向诊所（无诊所则居家），到达后分娩
	private static void tryGiveBirth(ServerLevel level, String city, Entity mother)
	{
		String key = mother.getNpcName();
		BlockPos target = BIRTH_TARGETS.get(key);
		if (target == null)
		{
			target = findClinicBirthPos(level, city);
			if (target == null)
			{
				// 无诊所：居家分娩
				BlockPos home = mother.getHomePos();
				target = home != null ? home : mother.blockPosition();
			}
			BIRTH_TARGETS.put(key, target);
		}

		mother.setMoveTarget(target);
		if (reached(mother, target))
		{
			BIRTH_TARGETS.remove(key);
			giveBirth(level, city, mother, target);
		}
	}

	// 清晨：临产者出发去诊所
	private static void goToClinic(ServerLevel level, String city, Entity mother)
	{
		BlockPos clinic = findClinicBirthPos(level, city);
		if (clinic == null)
		{
			// 无诊所：目标记为居家分娩
			BlockPos home = mother.getHomePos();
			BIRTH_TARGETS.put(mother.getNpcName(), home != null ? home : mother.blockPosition());
			return;
		}
		BIRTH_TARGETS.put(mother.getNpcName(), clinic);
		mother.setMoveTarget(clinic);
	}

	// 诊所关键词：中英文Clinic/Hospital
	private static final String[] CLINIC_KEYWORDS = {"clinic", "hospital", "诊所", "医院"};

	// 找城市里的诊所，返回分娩点
	private static BlockPos findClinicBirthPos(ServerLevel level, String city)
	{
		if (level.getServer() == null || city == null || city.isEmpty()) return null;

		for (ControlBoxRecord rec : ControlBoxPersistence.load(level, city))
		{
			String name = rec.schematicName();
			if (name == null || name.isEmpty()) continue;
			if (!matchesClinic(name)) continue;

			if (!rec.livingPoints().isEmpty())
			{
				// 多生活点：优先第一个未被占用的生活点，全被占则退回控制箱
				for (BlockPos lp : rec.livingPoints())
				{
					if (!ControlBoxPersistence.isLivingPointOccupied(rec, lp))
					{
						return lp;
					}
				}
				return rec.boxPos() != null ? rec.boxPos() : rec.originPos();
			}
			return rec.boxPos() != null ? rec.boxPos() : rec.originPos();
		}
		return null;
	}

	private static boolean matchesClinic(String schematicName)
	{
		String lower = schematicName.toLowerCase();
		for (String kw : CLINIC_KEYWORDS)
		{
			if (lower.contains(kw)) return true;
		}
		return false;
	}

	// 分娩：新生儿姓氏/职业继承、族谱、入住母家、人口更新
	private static void giveBirth(ServerLevel level, String city, Entity mother, BlockPos birthPos)
	{
		// 产妇复位
		mother.setPregnancyStage(0.0F);
		mother.setMatingStage(-1.0F);
		mother.clearMoveTarget();
		mother.syncToJson();

		// 父名（父已亡被清空则为未知）
		String fatherName = mother.getPartner();

		// 姓氏继承：父姓优先，父亡或未知则随母姓
		String surname = resolveSurname(level, city, fatherName, mother);

		// 创建新生儿：指定姓氏+随机性别与皮肤
		Entity baby = Entity.NPC.get().create(level);
		if (baby == null) return;

		Entity.generateAndSetName(baby, surname);
		baby.setCityName(city);
		baby.setNpcName(baby.getNpcName());
		baby.setSkin(Entity.randomSkin(baby.getSex()));
		baby.setAge((short) 0);

		// 职业等级继承：floor(父/2)+floor(母/2)，各职业独立，下限1上限10
		int[] jobs = inheritJobs(level, city, mother, fatherName);
		baby.setJobArchitect((byte) jobs[0]);
		baby.setJobFarmer((byte) jobs[1]);
		baby.setJobMiner((byte) jobs[2]);
		baby.setJobCourier((byte) jobs[3]);

		// 出生在分娩点
		baby.moveTo(birthPos.getX() + 0.5D, birthPos.getY() + 1.0D, birthPos.getZ() + 0.5D, 0.0F, 0.0F);
		level.addFreshEntity(baby);

		// 族谱
		Genealogy.onBirth(level, baby, fatherName, mother.getNpcName());

		// 入住母亲家
		moveIntoMotherHome(level, city, baby, mother);

		// 人口更新
		if (level.getServer() != null)
		{
			if (level.getServer().isDedicatedServer())
			{
				NpcData.save(baby, city);
			}
			else
			{
				String saveName = level.getServer().getWorldData().getLevelName();
				NpcData.save(baby, city, saveName);
			}
			short pop = Manage.getPopulation(level, city);
			ModSavedData.get(level).setPopulation(city, (short) (pop + 1), level);
		}

		// 公告+出生音效
		LifeSystem.announce(level, city, LifeSystem.tpl(Config.ANNOUNCE_BIRTH, baby.getNpcName()));
		level.playSound(null, birthPos, SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0F, 1.0F);
		LOGGER.info("NeoSim-Reproduction: {} born to {} & {} at {}",
				baby.getNpcName(), mother.getNpcName(), fatherName, birthPos);
	}

	// 姓氏继承：父姓优先，父不存在则随母姓
	private static String resolveSurname(ServerLevel level, String city, String fatherName, Entity mother)
	{
		if (fatherName != null && !fatherName.isEmpty())
		{
			Entity father = findLoaded(level, fatherName);
			if (father != null) return father.getNpcSurname();

			JsonObject json = NpcData.load(level, city, fatherName);
			if (json != null && json.has("surname"))
			{
				return json.get("surname").getAsString();
			}

			// 父亡故：随母姓
		}
		return mother.getNpcSurname();
	}

	// 职业等级继承
	private static int[] inheritJobs(ServerLevel level, String city, Entity mother, String fatherName)
	{
		int[] m = {mother.getJobArchitect(), mother.getJobFarmer(),
				mother.getJobMiner(), mother.getJobCourier()};
		int[] f = loadJobs(level, city, fatherName);
		int[] result = new int[4];
		for (int i = 0; i < 4; i++)
		{
			result[i] = Math.max(1, Math.min(JOB_MAX_LEVEL, f[i] / 2 + m[i] / 2));
		}
		return result;
	}

	// 父方等级：实体优先，其次档案（双亲全无默认全1）
	private static int[] loadJobs(ServerLevel level, String city, String fatherName)
	{
		int[] jobs = {1, 1, 1, 1};
		if (fatherName == null || fatherName.isEmpty()) return jobs;

		Entity father = findLoaded(level, fatherName);
		if (father != null)
		{
			jobs[0] = father.getJobArchitect();
			jobs[1] = father.getJobFarmer();
			jobs[2] = father.getJobMiner();
			jobs[3] = father.getJobCourier();
			return jobs;
		}

		JsonObject json = NpcData.load(level, city, fatherName);
		if (json != null && json.has("job"))
		{
			JsonObject job = json.getAsJsonObject("job");
			if (job.has("architect")) jobs[0] = job.get("architect").getAsInt();
			if (job.has("farmer")) jobs[1] = job.get("farmer").getAsInt();
			if (job.has("miner")) jobs[2] = job.get("miner").getAsInt();
			if (job.has("courier")) jobs[3] = job.get("courier").getAsInt();
		}
		return jobs;
	}

	// 新生儿入住母亲家
	private static void moveIntoMotherHome(ServerLevel level, String city, Entity baby, Entity mother)
	{
		for (ControlBoxRecord rec : ControlBoxPersistence.load(level, city))
		{
			for (Resident r : rec.residents())
			{
				if (r.name().equals(mother.getNpcName()))
				{
					CityLivingManager.assignToExistingHome(level, city, baby, rec);
					return;
				}
			}
		}
	}

	// 双方夜晚都在家
	private static boolean bothAtHome(ServerLevel level, Entity npc)
	{
		BlockPos home = npc.getHomePos();
		if (home == null || !NpcGoals.GoHomeGoal.hasArrived(npc, home)) return false;

		Entity partner = findLoaded(level, npc.getPartner());
		if (partner == null) return false;
		BlockPos phome = partner.getHomePos();
		return phome != null && NpcGoals.GoHomeGoal.hasArrived(partner, phome);
	}

	// 距目标3格内
	private static boolean reached(Entity npc, BlockPos pos)
	{
		return npc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D) <= BIRTH_REACH_RANGE;
	}

	// 每天孕期推进量
	private static float pregnancyStep()
	{
		int days = 9;
		try
		{
			days = Config.LIFE_PREGNANCY_DAYS.get();
		}
		catch (IllegalStateException ignored)
		{
			// 配置尚未加载，使用默认值
		}
		return 1.0F / days;
	}

	// 怀孕概率
	private static double pregnancyChance()
	{
		try
		{
			return Config.LIFE_PREGNANCY_CHANCE.get();
		}
		catch (IllegalStateException ignored)
		{
			return 1.0D / 7.0D;
		}
	}

	// 女性受孕上限年龄
	private static int pregnancyMaxAge()
	{
		try
		{
			return Config.LIFE_PREGNANCY_MAX_AGE.get();
		}
		catch (IllegalStateException ignored)
		{
			return 45;
		}
	}

	// 受孕初始孕期进度
	private static float pregnancyStartStage()
	{
		try
		{
			return (float) (double) Config.LIFE_PREGNANCY_START_STAGE.get();
		}
		catch (IllegalStateException ignored)
		{
			return 0.1F;
		}
	}

	// C5/C1：城市已加载实体快照（索引取数，快照防迭代中卸载）
	private static List<Entity> loadedNpcs(ServerLevel level, String city)
	{
		return new ArrayList<>(com.wenzai.neosim.npc.NpcRegistry.byCity(city));
	}

	private static Entity findLoaded(ServerLevel level, String name)
	{
		return com.wenzai.neosim.npc.NpcRegistry.findByName(name);
	}
}

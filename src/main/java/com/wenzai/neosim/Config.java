package com.wenzai.neosim;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public class Config
{
	private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

	// 通用
	static
	{
		BUILDER.push("general");
		INITIAL_CREDIT = BUILDER
				.translation("config.neosim.initialCredit")
				.defineInRange("initialCredit", 10.00, 10.00, Double.MAX_VALUE);

		// 建造费用
		CREDITS_PER_BLOCK = BUILDER
				.translation("config.neosim.creditsPerBlock")
				.defineInRange("creditsPerBlock", 0.02, 0.0, 1.0);

		MAX_POPULATION = BUILDER
				.translation("config.neosim.maxPopulation")
				.defineInRange("maxPopulation", 200, 1, 1024);

		// NPC
		NPC_MIN_AGE = BUILDER
				.translation("config.neosim.npcMinAge")
				.defineInRange("npcMinAge", 15, 15, 20);

		NPC_MAX_AGE = BUILDER
				.translation("config.neosim.npcMaxAge")
				.defineInRange("npcMaxAge", 25, 20, 25);
		BUILDER.pop();
	}

	// 生活系统
	static
	{
		BUILDER.push("life");

		// 房租与收入
		BUILDER.push("rent");
		LIFE_RENT_DEFAULT = BUILDER
				.translation("config.neosim.lifeRentDefault")
				.defineInRange("rentDefault", 0.1, 0.0, Double.MAX_VALUE);

		// 按建筑体积定价系数
		LIFE_RENT_PER_BLOCK = BUILDER
				.translation("config.neosim.lifeRentPerBlock")
				.defineInRange("rentPerBlock", 0.01, 0.0, 1.0);
		BUILDER.pop();

		// 衰老与寿终
		BUILDER.push("aging");
		LIFE_ADULT_AGE = BUILDER
				.translation("config.neosim.lifeAdultAge")
				.defineInRange("adultAge", 15, 1, 100);

		LIFE_MAX_AGE = BUILDER
				.translation("config.neosim.lifeMaxAge")
				.defineInRange("maxAge", 110, 2, 1000);

		// 成人每周长岁日
		LIFE_AGING_ADULT_DAY = BUILDER
				.translation("config.neosim.lifeAgingAdultDay")
				.defineInRange("agingAdultDay", 6, 0, 6);

		// 儿童每周长岁日（比成人多长一次）
		LIFE_AGING_CHILD_DAYS = BUILDER
				.translation("config.neosim.lifeAgingChildDays")
				.defineList("agingChildDays", List.of(3, 6),
						obj -> obj instanceof Integer i && i >= 0 && i <= 6);
		BUILDER.pop();

		// 作息
		BUILDER.push("rest");

		// 有家无业市民白天居家休息概率
		LIFE_REST_CHANCE = BUILDER
				.translation("config.neosim.lifeRestChance")
				.defineInRange("restChance", 0.25, 0.0, 1.0);
		BUILDER.pop();

		// 生育与分娩
		BUILDER.push("reproduction");
		LIFE_PREGNANCY_DAYS = BUILDER
				.translation("config.neosim.lifePregnancyDays")
				.defineInRange("pregnancyDays", 9, 1, 30);

		// 生育：怀孕概率（1/7）
		LIFE_PREGNANCY_CHANCE = BUILDER
				.translation("config.neosim.lifePregnancyChance")
				.defineInRange("pregnancyChance", 0.142857, 0.0, 1.0);

		// 女性受孕上限年龄
		LIFE_PREGNANCY_MAX_AGE = BUILDER
				.translation("config.neosim.lifePregnancyMaxAge")
				.defineInRange("pregnancyMaxAge", 45, 1, 120);

		// 受孕初始孕期进度
		LIFE_PREGNANCY_START_STAGE = BUILDER
				.translation("config.neosim.lifePregnancyStartStage")
				.defineInRange("pregnancyStartStage", 0.1, 0.0, 1.0);
		BUILDER.pop();

		// 社交与串门
		BUILDER.push("social");
		// 社交寻找半径
		LIFE_SOCIAL_RANGE = BUILDER
				.translation("config.neosim.lifeSocialRange")
				.defineInRange("socialRange", 40, 1, 128);

		// 社交判定范围
		LIFE_SOCIAL_ARRIVE_DIST = BUILDER
				.translation("config.neosim.lifeSocialArriveDist")
				.defineInRange("socialArriveDist", 2.5, 0.5, 16.0);
		BUILDER.pop();

		// 关系增减
		BUILDER.push("relationship");

		// 关系变差概率
		LIFE_RELATIONSHIP_DOWNGRADE_CHANCE = BUILDER
				.translation("config.neosim.lifeRelationshipDowngradeChance")
				.defineInRange("downgradeChance", 0.2, 0.0, 1.0);

		// 单次关系增减量上限
		LIFE_RELATIONSHIP_CHANGE_MAX = BUILDER
				.translation("config.neosim.lifeRelationshipChangeMax")
				.defineInRange("changeMax", 30, 1, 100);
		BUILDER.pop();

		// 婚姻
		BUILDER.push("marriage");
		LIFE_MARRIAGE_CHANCE = BUILDER
				.translation("config.neosim.lifeMarriageChance")
				.defineInRange("marriageChance", 0.5, 0.0, 1.0);

		// 结婚所需关系度
		LIFE_MARRIAGE_SUBLEVEL = BUILDER
				.translation("config.neosim.lifeMarriageSubLevel")
				.defineInRange("marriageSubLevel", 100, 1, 100);
		BUILDER.pop();

		BUILDER.pop();
	}

	// 农业盒/矿业盒
	static
	{
		BUILDER.push("workplot");

		// 农业每格作业扣款
		WORK_FARM_CREDIT_PER_BLOCK = BUILDER
				.translation("config.neosim.workFarmCreditPerBlock")
				.defineInRange("workFarmCreditPerBlock", 0.01, 0.0, 1.0);

		// 矿业每格扣款
		WORK_MINE_CREDIT_PER_BLOCK = BUILDER
				.translation("config.neosim.workMineCreditPerBlock")
				.defineInRange("workMineCreditPerBlock", 0.01, 0.0, 1.0);

		// 矿业丢弃过滤
		WORK_MINE_DISCARDS = BUILDER
				.translation("config.neosim.workMineDiscards")
				.defineInRange("workMineDiscards", 0, 0, 7);

		// 畜牧：围栏内成年数上限
		WORK_FARM_MAX_ADULTS = BUILDER
				.translation("config.neosim.workFarmMaxAdults")
				.defineInRange("workFarmMaxAdults", 3, 1, 64);

		// 畜牧：围栏内总数量上限
		WORK_FARM_MAX_TOTAL = BUILDER
				.translation("config.neosim.workFarmMaxTotal")
				.defineInRange("workFarmMaxTotal", 20, 1, 128);

		// 畜牧：幼崽长大所需分钟数
		WORK_FARM_BABY_GROW_MINUTES = BUILDER
				.translation("config.neosim.workFarmBabyGrowMinutes")
				.defineInRange("workFarmBabyGrowMinutes", 5, 1, 60);

		// 畜牧：繁殖冷却秒数（0=关闭）
		WORK_FARM_BREED_COOLDOWN_SECONDS = BUILDER
				.translation("config.neosim.workFarmBreedCooldownSeconds")
				.defineInRange("workFarmBreedCooldownSeconds", 60, 0, 600);

		BUILDER.pop();
	}

	// 快递盒
	static
	{
		BUILDER.push("delivery");

		// 每件材料送达扣款（非创造模式）
		DELIVERY_CREDIT_PER_UNIT = BUILDER
				.translation("config.neosim.deliveryCreditPerUnit")
				.defineInRange("deliveryCreditPerUnit", 0.01, 0.0, 1.0);

		// 快递员滚动区块窗口半径（区块数）
		DELIVERY_CHUNK_RADIUS = BUILDER
				.translation("config.neosim.deliveryChunkRadius")
				.defineInRange("deliveryChunkRadius", 2, 0, 8);

		BUILDER.pop();

		BUILDER.push("terraform");
		TERRAFORM_CREDIT_PER_BLOCK = BUILDER
				.comment("Cost deducted per terraformed block (credits)")
				.defineInRange("creditPerBlock", 0.01, 0.0, 100.0);
		TERRAFORM_WATER_DEPTH = BUILDER
				.comment("Max depth to fill water below the plot baseline (blocks)")
				.defineInRange("waterDepth", 12, 1, 64);
		BUILDER.pop();
	}

	// 公告文案（模板化，%s 占位符；§ 颜色码保留在模板内）
	static
	{
		BUILDER.push("announce");

		// 公告模板
		ANNOUNCE_SPAWN = BUILDER
				.translation("config.neosim.announceSpawn")
				.define("spawn", "§f%s §e来到了城市");
		ANNOUNCE_DEATH_TEMPLATE = BUILDER
				.translation("config.neosim.announceDeathTemplate")
				.define("deathTemplate", "§f%s §e%s §f(%s)");
		ANNOUNCE_EVICT_RESIDENT = BUILDER
				.translation("config.neosim.announceEvictResident")
				.define("evictResident", "§f%s §e被赶出了 §f%s");
		ANNOUNCE_EVICT_ALL = BUILDER
				.translation("config.neosim.announceEvictAll")
				.define("evictAll", "§f%s §e已清空，§f%s §e名住户被赶出");
		ANNOUNCE_MOVE_IN = BUILDER
				.translation("config.neosim.announceMoveIn")
				.define("moveIn", "§f%s §e搬进了 §f%s");
		ANNOUNCE_PREGNANCY = BUILDER
				.translation("config.neosim.announcePregnancy")
				.define("pregnancy", "§f好消息！§f%s §e和 §f%s §e要有宝宝了！");
		ANNOUNCE_BIRTH = BUILDER
				.translation("config.neosim.announceBirth")
				.define("birth", "§f%s §e诞生了！");
		ANNOUNCE_MARRIAGE = BUILDER
				.translation("config.neosim.announceMarriage")
				.define("marriage", "§f%s §e与 §f%s §e结为夫妻了");
		ANNOUNCE_COHABIT = BUILDER
				.translation("config.neosim.announceCohabit")
				.define("cohabit", "§f%s §e与 §f%s §e开始同居了");
		ANNOUNCE_BREAKUP = BUILDER
				.translation("config.neosim.announceBreakup")
				.define("breakup", "§f%s §e和 §f%s §e的关系破裂了，结束了同居生活");
		ANNOUNCE_RENT = BUILDER
				.translation("config.neosim.announceRent")
				.define("rent", "§e今天共收取了 §f $%s");
		ANNOUNCE_DELIVERY_DISPATCH = BUILDER
				.translation("config.neosim.announceDeliveryDispatch")
				.define("deliveryDispatch", "§e%s §f正前往 §e%s §f运送 §e%s §f个 §e%s");
		ANNOUNCE_ADULT_LEAVE = BUILDER
				.translation("config.neosim.announceAdultLeave")
				.define("adultLeave", "§f%s §e现在 %s 岁了，他们会开始找房子，你也可以雇佣他们了");
		ANNOUNCE_MISSING_MATERIAL = BUILDER
				.translation("config.neosim.announceMissingMaterial")
				.define("missingMaterial", "§6建造 §f%s §e缺少材料 §b%s");
		ANNOUNCE_BUILDING_COMPLETE = BUILDER
				.translation("config.neosim.announceBuildingComplete")
				.define("buildingComplete", "§f%s §e已完工");

		// 死亡原因
		BUILDER.push("deathCause");
		ANNOUNCE_DEATH_CAUSE_OLD_AGE = BUILDER
				.translation("config.neosim.announceDeathCauseOldAge")
				.define("oldAge", "年纪大了，感觉不太舒服……哦不！");
		ANNOUNCE_DEATH_CAUSE_DROWN = BUILDER
				.translation("config.neosim.announceDeathCauseDrown")
				.define("drown", "淹死了");
		ANNOUNCE_DEATH_CAUSE_LAVA = BUILDER
				.translation("config.neosim.announceDeathCauseLava")
				.define("lava", "掉进了岩浆里");
		ANNOUNCE_DEATH_CAUSE_SUFFOCATE = BUILDER
				.translation("config.neosim.announceDeathCauseSuffocate")
				.define("suffocate", "被卡在墙里窒息了");
		ANNOUNCE_DEATH_CAUSE_FALL = BUILDER
				.translation("config.neosim.announceDeathCauseFall")
				.define("fall", "从高处摔了下来");
		ANNOUNCE_DEATH_CAUSE_STARVE = BUILDER
				.translation("config.neosim.announceDeathCauseStarve")
				.define("starve", "饿死了（建个农场吧…）");
		ANNOUNCE_DEATH_CAUSE_FIRE = BUILDER
				.translation("config.neosim.announceDeathCauseFire")
				.define("fire", "被火烧死了");
		ANNOUNCE_DEATH_CAUSE_LIGHTNING = BUILDER
				.translation("config.neosim.announceDeathCauseLightning")
				.define("lightning", "被雷劈了");
		ANNOUNCE_DEATH_CAUSE_CACTUS = BUILDER
				.translation("config.neosim.announceDeathCauseCactus")
				.define("cactus", "被仙人掌扎了");
		ANNOUNCE_DEATH_CAUSE_OTHER = BUILDER
				.translation("config.neosim.announceDeathCauseOther")
				.define("other", "不幸去世了");
		BUILDER.pop();

		// 死亡备注
		BUILDER.push("deathRemark");
		ANNOUNCE_DEATH_REMARK_OLD = BUILDER
				.translation("config.neosim.announceDeathRemarkOld")
				.define("old", "享年 %s 岁，也算寿终正寝了！");
		ANNOUNCE_DEATH_REMARK_YOUNG = BUILDER
				.translation("config.neosim.announceDeathRemarkYoung")
				.define("young", "年仅 %s 岁");
		BUILDER.pop();

		BUILDER.pop();
	}

	// 通用
	public static final ModConfigSpec.DoubleValue INITIAL_CREDIT;

	// 建造费用
	public static final ModConfigSpec.DoubleValue CREDITS_PER_BLOCK;

	public static final ModConfigSpec.IntValue MAX_POPULATION;

	// NPC
	public static final ModConfigSpec.IntValue NPC_MIN_AGE;

	public static final ModConfigSpec.IntValue NPC_MAX_AGE;

	// 租金
	public static final ModConfigSpec.DoubleValue LIFE_RENT_DEFAULT;

	// 按建筑体积定价系数
	public static final ModConfigSpec.DoubleValue LIFE_RENT_PER_BLOCK;

	// 衰老与寿终
	public static final ModConfigSpec.IntValue LIFE_ADULT_AGE;

	public static final ModConfigSpec.IntValue LIFE_MAX_AGE;

	// 成人每周长岁日
	public static final ModConfigSpec.IntValue LIFE_AGING_ADULT_DAY;

	// 儿童每周长岁日（比成人多长一次）
	public static final ModConfigSpec.ConfigValue<List<? extends Integer>> LIFE_AGING_CHILD_DAYS;

	// 有家无业市民白天居家休息概率
	public static final ModConfigSpec.DoubleValue LIFE_REST_CHANCE;

	// 生育：孕期天数
	public static final ModConfigSpec.IntValue LIFE_PREGNANCY_DAYS;

	// 生育：怀孕概率
	public static final ModConfigSpec.DoubleValue LIFE_PREGNANCY_CHANCE;

	// 女性受孕上限年龄
	public static final ModConfigSpec.IntValue LIFE_PREGNANCY_MAX_AGE;

	// 受孕初始孕期进度
	public static final ModConfigSpec.DoubleValue LIFE_PREGNANCY_START_STAGE;

	// 社交：寻找对象半径
	public static final ModConfigSpec.IntValue LIFE_SOCIAL_RANGE;

	// 社交：到达判定距离
	public static final ModConfigSpec.DoubleValue LIFE_SOCIAL_ARRIVE_DIST;

	// 关系：变差概率
	public static final ModConfigSpec.DoubleValue LIFE_RELATIONSHIP_DOWNGRADE_CHANCE;

	// 关系：单次增减量上限
	public static final ModConfigSpec.IntValue LIFE_RELATIONSHIP_CHANGE_MAX;

	// 婚姻：结婚所需关系度
	public static final ModConfigSpec.IntValue LIFE_MARRIAGE_SUBLEVEL;

	// 婚姻：结婚的概率
	public static final ModConfigSpec.DoubleValue LIFE_MARRIAGE_CHANCE;

	// 工作盒：农业每格扣款
	public static final ModConfigSpec.DoubleValue WORK_FARM_CREDIT_PER_BLOCK;

	// 工作盒：矿业每格扣款
	public static final ModConfigSpec.DoubleValue WORK_MINE_CREDIT_PER_BLOCK;

	// 工作盒：矿业丢弃过滤
	public static final ModConfigSpec.IntValue WORK_MINE_DISCARDS;

	// 工作盒：畜牧成年上限
	public static final ModConfigSpec.IntValue WORK_FARM_MAX_ADULTS;

	// 工作盒：畜牧总量上限
	public static final ModConfigSpec.IntValue WORK_FARM_MAX_TOTAL;

	// 工作盒：畜牧幼崽长大分钟数
	public static final ModConfigSpec.IntValue WORK_FARM_BABY_GROW_MINUTES;

	// 工作盒：畜牧繁殖冷却秒数
	public static final ModConfigSpec.IntValue WORK_FARM_BREED_COOLDOWN_SECONDS;

	// 快递盒：每件材料送达扣款
	public static final ModConfigSpec.DoubleValue DELIVERY_CREDIT_PER_UNIT;

	// 快递盒：快递员滚动区块窗口半径（区块数）
	public static final ModConfigSpec.IntValue DELIVERY_CHUNK_RADIUS;

	// 整地：每块费用
	public static final ModConfigSpec.DoubleValue TERRAFORM_CREDIT_PER_BLOCK;

	// 整地：填水最大深度
	public static final ModConfigSpec.IntValue TERRAFORM_WATER_DEPTH;

	// 公告：模板
	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_SPAWN;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_TEMPLATE;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_EVICT_RESIDENT;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_EVICT_ALL;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_MOVE_IN;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_PREGNANCY;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_BIRTH;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_MARRIAGE;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_COHABIT;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_BREAKUP;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_RENT;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DELIVERY_DISPATCH;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_ADULT_LEAVE;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_MISSING_MATERIAL;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_BUILDING_COMPLETE;

	// 公告：死因
	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_CAUSE_OLD_AGE;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_CAUSE_DROWN;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_CAUSE_LAVA;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_CAUSE_SUFFOCATE;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_CAUSE_FALL;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_CAUSE_STARVE;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_CAUSE_FIRE;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_CAUSE_LIGHTNING;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_CAUSE_CACTUS;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_CAUSE_OTHER;

	// 公告：死亡备注
	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_REMARK_OLD;

	public static final ModConfigSpec.ConfigValue<String> ANNOUNCE_DEATH_REMARK_YOUNG;

	static final ModConfigSpec SPEC = BUILDER.build();

	// 运行时校验配置值，确保所有值在合法范围内（调用在服务端启动/客户端加入世界后）
	public static void validate()
	{
		// 信用点非负检查
		if (INITIAL_CREDIT.get() < 0.0)
		{
			NeoSim.LOGGER.warn("Config 'initialCredit' out of range ({}), clamped to 0.0", INITIAL_CREDIT.get());
			INITIAL_CREDIT.set(0.0);
		}

		// 人口
		int pop = MAX_POPULATION.get();
		if (pop < 1 || pop > 10000)
		{
			int clamped = Math.max(1, Math.min(10000, pop));
			NeoSim.LOGGER.warn("Config 'maxPopulation' out of range ({}), clamped to {}", pop, clamped);
			MAX_POPULATION.set(clamped);
		}

		// NPC最小年龄
		int minAge = NPC_MIN_AGE.get();
		if (minAge < 0 || minAge > 100)
		{
			int clamped = Math.max(0, Math.min(100, minAge));
			NeoSim.LOGGER.warn("Config 'npcMinAge' out of range ({}), clamped to {}", minAge, clamped);
			NPC_MIN_AGE.set(clamped);
		}

		// NPC最大年龄
		int maxAge = NPC_MAX_AGE.get();
		if (maxAge < 1 || maxAge > 100)
		{
			int clamped = Math.max(1, Math.min(100, maxAge));
			NeoSim.LOGGER.warn("Config 'npcMaxAge' out of range ({}), clamped to {}", maxAge, clamped);
			NPC_MAX_AGE.set(clamped);
		}

		// 成年年龄
		int adultAge = LIFE_ADULT_AGE.get();
		if (adultAge < 1 || adultAge > 100)
		{
			int clamped = Math.max(1, Math.min(100, adultAge));
			NeoSim.LOGGER.warn("Config 'lifeAdultAge' out of range ({}), clamped to {}", adultAge, clamped);
			LIFE_ADULT_AGE.set(clamped);
		}

		// 寿终年龄必须大于成年年龄
		int lifeMaxAge = LIFE_MAX_AGE.get();
		if (lifeMaxAge < 2 || lifeMaxAge > 1000)
		{
			int clamped = Math.max(2, Math.min(1000, lifeMaxAge));
			NeoSim.LOGGER.warn("Config 'lifeMaxAge' out of range ({}), clamped to {}", lifeMaxAge, clamped);
			LIFE_MAX_AGE.set(clamped);
		}
		if (LIFE_MAX_AGE.get() <= LIFE_ADULT_AGE.get())
		{
			NeoSim.LOGGER.warn("Config 'lifeMaxAge' ({}) <= 'lifeAdultAge' ({}), set to adultAge+1",
					LIFE_MAX_AGE.get(), LIFE_ADULT_AGE.get());
			LIFE_MAX_AGE.set(LIFE_ADULT_AGE.get() + 1);
		}

		// 受孕初始进度0~1
		double startStage = LIFE_PREGNANCY_START_STAGE.get();
		if (startStage < 0.0 || startStage > 1.0)
		{
			double clamped = Math.max(0.0, Math.min(1.0, startStage));
			NeoSim.LOGGER.warn("Config 'lifePregnancyStartStage' out of range ({}), clamped to {}", startStage, clamped);
			LIFE_PREGNANCY_START_STAGE.set(clamped);
		}

		// 受孕上限年龄必须大于成年年龄
		int pregMaxAge = LIFE_PREGNANCY_MAX_AGE.get();
		if (pregMaxAge < 1 || pregMaxAge > 120)
		{
			int clamped = Math.max(1, Math.min(120, pregMaxAge));
			NeoSim.LOGGER.warn("Config 'lifePregnancyMaxAge' out of range ({}), clamped to {}", pregMaxAge, clamped);
			LIFE_PREGNANCY_MAX_AGE.set(clamped);
		}
		if (LIFE_PREGNANCY_MAX_AGE.get() <= LIFE_ADULT_AGE.get())
		{
			NeoSim.LOGGER.warn("Config 'lifePregnancyMaxAge' ({}) <= 'lifeAdultAge' ({}), set to adultAge+1",
					LIFE_PREGNANCY_MAX_AGE.get(), LIFE_ADULT_AGE.get());
			LIFE_PREGNANCY_MAX_AGE.set(LIFE_ADULT_AGE.get() + 1);
		}

		// 婚姻：结婚所需关系度1~100
		int subLevel = LIFE_MARRIAGE_SUBLEVEL.get();
		if (subLevel < 1 || subLevel > 100)
		{
			int clamped = Math.max(1, Math.min(100, subLevel));
			NeoSim.LOGGER.warn("Config 'lifeMarriageSubLevel' out of range ({}), clamped to {}", subLevel, clamped);
			LIFE_MARRIAGE_SUBLEVEL.set(clamped);
		}
	}
}

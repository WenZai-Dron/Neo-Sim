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

        // 族谱血亲回溯深度
        BUILDER.push("genealogy");
        LIFE_GENEALOGY_DEPTH = BUILDER
                .translation("config.neosim.lifeGenealogyDepth")
                .defineInRange("genealogyDepth", 3, 1, 10);
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

    // 族谱血亲回溯深度
    public static final ModConfigSpec.IntValue LIFE_GENEALOGY_DEPTH;

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

        // 族谱回溯深度
        int genDepth = LIFE_GENEALOGY_DEPTH.get();
        if (genDepth < 1 || genDepth > 10)
        {
            int clamped = Math.max(1, Math.min(10, genDepth));
            NeoSim.LOGGER.warn("Config 'lifeGenealogyDepth' out of range ({}), clamped to {}", genDepth, clamped);
            LIFE_GENEALOGY_DEPTH.set(clamped);
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

package com.wenzai.neosim;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public class Config
{
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // 通用
    public static final ModConfigSpec.DoubleValue INITIAL_CREDIT = BUILDER
            .translation("config.neosim.initialCredit")
            .defineInRange("initialCredit", 10.00, 10.00, Double.MAX_VALUE);

    // 建造费用
    public static final ModConfigSpec.DoubleValue CREDITS_PER_BLOCK = BUILDER
            .translation("config.neosim.creditsPerBlock")
            .defineInRange("creditsPerBlock", 0.02, 0.0, 1.0);

    public static final ModConfigSpec.IntValue MAX_POPULATION = BUILDER
            .translation("config.neosim.maxPopulation")
            .defineInRange("maxPopulation", 200, 1, 1024);

    // NPC
    public static final ModConfigSpec.IntValue NPC_MIN_AGE = BUILDER
            .translation("config.neosim.npcMinAge")
            .defineInRange("npcMinAge", 15, 15, 20);

    public static final ModConfigSpec.IntValue NPC_MAX_AGE = BUILDER
            .translation("config.neosim.npcMaxAge")
            .defineInRange("npcMaxAge", 25, 20, 25);

    // 租金
    public static final ModConfigSpec.DoubleValue LIFE_RENT_DEFAULT = BUILDER
            .translation("config.neosim.lifeRentDefault")
            .defineInRange("lifeRentDefault", 0.1, 0.0, Double.MAX_VALUE);

    // 按建筑体积定价系数
    public static final ModConfigSpec.DoubleValue LIFE_RENT_PER_BLOCK = BUILDER
            .translation("config.neosim.lifeRentPerBlock")
            .defineInRange("lifeRentPerBlock", 0.01, 0.0, 1.0);

    // 衰老与寿终
    public static final ModConfigSpec.IntValue LIFE_ADULT_AGE = BUILDER
            .translation("config.neosim.lifeAdultAge")
            .defineInRange("lifeAdultAge", 15, 1, 100);

    public static final ModConfigSpec.IntValue LIFE_MAX_AGE = BUILDER
            .translation("config.neosim.lifeMaxAge")
            .defineInRange("lifeMaxAge", 110, 2, 1000);

    // 成人每周长岁日
    public static final ModConfigSpec.IntValue LIFE_AGING_ADULT_DAY = BUILDER
            .translation("config.neosim.lifeAgingAdultDay")
            .defineInRange("lifeAgingAdultDay", 6, 0, 6);

    // 儿童每周长岁日（比成人多长一次）
    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> LIFE_AGING_CHILD_DAYS = BUILDER
            .translation("config.neosim.lifeAgingChildDays")
            .defineList("lifeAgingChildDays", List.of(3, 6),
                    obj -> obj instanceof Integer i && i >= 0 && i <= 6);

    // 族谱血亲回溯深度
    public static final ModConfigSpec.IntValue LIFE_GENEALOGY_DEPTH = BUILDER
            .translation("config.neosim.lifeGenealogyDepth")
            .defineInRange("lifeGenealogyDepth", 3, 1, 10);

    // 婚姻：结婚的概率
    public static final ModConfigSpec.DoubleValue LIFE_MARRIAGE_CHANCE = BUILDER
            .translation("config.neosim.lifeMarriageChance")
            .defineInRange("lifeMarriageChance", 0.5, 0.0, 1.0);

    // 生育：孕期天数
    public static final ModConfigSpec.IntValue LIFE_PREGNANCY_DAYS = BUILDER
            .translation("config.neosim.lifePregnancyDays")
            .defineInRange("lifePregnancyDays", 9, 1, 30);

    // 生育：怀孕概率（1/7）
    public static final ModConfigSpec.DoubleValue LIFE_PREGNANCY_CHANCE = BUILDER
            .translation("config.neosim.lifePregnancyChance")
            .defineInRange("lifePregnancyChance", 0.142857, 0.0, 1.0);

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
    }
}

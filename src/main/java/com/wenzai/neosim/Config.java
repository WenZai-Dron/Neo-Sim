package com.wenzai.neosim;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config
{
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // 通用
    public static final ModConfigSpec.DoubleValue INITIAL_CREDIT = BUILDER
            .translation("config.neosim.initialCredit")
            .defineInRange("initialCredit", 10.00, 10.00, Double.MAX_VALUE);

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

    static final ModConfigSpec SPEC = BUILDER.build();

    // 运行时校验配置值，确保所有值在合法范围内
    // 调用：服务端启动/客户端加入世界后
    public static void validate()
    {
        // 信用点非负检查
        if (INITIAL_CREDIT.get() < 0.0)
        {
            NeoSim.LOGGER.warn("Config 'initialCredit' out of range ({}), clamped to 0.0", INITIAL_CREDIT.get());
            INITIAL_CREDIT.set(0.0);
        }

        // 人口：[1, 10000]
        int pop = MAX_POPULATION.get();
        if (pop < 1 || pop > 10000)
        {
            int clamped = Math.max(1, Math.min(10000, pop));
            NeoSim.LOGGER.warn("Config 'maxPopulation' out of range ({}), clamped to {}", pop, clamped);
            MAX_POPULATION.set(clamped);
        }

        // NPC最小年龄：[0, 100]
        int minAge = NPC_MIN_AGE.get();
        if (minAge < 0 || minAge > 100)
        {
            int clamped = Math.max(0, Math.min(100, minAge));
            NeoSim.LOGGER.warn("Config 'npcMinAge' out of range ({}), clamped to {}", minAge, clamped);
            NPC_MIN_AGE.set(clamped);
        }

        // NPC最大年龄：[1, 100]
        int maxAge = NPC_MAX_AGE.get();
        if (maxAge < 1 || maxAge > 100)
        {
            int clamped = Math.max(1, Math.min(100, maxAge));
            NeoSim.LOGGER.warn("Config 'npcMaxAge' out of range ({}), clamped to {}", maxAge, clamped);
            NPC_MAX_AGE.set(clamped);
        }

        // 交叉校验：minAge不能大于maxAge
        if (NPC_MIN_AGE.get() > NPC_MAX_AGE.get())
        {
            NeoSim.LOGGER.warn("Config 'npcMinAge' ({}) > 'npcMaxAge' ({}), swapping",
                NPC_MIN_AGE.get(), NPC_MAX_AGE.get());
            int tmp = NPC_MIN_AGE.get();
            NPC_MIN_AGE.set(NPC_MAX_AGE.get());
            NPC_MAX_AGE.set(tmp);
        }
    }
}

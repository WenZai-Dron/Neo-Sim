// 每日结算调度：所有生活机制统一从这里触发

package com.wenzai.neosim.life;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.building.ControlBoxPersistence;
import com.wenzai.neosim.building.ControlBoxPersistence.ControlBoxRecord;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.schematic.BuildingType;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SchematicRegistry;
import com.wenzai.neosim.storage.FileCreater;
import com.wenzai.neosim.storage.ModSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Random;

public class LifeSystem
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Random RANDOM = new Random();

    // 分钟计时
    private static final int MINUTE_TICKS = 1200;
    private static int minuteTimer = 0;

    // 秒计时
    private static final int SECOND_TICKS = 20;
    private static int secondTimer = 0;

    private LifeSystem() {}

    // 每天早晨一次
    public static void onDayStart(ServerLevel level, int dayOfWeek)
    {
        LOGGER.info("NeoSim-LifeSystem: day start, dayOfWeek={}", dayOfWeek);

        // 衰老与寿终
        AgingSystem.onDayStart(level, dayOfWeek);

        // 房租与收入
        collectRent(level);

        // 生育：孕期推进，临产者出发去Clinic/Hospital
        ReproductionSystem.onDayStart(level);

        // 白天1/4概率在家休息
        rollRestToday(level);
    }

    // 每tick调用
    public static void onServerTick(ServerLevel level)
    {
        // 秒计时：进度推进与分娩
        secondTimer++;
        if (secondTimer >= SECOND_TICKS)
        {
            secondTimer = 0;
            String city = ModSavedData.getActiveCityName();
            if (!city.isEmpty())
            {
                ReproductionSystem.onSecondTick(level, city);
            }
        }

        // 分钟计时
        minuteTimer++;
        if (minuteTimer < MINUTE_TICKS) return;
        minuteTimer = 0;

        String city = ModSavedData.getActiveCityName();
        if (city.isEmpty()) return;

        // 玩家附近的未加载NPC从文件恢复，远离所有玩家的卸载
        Manage.respawnNearPlayers(level, city);
        Manage.despawnFarFromPlayers(level, city);

        // 自动入城补人（含从文件恢复的判定）
        Manage.replenishPopulation(level, city);

        // 婚姻与同居
        MarriageSystem.onServerTick(level, city);

        // 生育：夜晚发起
        ReproductionSystem.onMinuteNight(level, city);
    }

    // 无目标且有家的NPC清晨1/4概率在家休息
    private static void rollRestToday(ServerLevel level)
    {
        for (net.minecraft.world.entity.Entity e : level.getAllEntities())
        {
            if (!(e instanceof Entity npc)) continue;
            if (npc.getHomePos() != null && !npc.hasJob())
            {
                npc.setRestToday(RANDOM.nextDouble() < 0.25);
            }
            else
            {
                npc.setRestToday(false);
            }
        }
    }

    // 公告给该城市在线玩家
    public static void announce(ServerLevel level, String cityName, String msg)
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

    // 每日收租
    private static void collectRent(ServerLevel level)
    {
        String city = ModSavedData.getActiveCityName();
        if (city.isEmpty()) return;

        ModSavedData data = ModSavedData.get(level);

        // 创造模式不收租
        if (data.getMode() == 2) return;

        double total = 0;
        int households = 0;
        for (ControlBoxRecord rec : ControlBoxPersistence.load(level, city))
        {
            if (rec.residents().isEmpty()) continue;
            if (!isResidential(rec)) continue;

            // 无租金
            total += rec.rent() > 0 ? rec.rent() : Config.LIFE_RENT_DEFAULT.get();
            households++;
        }
        if (households <= 0) return;

        data.setCredit(data.getCredit() + total, level);

        String msg = "§e今天共收取了 §f $" + formatAmount(total);
        announce(level, city, msg);
        LOGGER.info("NeoSim-RentSystem: collected {} credits from {} households in '{}'",
                formatAmount(total), households, city);
    }

    // 建筑类型判定
    private static boolean isResidential(ControlBoxRecord rec)
    {
        SchematicData schematic = SchematicRegistry.getInstance().get(rec.schematicName());
        if (schematic != null)
        {
            return schematic.getType() == BuildingType.RESIDENTIAL;
        }
        return !rec.livingPoints().isEmpty();
    }

    // 金额显示：小数保留两位
    private static String formatAmount(double amount)
    {
        return amount == Math.floor(amount)
                ? String.valueOf((long) amount)
                : String.valueOf(Math.round(amount * 100.0) / 100.0);
    }
}

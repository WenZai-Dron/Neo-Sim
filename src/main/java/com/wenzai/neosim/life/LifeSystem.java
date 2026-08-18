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
import com.wenzai.neosim.storage.CityManager;
import com.wenzai.neosim.storage.FileCreater;
import com.wenzai.neosim.storage.ModSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

import java.util.IllegalFormatException;
import java.util.Random;

public class LifeSystem
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Random RANDOM = new Random();

	// 分钟计时
	private static final int MINUTE_TICKS = 1200;
	private static int minuteTimer = 0;

	// C4：分钟分片相位（婚姻/生育交替分钟执行）
	private static int minutePhase = 0;

	// 秒计时
	private static final int SECOND_TICKS = 20;
	private static int secondTimer = 0;

	private LifeSystem() {}

	// 每天早晨一次：逐城结算（城市只在有玩家在线时演化）
	public static void onDayStart(ServerLevel level, int dayOfWeek)
	{
		LOGGER.info("NeoSim-LifeSystem: day start, dayOfWeek={}", dayOfWeek);

		for (String city : CityManager.onlineCities(level))
		{
			try
			{
				// 衰老与寿终（含未加载档案结算）
				AgingSystem.onDayStart(level, dayOfWeek, city);

				// 房租与收入
				collectRent(level, city);

				// 生育：孕期推进，临产者出发去Clinic/Hospital
				ReproductionSystem.onDayStart(level, city);

				// L9：每日清理低等级过期关系文件（防关系对 O(P²) 无上限）
				RelationshipPersistence.cleanupStale(level, city);
			}
			catch (Exception e)
			{
				LOGGER.error("NeoSim-LifeSystem: city '{}' day start failed", city, e);
			}
		}

		// 白天1/4概率在家休息（实体级，无需城市参数）
		rollRestToday(level);
	}

	// 每tick调用：遍历"在线玩家所属城市"，逐城演化
	public static void onServerTick(ServerLevel level)
	{
		// 秒计时：进度推进与分娩
		secondTimer++;
		if (secondTimer >= SECOND_TICKS)
		{
			secondTimer = 0;
			for (String city : CityManager.onlineCities(level))
			{
				try
				{
					ReproductionSystem.onSecondTick(level, city);
				}
				catch (Exception e)
				{
					LOGGER.error("NeoSim-LifeSystem: city '{}' second tick failed", city, e);
				}
			}
		}

		// 分钟计时
		minuteTimer++;
		if (minuteTimer < MINUTE_TICKS) return;
		minuteTimer = 0;
		minutePhase++;

		for (String city : CityManager.onlineCities(level))
		{
			try
			{
				// 玩家附近的未加载NPC从文件恢复，远离所有玩家的卸载
				Manage.respawnNearPlayers(level, city);
				Manage.despawnFarFromPlayers(level, city);

				// 自动入城补人（含从文件恢复的判定）
				Manage.replenishPopulation(level, city);
			}
			catch (Exception e)
			{
				LOGGER.error("NeoSim-LifeSystem: city '{}' minute tick failed", city, e);
			}
		}

		// C4：婚姻/生育等低实时性重活分片到不同分钟，避免每分钟全部挤在同一 tick
		if (minutePhase % 2 == 1)
		{
			for (String city : CityManager.onlineCities(level))
			{
				try
				{
					MarriageSystem.onServerTick(level, city);
				}
				catch (Exception e)
				{
					LOGGER.error("NeoSim-LifeSystem: city '{}' marriage tick failed", city, e);
				}
			}
		}
		else
		{
			for (String city : CityManager.onlineCities(level))
			{
				try
				{
					// 生育：夜晚发起
					ReproductionSystem.onMinuteNight(level, city);
				}
				catch (Exception e)
				{
					LOGGER.error("NeoSim-LifeSystem: city '{}' reproduction tick failed", city, e);
				}
			}
		}
	}

	// 有家无业市民清晨按配置概率在家休息（C1：索引遍历全部已加载NPC）
	private static void rollRestToday(ServerLevel level)
	{
		double restChance = restChance();
		for (Entity npc : com.wenzai.neosim.npc.NpcRegistry.allLoaded())
		{
			if (npc.getHomePos() != null && !npc.hasJob())
			{
				npc.setRestToday(RANDOM.nextDouble() < restChance);
			}
			else
			{
				npc.setRestToday(false);
			}
		}
	}

	// 有家无业市民白天居家休息概率
	private static double restChance()
	{
		try
		{
			return Config.LIFE_REST_CHANCE.get();
		}
		catch (IllegalStateException ignored)
		{
			// 配置尚未加载，使用默认值
			return 0.25;
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

	// 按配置模板格式化公告文案（%s 占位）；模板非法时回退原样并警告
	public static String tpl(ModConfigSpec.ConfigValue<String> template, Object... args)
	{
		String raw = template.get();
		try
		{
			return String.format(raw, args);
		}
		catch (IllegalFormatException e)
		{
			LOGGER.warn("NeoSim-Announce: bad template '{}' — {}", raw, e.getMessage());
			return raw;
		}
	}

	// 每日收租（按城市；模式为全服全局，创造模式免租）
	private static void collectRent(ServerLevel level, String city)
	{
		if (city.isEmpty()) return;

		ModSavedData data = ModSavedData.get(level);

		// 全局模式=创造(2)不收租（立项基线：模式不按城市隔离）
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

		data.setCredit(city, data.getData(city).credit() + total, level);

		String msg = tpl(Config.ANNOUNCE_RENT, formatAmount(total));
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

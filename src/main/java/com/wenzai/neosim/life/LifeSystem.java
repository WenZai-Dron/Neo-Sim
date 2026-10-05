package com.wenzai.neosim.life;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.block.ControlBoxPersistence;
import com.wenzai.neosim.block.ControlBoxPersistence.ControlBoxRecord;
import com.wenzai.neosim.npc.CityLivingManager;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.npc.NpcRegistry;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SchematicRegistry;
import com.wenzai.neosim.storage.CityManager;
import com.wenzai.neosim.storage.FileCreater;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Random;
import java.util.Set;

// 生活系统：逐城推进的每日/每分钟结算入口（衰老、房租、生育、关系、休息），公告与模板也在这里
public class LifeSystem
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Random RANDOM = new Random();

	// 分钟计时
	private static final int MINUTE_TICKS = 1200;
	private static int minuteTimer = 0;

	// 分钟分片相位（婚姻/生育交替分钟执行）
	private static int minutePhase = 0;

	// 秒计时
	private static final int SECOND_TICKS = 20;
	private static int secondTimer = 0;

	// 高龄寿终：超过寿终年龄后每天 1/10 概率自然死亡
	private static final double OLD_AGE_DEATH_CHANCE = 0.1;

	private LifeSystem()
	{
	}

	// 每天早晨一次：逐城结算（城市只在有玩家在线时演化）
	public static void onDayStart(ServerLevel level, int dayOfWeek)
	{
		LOGGER.info("NeoSim-LifeSystem: day start, dayOfWeek={}", dayOfWeek);

		for (String city : CityManager.onlineCities(level))
		{
			try
			{
				// 衰老与寿终（含未加载档案结算）
				agingOnDayStart(level, dayOfWeek, city);

				// 房租与收入
				collectRent(level, city);

				// 生育：孕期推进，临产者出发去Clinic/Hospital
				ReproductionSystem.onDayStart(level, city);

				// 每日清理低等级过期关系文件（防关系对 O(P²) 无上限）
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

		// 婚姻/生育等低实时性重活分片到不同分钟，避免每分钟全部挤在同一 tick
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

	// 衰老与寿终（每日第一步）：先长岁，再处理成年离家与高龄寿终
	// city 仅用于未加载档案结算；加载中实体按实体改，未加载档案走 patch*
	private static void agingOnDayStart(ServerLevel level, int dayOfWeek, String city)
	{
		int adultAge = Config.LIFE_ADULT_AGE.get();
		int maxAge = Config.LIFE_MAX_AGE.get();
		boolean adultAgingDay = dayOfWeek == Config.LIFE_AGING_ADULT_DAY.get();
		boolean childAgingDay = Config.LIFE_AGING_CHILD_DAYS.get().contains(dayOfWeek);

		// 只扫本城已加载 NPC（快照，避免逐城重复全量扫描 + 同一 NPC 被多城重复结算）
		List<Entity> loaded = new ArrayList<>(NpcRegistry.byCity(city));
		for (Entity npc : loaded)
		{
			if (npc.getNpcName().isEmpty() || npc.getCityName().isEmpty()) continue;

			// 每周长岁
			boolean child = npc.getAge() < adultAge;
			boolean aged = false;
			if ((child && childAgingDay) || (!child && adultAgingDay))
			{
				npc.setAge((short) (npc.getAge() + 1));
				npc.syncToJson();
				aged = true;
			}

			// 成年离家
			if (aged && child && npc.getAge() == adultAge)
			{
				CityLivingManager.releaseHome(level, npc);
				announce(level, npc.getCityName(), tpl(Config.ANNOUNCE_ADULT_LEAVE, npc.getNpcName(), adultAge));
			}

			// 高龄寿终
			if (npc.getAge() > maxAge && RANDOM.nextDouble() < OLD_AGE_DEATH_CHANCE)
			{
				LOGGER.info("NeoSim-LifeSystem.aging: '{}' died of old age at {}", npc.getNpcName(), npc.getAge());
				npc.die(level.damageSources().genericKill());
			}
		}

		// 未加载NPC：数据侧同样结算
		if (city.isEmpty()) return;

		Set<String> loadedNames = new HashSet<>();
		for (Entity npc : NpcRegistry.byCity(city))
		{
			loadedNames.add(npc.getNpcName());
		}

		for (String name : NpcData.listNpcNames(level, city))
		{
			if (loadedNames.contains(name)) continue;
			JsonObject json = NpcData.load(level, city, name);
			if (json == null || !json.has("age")) continue;

			int age = json.get("age").getAsShort();
			boolean child = age < adultAge;
			boolean aged = false;
			if ((child && childAgingDay) || (!child && adultAgingDay))
			{
				age++;
				aged = true;
			}

			// 成年离家：清生活点+城市记录移除+公告
			if (aged && child && age == adultAge)
			{
				NpcData.patchClearHome(level, city, name);
				CityLivingManager.releaseHomeByName(level, city, name);
				announce(level, city, tpl(Config.ANNOUNCE_ADULT_LEAVE, name, adultAge));
			}

			// 高龄寿终：删档、退房、族谱、人口同步
			if (age > maxAge && RANDOM.nextDouble() < OLD_AGE_DEATH_CHANCE)
			{
				LOGGER.info("NeoSim-LifeSystem.aging: '{}' died of old age while unloaded at {}", name, age);
				Manage.dieUnloaded(level, city, name);
				continue;
			}

			if (aged)
			{
				NpcData.patchAge(level, city, name, (short) age);
			}
		}
	}

	// 有家市民清晨按配置概率在家休息（含在岗工人：在岗也可能抽到休息日）
	// 索引遍历全部已加载NPC；无家者不休息
	private static void rollRestToday(ServerLevel level)
	{
		double restChance = restChance();
		for (Entity npc : NpcRegistry.allLoaded())
		{
			npc.setRestToday(npc.getHomePos() != null && RANDOM.nextDouble() < restChance);
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

	// 公告给该城市在线玩家（组件在客户端按各自语言解析）
	public static void announce(ServerLevel level, String cityName, Component msg)
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
				player.displayClientMessage(msg, false);
			}
		}
	}

	// 按配置模板生成公告组件（%s 占位）：
	// 模板保持默认值 → 翻译组件（每个客户端用自己的语言渲染，参数可为组件）
	// 模板被玩家改过   → 原样格式化文本，尊重玩家自定义
	public static Component tpl(ModConfigSpec.ConfigValue<String> template, Object... args)
	{
		String raw = template.get();
		String key = Config.announceLangKey(template);
		if (key != null && raw != null && raw.equals(template.getDefault()))
		{
			return Component.translatable(key, args);
		}

		Object[] plain = new Object[args.length];
		for (int i = 0; i < args.length; i++)
		{
			plain[i] = args[i] instanceof Component c ? c.getString() : args[i];
		}
		try
		{
			return Component.literal(String.format(raw, plain));
		}
		catch (IllegalFormatException e)
		{
			LOGGER.warn("NeoSim-Announce: bad template '{}' — {}", raw, e.getMessage());
			return Component.literal(raw);
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

		Component msg = tpl(Config.ANNOUNCE_RENT, formatAmount(total));
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
			return schematic.isResidential();
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

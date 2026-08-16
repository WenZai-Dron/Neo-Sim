package com.wenzai.neosim.life;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.npc.CityLivingManager;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

// 衰老与寿终
public class AgingSystem
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// 每日寿终概率1/10
	private static final double OLD_AGE_DEATH_CHANCE = 0.1;

	private static final Random RANDOM = new Random();

	private AgingSystem() {}

	// 每日结算：先长岁，再处理成年离家与高龄寿终
	public static void onDayStart(ServerLevel level, int dayOfWeek)
	{
		int adultAge = Config.LIFE_ADULT_AGE.get();
		int maxAge = Config.LIFE_MAX_AGE.get();
		boolean adultAgingDay = dayOfWeek == Config.LIFE_AGING_ADULT_DAY.get();
		boolean childAgingDay = Config.LIFE_AGING_CHILD_DAYS.get().contains(dayOfWeek);

		for (net.minecraft.world.entity.Entity e : level.getAllEntities())
		{
			if (!(e instanceof Entity npc)) continue;
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
				LifeSystem.announce(level, npc.getCityName(),
						LifeSystem.tpl(Config.ANNOUNCE_ADULT_LEAVE, npc.getNpcName(), adultAge));
			}

			// 高龄寿终：超过寿终年龄后每天1/10概率自然死亡
			if (npc.getAge() > maxAge && RANDOM.nextDouble() < OLD_AGE_DEATH_CHANCE)
			{
				LOGGER.info("NeoSim-AgingSystem: '{}' died of old age at {}", npc.getNpcName(), npc.getAge());
				npc.die(level.damageSources().genericKill());
			}
		}

		// 未加载NPC：数据侧同样结算
		String city = ModSavedData.getActiveCityName();
		if (!city.isEmpty())
		{
			Set<String> loadedNames = new HashSet<>();
			for (net.minecraft.world.entity.Entity e : level.getAllEntities())
			{
				if (e instanceof Entity npc) loadedNames.add(npc.getNpcName());
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
					LifeSystem.announce(level, city,
							LifeSystem.tpl(Config.ANNOUNCE_ADULT_LEAVE, name, adultAge));
				}

				// 高龄寿终：删档、退房、族谱、人口同步
				if (age > maxAge && RANDOM.nextDouble() < OLD_AGE_DEATH_CHANCE)
				{
					LOGGER.info("NeoSim-AgingSystem: '{}' died of old age while unloaded at {}", name, age);
					Manage.dieUnloaded(level, city, name);
					continue;
				}

				if (aged)
				{
					NpcData.patchAge(level, city, name, (short) age);
				}
			}
		}
	}
}

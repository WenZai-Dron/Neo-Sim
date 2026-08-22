package com.wenzai.neosim.npc;

import java.util.*;

import javax.annotation.Nullable;

// NPC索引：名字→已加载实体、城市→已加载实体集合。
// 在 Entity.onAddedToWorld/onRemovedFromWorld 维护（见 Entity），改名时重挂键。
// 仅服务端使用（客户端实体不注册），服务器主线程访问，无需加锁。
public final class NpcRegistry
{
	private static final Map<String, Entity> BY_NAME = new HashMap<>();
	private static final Map<String, Set<Entity>> BY_CITY = new HashMap<>();

	private NpcRegistry()
	{
	}

	public static void register(Entity npc)
	{
		if (npc == null) return;
		String name = npc.getNpcName();
		String city = npc.getCityName();
		if (name.isEmpty()) return;

		BY_NAME.put(name, npc);
		if (!city.isEmpty())
		{
			BY_CITY.computeIfAbsent(city, k -> new HashSet<>()).add(npc);
		}
	}

	public static void unregister(Entity npc)
	{
		if (npc == null) return;
		String name = npc.getNpcName();
		String city = npc.getCityName();
		if (!name.isEmpty())
		{
			// 仅当该名字当前指向此实体时才移除（避免改名时序下误删新条目）
			BY_NAME.remove(name, npc);
		}
		if (!city.isEmpty())
		{
			Set<Entity> set = BY_CITY.get(city);
			if (set != null)
			{
				set.remove(npc);
				if (set.isEmpty()) BY_CITY.remove(city);
			}
		}
	}

	// 按名字查已加载实体（等价于原全服线性扫描的语义：已死/已移除视为未找到）
	@Nullable
	public static Entity findByName(String name)
	{
		if (name == null || name.isEmpty()) return null;
		Entity e = BY_NAME.get(name);
		if (e == null || e.isRemoved() || !e.isAlive()) return null;
		return e;
	}

	// 某城市全部已加载实体（只读视图，勿改）
	public static Set<Entity> byCity(String city)
	{
		if (city == null || city.isEmpty()) return Collections.emptySet();
		Set<Entity> set = BY_CITY.get(city);
		return set != null ? set : Collections.emptySet();
	}

	// 全部已加载实体
	public static Collection<Entity> allLoaded()
	{
		return BY_NAME.values();
	}

	// 服务器停止：清空索引，防止跨存档残留
	public static void clear()
	{
		BY_NAME.clear();
		BY_CITY.clear();
	}
}

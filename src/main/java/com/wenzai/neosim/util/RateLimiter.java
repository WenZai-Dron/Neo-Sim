package com.wenzai.neosim.util;


import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 简单的内存限流器：限制单个玩家瞬间点击多次建造
public class RateLimiter
{
	private static final ConcurrentHashMap<String, Long> LAST_CALL = new ConcurrentHashMap<>();

	private RateLimiter() {}

	// 距上次调用>=minIntervalMs才放行（首次调用直接放行）
	public static boolean check(UUID player, String action, long minIntervalMs)
	{
		String key = player + ":" + action;
		long now = System.currentTimeMillis();
		Long last = LAST_CALL.putIfAbsent(key, now);
		if (last == null) return true;
		if (now - last < minIntervalMs) return false;
		LAST_CALL.put(key, now);
		return true;
	}

	// 服务器停止时清理过期条目
	public static void cleanup()
	{
		long cutoff = System.currentTimeMillis() - 300_000L;
		LAST_CALL.entrySet().removeIf(e -> e.getValue() < cutoff);
	}
}

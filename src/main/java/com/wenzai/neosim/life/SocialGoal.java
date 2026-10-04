package com.wenzai.neosim.life;

import com.wenzai.neosim.Config;
import com.wenzai.neosim.npc.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class SocialGoal extends Goal
{
	// 分开结束串门判定（格）
	private static final double SEPARATE_DIST = 8.0;

	// 凑在一起每 12 tick 结算一次关系
	private static final int MEDDLE_TICKS = 12;

	// 目标持续移动时的兜底重算间隔（2 秒）
	private static final int REPATH_INTERVAL = 40;

	// 目标位置变化超过 2 格才值得重算（对方也在移动，逐秒重算纯浪费）
	private static final double TARGET_MOVE_SQR = 4.0D;

	// 单向接近：每 30 秒翻转一次谁主动靠近，走不动就下一轮换人
	private static final long APPROACH_FLIP_TICKS = 600L;

	// 锚点等待上限：对方 10 秒没能更近就放弃本次社交，别一直占着 MOVE 站着不动
	private static final int ANCHOR_WAIT_TICKS = 200;

	private final Entity npc;
	private Entity target;
	private int stuckTicks;
	private int repathTicks;
	private Vec3 pathTarget;
	private int anchorWaitTicks;
	private double anchorLastDistSqr = Double.MAX_VALUE;

	// 目标搜索缓存（每 SEARCH_INTERVAL tick 重搜；搭档失效/走远提前失效）
	private static final int SEARCH_INTERVAL = 60;
	private Entity searchResult;
	private int lastSearchTick = Integer.MIN_VALUE;

	public SocialGoal(Entity npc)
	{
		this.npc = npc;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse()
	{
		if (isNight()) return false;
		if (npc.hasJob()) return false;

		// 已在串门中：验证搭档仍有效，失效则清过期标记
		String partnerName = npc.getHangingWith();
		if (!partnerName.isEmpty())
		{
			Entity partner = findLoaded(partnerName);
			boolean valid = partner != null && partner.isAlive() && !partner.hasJob()
					&& !partner.getHangingWith().isEmpty()
					&& npc.distanceToSqr(partner) <= SEPARATE_DIST * SEPARATE_DIST;

			// 仍与搭档在一起
			if (valid) return false;
			npc.setHangingWith("");
			npc.setHangTicks(0);
			if (partner != null)
			{
				partner.setHangingWith("");
				partner.setHangTicks(0);
			}
		}

		target = findTarget();
		return target != null;
	}

	@Override
	public boolean canContinueToUse()
	{
		if (target == null || !target.isAlive()) return false;
		if (isNight()) return false;
		if (npc.hasJob() || target.hasJob()) return false;
		return true;
	}

	@Override
	public void start()
	{
		stuckTicks = 0;
		pathTarget = null;
		anchorWaitTicks = 0;
		anchorLastDistSqr = Double.MAX_VALUE;

		// 相位错开——以 (tickCount + id) % REPATH_INTERVAL 起步，避免全体 NPC 同一 tick 重算寻路
		repathTicks = Math.floorMod(npc.tickCount + npc.getId(), REPATH_INTERVAL);
	}

	@Override
	public void tick()
	{
		if (target == null) return;

		double distSqr = npc.distanceToSqr(target);

		// 分开超过8格：结束本次串门
		if (distSqr > SEPARATE_DIST * SEPARATE_DIST)
		{
			clearHangout();
			target = null;
			return;
		}

		if (distSqr <= arriveDist() * arriveDist())
		{
			// 到达：原地停住，双方互标记串门对象
			npc.getNavigation().stop();
			npc.setHangingWith(target.getNpcName());
			target.setHangingWith(npc.getNpcName());

			// 凑在一起累计tick->每12tick结算一次关系
			int ticks = npc.getHangTicks() + 1;
			npc.setHangTicks(ticks);
			if (ticks >= MEDDLE_TICKS)
			{
				npc.setHangTicks(0);
				if (npc.level() instanceof ServerLevel serverLevel)
				{
					Relationship.meddle(serverLevel, npc.getCityName(),
							npc.getNpcName(), target.getNpcName());
				}
			}
			return;
		}

		// 只让一方主动靠近：名字字典序小者当锚点原地等，另一方走过去
		// 每 30 秒翻转一次方向（用同一个游戏时钟判定，两侧结论必然一致），走不动就下一轮换人
		if (iAmAnchor(target))
		{
			npc.getNavigation().stop();

			// 对方在靠近就重置计时；10 秒没能更近就放弃本次社交，别一直占着 MOVE 站着不动
			if (distSqr < anchorLastDistSqr - 1.0D)
			{
				anchorLastDistSqr = distSqr;
				anchorWaitTicks = 0;
			}
			else if (++anchorWaitTicks > ANCHOR_WAIT_TICKS)
			{
				clearHangout();
				target = null;
			}
			return;
		}

		// 尚未到达：沿路径走向对方，目标挪窝或走完当前路径才重算
		PathNavigation nav = npc.getNavigation();
		BlockPos pos = target.blockPosition();
		Vec3 pos3 = new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
		boolean moved = pathTarget == null || pathTarget.distanceToSqr(pos3) > TARGET_MOVE_SQR;
		if (nav.isDone() || (moved && ++repathTicks >= REPATH_INTERVAL))
		{
			repathTicks = 0;
			pathTarget = pos3;
			Path path = nav.createPath(pos, 0);
			if (path == null || !nav.moveTo(path, 0.5D))
			{
				nav.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.5D);
			}
		}

		if (nav.isDone())
		{
			stuckTicks++;
			if (stuckTicks > 60)
			{
				// 卡住超过3秒：放弃本次社交
				clearHangout();
				target = null;
			}
		}
		else
		{
			stuckTicks = 0;
		}
	}

	@Override
	public void stop()
	{
		clearHangout();
		target = null;
		npc.getNavigation().stop();
	}

	// 谁主动靠近：名字字典序小者（翻转期反过来）。两侧用同一个游戏时钟判定，结论必然一致
	// 同名异常情况当作双方都主动靠近，退化成互相走近
	private boolean iAmAnchor(Entity other)
	{
		int nameOrder = npc.getNpcName().compareTo(other.getNpcName());
		if (nameOrder == 0) return false;

		boolean flip = (npc.level().getGameTime() / APPROACH_FLIP_TICKS) % 2L == 1L;
		return (nameOrder < 0) != flip;
	}

	// 找对象（结果缓存 + 城市索引，免每 tick AABB 查询与全服扫描）
	private Entity findTarget()
	{
		if (!(npc.level() instanceof ServerLevel serverLevel)) return null;
		String city = npc.getCityName();
		if (city.isEmpty()) return null;

		int range = socialRange();

		// 缓存命中：搭档仍有效且未走远
		if (searchResult != null && lastSearchTick != Integer.MIN_VALUE
				&& npc.tickCount - lastSearchTick < SEARCH_INTERVAL
				&& searchResult.isAlive() && !searchResult.hasJob()
				&& searchResult.getHangingWith().isEmpty()
				&& npc.distanceToSqr(searchResult) <= (double) range * range)
		{
			return searchResult;
		}

		// 重搜：城市索引遍历 + 半径过滤
		Entity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity other : com.wenzai.neosim.npc.NpcRegistry.byCity(city))
		{
			if (other == npc || other.hasJob() || !other.getHangingWith().isEmpty()) continue;
			double d = npc.distanceToSqr(other);
			if (d <= (double) range * range && d < bestDist)
			{
				bestDist = d;
				best = other;
			}
		}
		searchResult = best;
		lastSearchTick = npc.tickCount;
		return best;
	}

	private void clearHangout()
	{
		npc.setHangingWith("");
		npc.setHangTicks(0);
		if (target != null)
		{
			target.setHangingWith("");
			target.setHangTicks(0);
		}
	}

	// 社交寻找半径（默认收窄到 16）
	private static int socialRange()
	{
		try
		{
			return Config.LIFE_SOCIAL_RANGE.get();
		}
		catch (IllegalStateException ignored)
		{
			return 16;
		}
	}

	// 社交判定范围
	private static double arriveDist()
	{
		try
		{
			return Config.LIFE_SOCIAL_ARRIVE_DIST.get();
		}
		catch (IllegalStateException ignored)
		{
			return 2.5;
		}
	}

	private Entity findLoaded(String name)
	{
		if (name.isEmpty() || !(npc.level() instanceof ServerLevel serverLevel)) return null;
		return com.wenzai.neosim.npc.NpcRegistry.findByName(name);
	}

	private boolean isNight()
	{
		return com.wenzai.neosim.Config.isRestTime(npc.level().getDayTime());
	}
}

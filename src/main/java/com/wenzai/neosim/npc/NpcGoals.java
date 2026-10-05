package com.wenzai.neosim.npc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.function.Predicate;

public class NpcGoals
{
	// 生活点附近的活动半径：NPC到家后在这个范围内闲逛
	public static final double HOME_RADIUS = 4.0D;

	// 超出这个距离就认为"到家"状态已失效（被传送/炸飞），重新走完整回家流程
	private static final double HOME_ABANDON_SQR = 32.0D * 32.0D;

	private NpcGoals()
	{
	}

	// 到家判定：贴合生活点，只用于判断寻路是否走完
	public static boolean hasArrived(Entity npc, BlockPos home)
	{
		return within(npc, home, 1.0D);
	}

	// 在家判定：含闲逛范围。回家类目标与"双方夜晚都在家"都以此为准——
	// 否则NPC在生活点旁走两步就被判成离家，夜里造人进度会被反复重置
	public static boolean isNearHome(Entity npc, BlockPos home)
	{
		return within(npc, home, HOME_RADIUS);
	}

	private static boolean within(Entity npc, BlockPos home, double radius)
	{
		if (home == null) return false;
		double dx = npc.getX() - (home.getX() + 0.5D);
		double dz = npc.getZ() - (home.getZ() + 0.5D);
		double dy = npc.getY() - (home.getY() + 1.0D);
		return Math.abs(dx) <= radius && Math.abs(dz) <= radius && Math.abs(dy) <= 2.0D;
	}

	// 回家类目标的公共实现：先寻路回生活点，到家后在生活点附近小范围闲逛而不是原地不动
	public abstract static class HomeGoal extends Goal
	{
		protected final Entity npc;
		protected final double speed;

		private final HomeWander wander;
		private final StuckEscape escape;
		private boolean atHome;

		protected HomeGoal(Entity npc, double speed)
		{
			this.npc = npc;
			this.speed = speed;
			this.wander = new HomeWander(npc, speed);
			this.escape = new StuckEscape(npc, speed);
			setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		// 当前是否处于"该在家"的时段（夜晚 / 休息日）
		protected abstract boolean stateActive();

		@Override
		public boolean canUse()
		{
			return stateActive() && npc.getHomePos() != null;
		}

		// 到家后保持活动（在生活点附近闲逛）直到时段结束
		@Override
		public boolean canContinueToUse()
		{
			return stateActive() && npc.getHomePos() != null;
		}

		@Override
		public void start()
		{
			escape.reset();
			atHome = false;
			wander.reset();
			walkHome();
		}

		@Override
		public void tick()
		{
			BlockPos home = npc.getHomePos();
			if (home == null) return;

			if (hasArrived(npc, home))
			{
				atHome = true;
			}

			if (atHome)
			{
				// 被传送/炸飞到远处：丢掉"到家"状态，走完整回家流程（含卡住传送兜底）
				if (npc.blockPosition().distSqr(home) > HOME_ABANDON_SQR)
				{
					atHome = false;
					escape.reset();
					walkHome();
				}
				else
				{
					// 到家：闲逛接管；走远了它自己会走回来
					wander.tick(home);
					return;
				}
			}

			// 还没到家：能自己走过去就继续走，只有确实无路可走才传送兜底
			escape.tick(home, home);
		}

		@Override
		public void stop()
		{
			atHome = false;
			npc.getNavigation().stop();
			wander.reset();
			escape.reset();
		}

		private void walkHome()
		{
			BlockPos home = npc.getHomePos();
			if (home == null) return;
			PathNavigation nav = npc.getNavigation();
			Path path = nav.createPath(home, 0);
			if (path == null || !nav.moveTo(path, speed))
			{
				nav.moveTo(home.getX() + 0.5D, home.getY() + 1.0D, home.getZ() + 0.5D, speed);
			}
		}
	}

	// 夜晚回家休息：天黑后回生活点，到家后在生活点附近闲逛，天亮解除
	public static class GoHomeGoal extends HomeGoal
	{
		public GoHomeGoal(Entity npc, double speed)
		{
			super(npc, speed);
		}

		@Override
		protected boolean stateActive()
		{
			return com.wenzai.neosim.Config.isRestTime(npc.level().getDayTime());
		}
	}

	// 白天在家休息：onDayStart 按概率置 restToday 的有家NPC（含在岗工人）白天待在生活点附近，次日清晨重新掷骰
	// 分派/雇佣当天会清掉 restToday（assignToSite），恢复岗位（读档/解冻）则保留
	public static class StayHomeGoal extends HomeGoal
	{
		public StayHomeGoal(Entity npc, double speed)
		{
			super(npc, speed);
		}

		@Override
		protected boolean stateActive()
		{
			return !com.wenzai.neosim.Config.isRestTime(npc.level().getDayTime())
					&& npc.isRestToday();
		}
	}

	// 生活点附近的闲逛：回家类目标到家后逐 tick 调用
	// 半径刻意做小——夜里/休息日的NPC该留在家里，只是不能像雕像一样杵着
	static final class HomeWander
	{
		// 两次闲逛之间的停顿（3~10 秒）
		private static final int PAUSE_MIN = 60;
		private static final int PAUSE_MAX = 200;

		// 选点尝试上限；每 tick 只试 1 次，把单 tick 的探点尖峰摊到多个 tick
		private static final int MAX_ATTEMPTS = 6;

		private final Entity npc;
		private final double speed;
		private int pauseTicks;
		private int strollAttempts;
		private boolean strolling;
		private boolean returning;

		HomeWander(Entity npc, double speed)
		{
			this.npc = npc;
			this.speed = speed;
			reset();
		}

		// 进入/离开闲逛状态：先歇一下，避免刚到家就立刻走开
		void reset()
		{
			this.strolling = false;
			this.returning = false;
			this.strollAttempts = 0;
			this.pauseTicks = PAUSE_MIN + npc.getRandom().nextInt(PAUSE_MAX - PAUSE_MIN);
		}

		void tick(BlockPos home)
		{
			// 离生活点太远：先走回去，别让闲逛把NPC一路带出家门
			// 只在开始返回时下一次指令，之后交给寻路自己走完（逐tick重下会反复重建路径）
			if (!isNearHome(npc, home))
			{
				strolling = false;
				if (!returning)
				{
					returning = true;
					pauseTicks = PAUSE_MIN + npc.getRandom().nextInt(PAUSE_MAX - PAUSE_MIN);
					walkBack(home);
				}
				return;
			}
			returning = false;

			// 还在路上：等它走完
			if (!npc.getNavigation().isDone()) return;

			if (strolling)
			{
				// 走到一个点：歇一会儿再挑下一个
				strolling = false;
				pauseTicks = PAUSE_MIN + npc.getRandom().nextInt(PAUSE_MAX - PAUSE_MIN);
				return;
			}

			if (pauseTicks > 0)
			{
				pauseTicks--;
				return;
			}

			if (strollAttempts >= MAX_ATTEMPTS)
			{
				strollAttempts = 0;
				pauseTicks = PAUSE_MIN;
				return;
			}
			strollAttempts++;

			// 本 tick 没挑到点：下一 tick 再试一次，不重置停顿
			if (!startStroll(home)) return;
			strollAttempts = 0;
			strolling = true;
		}

		// 在生活点半径内挑一个能站的点：原版 getPos 负责地形合法性，这里再筛回生活点半径内
		// 每次只试一次，由 tick 逐 tick 重试（单 tick 连试十几次会在夜里堆出尖峰）
		private boolean startStroll(BlockPos home)
		{
			PathNavigation nav = npc.getNavigation();
			Vec3 pos = DefaultRandomPos.getPos(npc, (int) HOME_RADIUS, 3);
			if (pos == null) return false;
			if (Math.abs(pos.x - (home.getX() + 0.5D)) > HOME_RADIUS) return false;
			if (Math.abs(pos.z - (home.getZ() + 0.5D)) > HOME_RADIUS) return false;
			return nav.moveTo(pos.x, pos.y, pos.z, speed);
		}

		private void walkBack(BlockPos home)
		{
			PathNavigation nav = npc.getNavigation();
			Path path = nav.createPath(home, 0);
			if (path == null || !nav.moveTo(path, speed))
			{
				nav.moveTo(home.getX() + 0.5D, home.getY() + 1.0D, home.getZ() + 0.5D, speed);
			}
		}
	}

	// 卡住处理：能自己走过去就绝不传送
	// 原来是"导航停下3秒就传送"，于是明明有路可绕的NPC也会被凭空拽到目标点
	static final class StuckEscape
	{
		// 重下寻路指令的基础间隔（1 秒）；失败后逐次翻倍退避
		private static final int REPATH_BASE = 20;

		// 退避上限（10 秒）：走不到的 NPC 不必每秒重算
		private static final int REPATH_MAX = 200;

		// 已确认无路时本次搜索只用 35% 节点预算（注定大概率再失败，别烧满）
		private static final float NO_PATH_BUDGET_SCALE = 0.35F;

		// 连续确认无路可走 3 次才兜底传送
		private static final int NO_PATH_STRIKES = 3;

		private final Entity npc;
		private final double speed;
		private int ticks;
		private int noPathStrikes;
		private int repathInterval = REPATH_BASE;

		StuckEscape(Entity npc, double speed)
		{
			this.npc = npc;
			this.speed = speed;
		}

		void reset()
		{
			ticks = 0;
			noPathStrikes = 0;
			repathInterval = REPATH_BASE;
		}

		// 未到达目标时逐 tick 调用
		// walkPos：寻路目标（NPC 该站到的格子）；basePos：兜底传送的基准方块
		boolean tick(BlockPos walkPos, BlockPos basePos)
		{
			if (walkPos == null || basePos == null) return false;

			// 休息时间一律不传送（赴工/回家两条路都走这里）：走不动就走不动，等休息结束再说
			if (npc.isRestingNow()) return false;

			PathNavigation nav = npc.getNavigation();

			// 还在走：不干预
			if (!nav.isDone())
			{
				reset();
				return false;
			}

			// 超出单条路径的距离上限：PathFinder 会硬截断，注定到不了，
			// 直接兜底，不再白跑一次昂贵的搜索
			if (beyondWalkRange(walkPos))
			{
				teleport(basePos);
				return true;
			}

			if (++ticks < repathInterval) return false;
			ticks = 0;

			// 已确认无路时压低本次搜索预算：不可达目标的搜索最贵，且大概率还会再失败
			nav.setMaxVisitedNodesMultiplier(noPathStrikes > 0 ? NO_PATH_BUDGET_SCALE : 1.0F);
			boolean reached = walkTo(walkPos);
			nav.resetMaxVisitedNodesMultiplier();

			// 还能走 → 继续走，不传送，退避复位
			if (reached)
			{
				noPathStrikes = 0;
				repathInterval = REPATH_BASE;
				return false;
			}

			// 确实无路可走：退避加倍，连续确认若干次才兜底传送
			repathInterval = Math.min(REPATH_MAX, repathInterval * 2);
			if (++noPathStrikes < NO_PATH_STRIKES) return false;

			teleport(basePos);
			return true;
		}

		// 目标超出 FOLLOW_RANGE 时寻路器根本不会展开那么远的节点，属于注定走不到
		private boolean beyondWalkRange(BlockPos walkPos)
		{
			double maxWalk = npc.getAttributeValue(Attributes.FOLLOW_RANGE);
			return npc.blockPosition().distSqr(walkPos) > maxWalk * maxWalk;
		}

		private void teleport(BlockPos basePos)
		{
			noPathStrikes = 0;
			npc.teleportTo(basePos.getX() + 0.5D, basePos.getY() + 1.0D, basePos.getZ() + 0.5D);
			npc.getNavigation().stop();
		}

		// 只有"存在一条真正能抵达目标的路径"才算走得到
		// createPath 在目标不可达时可能返回只能走一半的路径，故必须查 canReach
		private boolean walkTo(BlockPos walkPos)
		{
			PathNavigation nav = npc.getNavigation();
			Path path = nav.createPath(walkPos, 0);
			if (path == null || !path.canReach()) return false;
			return nav.moveTo(path, speed);
		}
	}

	// 让NPC走到模盒正上方（距离≤3格判定到达；能走到就走，确实无路才传送）
	public static class MoveToSiteGoal extends Goal
	{
		private final Entity npc;
		private BlockPos target;
		private final double speed;
		private final StuckEscape escape;

		// 仅用于放弃判定：导航空闲的累计时长
		private int stuckTicks;

		public MoveToSiteGoal(Entity npc, double speed)
		{
			this.npc = npc;
			this.speed = speed;
			this.escape = new StuckEscape(npc, speed);
			setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		public void setTarget(BlockPos pos)
		{
			this.target = pos;
			this.stuckTicks = 0;
			this.escape.reset();
		}

		// NPC位于模盒正上方
		public boolean hasArrived()
		{
			return target != null && isAboveSite(npc, target);
		}

		// 判断NPC是否在模盒正上方
		public static boolean isAboveSite(Entity npc, BlockPos box)
		{
			double dx = npc.getX() - (box.getX() + 0.5);
			double dz = npc.getZ() - (box.getZ() + 0.5);
			double dy = npc.getY() - (box.getY() + 1.0);
			return Math.abs(dx) <= 0.75 && Math.abs(dz) <= 0.75 && Math.abs(dy) <= 1.0;
		}

		// 现在能不能走：休息时段/休息日不赴工；
		// 但身上还压着在途材料（快递）时先送完这一单再回家——回家交给 GoHomeGoal
		private boolean canGoNow()
		{
			return !npc.isRestingNow() || !npc.isCarriageEmpty();
		}

		@Override
		public boolean canUse()
		{
			// 休息时间不赴工：夜里/休息日不该被拽去工地干活（在途送货除外）
			return target != null && !hasArrived() && canGoNow();
		}

		@Override
		public boolean canContinueToUse()
		{
			return target != null && !hasArrived() && stuckTicks < 200 && canGoNow();
		}

		@Override
		public void start()
		{
			stuckTicks = 0;
			escape.reset();
			PathNavigation nav = npc.getNavigation();

			// 寻路到模盒正上方一格
			Path path = nav.createPath(target.above(), 0);
			if (path == null || !nav.moveTo(path, speed))
			{
				nav.moveTo(target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5, speed);
			}
		}

		@Override
		public void tick()
		{
			if (target == null) return;

			// 导航空闲时长：只用于 canContinueToUse 的放弃判定
			if (npc.getNavigation().isDone()) stuckTicks++;
			else stuckTicks = 0;

			// 能自己走过去就继续走，只有确实无路可走才传送兜底
			escape.tick(target.above(), target);
		}

		@Override
		public void stop()
		{
			npc.getNavigation().stop();
		}
	}

	// 逃怪目标：没在逃跑时每 5 tick 才真查一次 8 格 AABB
	// 原版 canUse 每次都要做一次实体范围查询 + getNearestEntity，而 GoalSelector 每 2 tick 就会问一次
	// canContinueToUse 父类判的是「路径走完没有」，不经 canUse，所以逃跑过程不会被这个节流打断
	static final class AvoidGoal extends AvoidEntityGoal<LivingEntity>
	{
		private static final int CHECK_INTERVAL = 5;

		private int lastCheckTick = Integer.MIN_VALUE;

		AvoidGoal(PathfinderMob mob, Class<LivingEntity> avoidClass, Predicate<LivingEntity> avoidPredicate,
				float maxDist, double walkSpeedModifier, double sprintSpeedModifier,
				Predicate<LivingEntity> predicateOnAvoidEntity)
		{
			super(mob, avoidClass, avoidPredicate, maxDist, walkSpeedModifier, sprintSpeedModifier, predicateOnAvoidEntity);
		}

		@Override
		public boolean canUse()
		{
			int now = this.mob.tickCount;
			if (now - lastCheckTick < CHECK_INTERVAL) return false;
			lastCheckTick = now;
			return super.canUse();
		}
	}
}

package com.wenzai.neosim.npc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

public class NpcGoals
{
    private NpcGoals() {}

    // 夜晚回家休息：有生活点的NPC天黑后回生活点，原地休息，天亮解除
    public static class GoHomeGoal extends Goal
    {
        private final Entity npc;
        private final double speed;
        private int stuckTicks;

        public GoHomeGoal(Entity npc, double speed)
        {
            this.npc = npc;
            this.speed = speed;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse()
        {
            if (!isNight()) return false;
            BlockPos home = npc.getHomePos();
            return home != null && !hasArrived(npc, home);
        }

        // 到家后保持活动（原地休息）直到天亮，防止夜里乱逛
        @Override
        public boolean canContinueToUse()
        {
            if (!isNight()) return false;
            return npc.getHomePos() != null;
        }

        @Override
        public void start()
        {
            stuckTicks = 0;
            BlockPos home = npc.getHomePos();
            if (home == null) return;
            PathNavigation nav = npc.getNavigation();
            Path path = nav.createPath(home, 0);
            if (path == null || !nav.moveTo(path, speed))
            {
                nav.moveTo(home.getX() + 0.5, home.getY() + 1, home.getZ() + 0.5, speed);
            }
        }

        @Override
        public void tick()
        {
            BlockPos home = npc.getHomePos();
            if (home == null) return;

            if (hasArrived(npc, home))
            {
                // 到家：原地休息
                npc.getNavigation().stop();
                return;
            }
            if (npc.getNavigation().isDone())
            {
                stuckTicks++;
                if (stuckTicks > 60)
                {
                    // 卡住超过3秒，传送到生活点上方
                    npc.teleportTo(home.getX() + 0.5, home.getY() + 1, home.getZ() + 0.5);
                    npc.getNavigation().stop();
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
            npc.getNavigation().stop();
        }

        // 到家判定
        public static boolean hasArrived(Entity npc, BlockPos home)
        {
            double dx = npc.getX() - (home.getX() + 0.5);
            double dz = npc.getZ() - (home.getZ() + 0.5);
            double dy = npc.getY() - (home.getY() + 1.0);
            return Math.abs(dx) <= 1.0 && Math.abs(dz) <= 1.0 && Math.abs(dy) <= 2.0;
        }

        private boolean isNight()
        {
            return npc.level().getDayTime() % 24000 >= 12000;
        }
    }

    // 白天在家休息：onDayStart 掷 1/4 概率置 restToday 的无业有家NPC白天回生活点原地休息，次日清晨重新掷骰
    // 与 GoHomeGoal 共享 hasArrived 到家判定；有工作时（assignToSite）该目标会被移除且 restToday 被清除
    public static class StayHomeGoal extends Goal
    {
        private final Entity npc;
        private final double speed;
        private int stuckTicks;

        public StayHomeGoal(Entity npc, double speed)
        {
            this.npc = npc;
            this.speed = speed;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse()
        {
            if (isNight()) return false;
            if (!npc.isRestToday()) return false;
            if (npc.hasJob()) return false;
            BlockPos home = npc.getHomePos();
            return home != null && !GoHomeGoal.hasArrived(npc, home);
        }

        // 到家后保持原地休息直到天黑，防止休息日乱逛
        @Override
        public boolean canContinueToUse()
        {
            if (isNight()) return false;
            return npc.isRestToday() && !npc.hasJob() && npc.getHomePos() != null;
        }

        @Override
        public void start()
        {
            stuckTicks = 0;
            BlockPos home = npc.getHomePos();
            if (home == null) return;
            PathNavigation nav = npc.getNavigation();
            Path path = nav.createPath(home, 0);
            if (path == null || !nav.moveTo(path, speed))
            {
                nav.moveTo(home.getX() + 0.5, home.getY() + 1, home.getZ() + 0.5, speed);
            }
        }

        @Override
        public void tick()
        {
            BlockPos home = npc.getHomePos();
            if (home == null) return;

            if (GoHomeGoal.hasArrived(npc, home))
            {
                // 到家：原地休息
                npc.getNavigation().stop();
                return;
            }
            if (npc.getNavigation().isDone())
            {
                stuckTicks++;
                if (stuckTicks > 60)
                {
                    // 卡住超过3秒，传送到生活点上方
                    npc.teleportTo(home.getX() + 0.5, home.getY() + 1, home.getZ() + 0.5);
                    npc.getNavigation().stop();
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
            npc.getNavigation().stop();
        }

        private boolean isNight()
        {
            return npc.level().getDayTime() % 24000 >= 12000;
        }
    }

    // 让NPC走到模盒正上方（距离≤3格判定到达，卡住3秒传送）
    public static class MoveToSiteGoal extends Goal
    {
        private final Entity npc;
        private BlockPos target;
        private final double speed;
        private int stuckTicks;

        public MoveToSiteGoal(Entity npc, double speed)
        {
            this.npc = npc;
            this.speed = speed;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        public void setTarget(BlockPos pos)
        {
            this.target = pos;
            this.stuckTicks = 0;
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

        @Override
        public boolean canUse()
        {
            return target != null && !hasArrived();
        }

        @Override
        public boolean canContinueToUse()
        {
            return target != null && !hasArrived() && stuckTicks < 200;
        }

        @Override
        public void start()
        {
            stuckTicks = 0;
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
            if (npc.getNavigation().isDone())
            {
                stuckTicks++;
                if (stuckTicks > 60)
                {
                    // 卡住超过3秒，传送到模盒正上方
                    npc.teleportTo(target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5);
                    npc.getNavigation().stop();
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
            npc.getNavigation().stop();
        }
    }
}

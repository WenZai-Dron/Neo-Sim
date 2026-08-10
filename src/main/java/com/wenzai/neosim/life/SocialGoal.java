// 串门社交AI

package com.wenzai.neosim.life;

import com.wenzai.neosim.Config;
import com.wenzai.neosim.npc.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

public class SocialGoal extends Goal
{
    private static final double SEPARATE_DIST = 8.0;  // 分开结束串门判定（格）
    private static final int MEDDLE_TICKS = 12;       // 凑在一起每 12 tick 结算一次关系
    private static final int REPATH_INTERVAL = 20;    // 每 20 tick 重新寻路（目标会移动）

    private final Entity npc;
    private Entity target;
    private int stuckTicks;
    private int repathTicks;

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
        repathTicks = 0;
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

        // 尚未到达：沿路径走向对方，周期性重新寻路
        PathNavigation nav = npc.getNavigation();
        if (nav.isDone() || ++repathTicks >= REPATH_INTERVAL)
        {
            repathTicks = 0;
            BlockPos pos = target.blockPosition();
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

    // 找对象
    private Entity findTarget()
    {
        if (!(npc.level() instanceof ServerLevel serverLevel)) return null;
        String city = npc.getCityName();
        if (city.isEmpty()) return null;

        AABB box = new AABB(npc.blockPosition()).inflate(socialRange());
        List<Entity> others = serverLevel.getEntitiesOfClass(Entity.class, box, e ->
                e != npc && city.equals(e.getCityName()) && !e.hasJob() && e.getHangingWith().isEmpty());

        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity other : others)
        {
            double d = npc.distanceToSqr(other);
            if (d < bestDist)
            {
                bestDist = d;
                best = other;
            }
        }
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

    // 社交寻找半径
    private static int socialRange()
    {
        try
        {
            return Config.LIFE_SOCIAL_RANGE.get();
        }
        catch (IllegalStateException ignored)
        {
            return 40;
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
        for (net.minecraft.world.entity.Entity e : serverLevel.getAllEntities())
        {
            if (e instanceof Entity npc2 && name.equals(npc2.getNpcName()))
            {
                return npc2;
            }
        }
        return null;
    }

    private boolean isNight()
    {
        return npc.level().getDayTime() % 24000 >= 12000;
    }
}

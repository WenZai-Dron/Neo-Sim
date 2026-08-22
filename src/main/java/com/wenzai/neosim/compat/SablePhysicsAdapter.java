package com.wenzai.neosim.compat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

// Sable（子世界）物理适配器。
// 当前仅保留与子世界无写交互的辅助能力（世界坐标投影、NPC 跟踪、子世界判定）；
// 方块读写集成已停用——任何对子世界的访问都可能触发 Sable 卸载队列自旋（保存卡死）。
public class SablePhysicsAdapter implements IPhysicsAdapter
{
	// 世界命中结果：子世界 + 局部坐标
	private record Hit(SubLevel sub, BlockPos local) {}

	@Override
	public boolean isAvailable()
	{
		return true;
	}

	// 公开判定：pos 是否位于某子世界 plot 网格内
	public static boolean isInSubLevel(ServerLevel level, BlockPos pos)
	{
		return containingLocal(level, pos) != null;
	}

	// 情况A：pos 已位于某子世界 plot 网格内
	private static SubLevel containingLocal(ServerLevel level, BlockPos pos)
	{
		SubLevel sub = Sable.HELPER.getContaining(level, pos);
		return (sub == null || sub.isRemoved()) ? null : sub;
	}

	// 世界坐标 → 局部坐标（与 Sable 自身 runIncludingSubLevels 相同的换算）
	private static BlockPos toLocal(Pose3dc pose, BlockPos world)
	{
		Vector3d v = pose.transformPositionInverse(
				new Vector3d(world.getX(), world.getY(), world.getZ()), new Vector3d());
		return new BlockPos((int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z));
	}

	// 情况B：世界 pos 处确有结构方块时返回命中结果
	private static Hit worldHit(ServerLevel level, BlockPos worldPos)
	{
		BoundingBox3d box = new BoundingBox3d(
				worldPos.getX(), worldPos.getY(), worldPos.getZ(),
				worldPos.getX() + 1.0, worldPos.getY() + 1.0, worldPos.getZ() + 1.0);
		for (SubLevel sub : Sable.HELPER.getAllIntersecting(level, box))
		{
			if (sub.isRemoved()) continue;
			BlockPos local = toLocal(sub.logicalPose(), worldPos);
			if (!plotGet(sub.getPlot(), level, local).isAir())
			{
				return new Hit(sub, local);
			}
		}
		return null;
	}

	// plot 内读取：只碰 plot 自己的 holder 区块，绝不触发父级 chunk 加载
	private static BlockState plotGet(LevelPlot plot, ServerLevel level, BlockPos local)
	{
		LevelChunk chunk = plotChunk(plot, local);
		return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(local);
	}

	// 取 plot 的 holder 区块（绝对局部坐标 → plot 内索引）
	private static LevelChunk plotChunk(LevelPlot plot, BlockPos local)
	{
		ChunkPos inPlot = plot.toLocal(new ChunkPos(local.getX() >> 4, local.getZ() >> 4));
		return plot.getChunk(inPlot);
	}

	@Override
	public BlockState getBlockState(ServerLevel level, BlockPos pos)
	{
		SubLevel local = containingLocal(level, pos);
		if (local != null)
		{
			// 局部空间：结构方块（甲板）优先，其次主世界对应位置的建筑方块
			BlockState plotState = plotGet(local.getPlot(), level, pos);
			if (!plotState.isAir()) return plotState;
			return level.getBlockState(toWorld(level, pos));
		}

		Hit hit = worldHit(level, pos);
		if (hit != null) return plotGet(hit.sub().getPlot(), level, hit.local());
		return null;
	}

	@Override
	public boolean setBlock(ServerLevel level, BlockPos pos, BlockState state, int flags)
	{
		SubLevel local = containingLocal(level, pos);
		if (local != null)
		{
			// 世界坐标放置：写入主世界对应位置，绝不触碰 20.48M plot 区块
			level.setBlock(toWorld(level, pos), state, flags);
			return true;
		}
		Hit hit = worldHit(level, pos);
		if (hit == null) hit = worldHit(level, pos.below());
		if (hit != null)
		{
			level.setBlock(pos, state, flags);
			return true;
		}
		return false;
	}

	@Override
	public boolean destroyBlock(ServerLevel level, BlockPos pos)
	{
		SubLevel local = containingLocal(level, pos);
		if (local != null)
		{
			level.setBlock(toWorld(level, pos), Blocks.AIR.defaultBlockState(), 3);
			return true;
		}
		Hit hit = worldHit(level, pos);
		if (hit != null)
		{
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			return true;
		}
		return false;
	}

	// 把 NPC 登记进子世界跟踪：设置 plotPosition（局部坐标），EntityMixin.tick 会把它投影到
	// 甲板的世界坐标并建立碰撞（随船体姿态站立）。
	public static void attachToSubLevel(ServerLevel level, Entity npc, BlockPos localPos)
	{
		if (npc == null || localPos == null) return;
		SubLevel sub = Sable.HELPER.getContaining(level, localPos);
		if (sub == null || sub.isRemoved()) return;
		if (npc instanceof EntityStickExtension stick)
		{
			stick.sable$setPlotPosition(Vec3.atCenterOf(localPos));
		}
		if (npc instanceof EntityMovementExtension mov)
		{
			mov.sable$setTrackingSubLevel(sub);
		}
	}

	// 局部坐标 → 世界坐标（供 NPC 生成/导航等需要世界坐标的场景）
	public static BlockPos toWorld(ServerLevel level, BlockPos localPos)
	{
		SubLevel sub = containingLocal(level, localPos);
		if (sub == null) return localPos;
		Vector3d v = sub.logicalPose().transformPosition(
				new Vector3d(localPos.getX() + 0.5, localPos.getY() + 0.5, localPos.getZ() + 0.5), new Vector3d());
		return new BlockPos((int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z));
	}
}

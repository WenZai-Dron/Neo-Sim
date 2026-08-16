package com.wenzai.neosim.client.preview;

import com.wenzai.neosim.schematic.CoordTransform;
import com.wenzai.neosim.schematic.SchematicData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

// 预览状态模型
public class PreviewState
{
	private SchematicData schematic;
	private BlockPos origin = BlockPos.ZERO;
	private Rotation rotation = Rotation.NONE;
	private Mirror mirror = Mirror.NONE;

	// 玩家面朝方向
	private Direction facing;
	private boolean isActive;

	// VBO 缓存：仅在预览状态变化时重建
	private GhostBlockRenderer.GhostMeshCache meshCache;
	private boolean needsRebuild = true;

	public PreviewState() {}

	public SchematicData getSchematic() { return schematic; }
	public void setSchematic(SchematicData v) { this.schematic = v; markNeedsRebuild(); }

	public BlockPos getOrigin() { return origin; }
	public void setOrigin(BlockPos v) { this.origin = v; markNeedsRebuild(); }

	public Rotation getRotation() { return rotation; }
	public Mirror getMirror() { return mirror; }

	public void setRotation(Rotation v) { this.rotation = v; markNeedsRebuild(); }
	public void setMirror(Mirror v) { this.mirror = v; markNeedsRebuild(); }

	public Direction getFacing() { return facing; }
	public void setFacing(Direction v) { this.facing = v; markNeedsRebuild(); }

	public boolean isActive() { return isActive; }
	public void setActive(boolean v)
	{
		this.isActive = v;
		// 预览结束：释放缓存的GPU显存
		if (!v && meshCache != null) meshCache.invalidate();
	}

	public GhostBlockRenderer.GhostMeshCache getMeshCache()
	{
		// 仅客户端渲染路径调用
		if (meshCache == null) meshCache = new GhostBlockRenderer.GhostMeshCache();
		return meshCache;
	}

	public boolean needsRebuild() { return needsRebuild; }
	public void markNeedsRebuild() { this.needsRebuild = true; }
	public void clearNeedsRebuild() { this.needsRebuild = false; }

	// 移动
	public void nudgeForward(int dx, int dy, int dz)
	{
		origin = origin.offset(dx, dy, dz);
		markNeedsRebuild();
	}

	// 抬高/降低
	public void nudgeY(int dy)
	{
		origin = origin.offset(0, dy, 0);
		markNeedsRebuild();
	}

	// 旋转
	public void rotate()
	{
		rotation = switch (rotation)
		{
			case NONE              -> Rotation.CLOCKWISE_90;
			case CLOCKWISE_90      -> Rotation.CLOCKWISE_180;
			case CLOCKWISE_180     -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.NONE;
		};
		markNeedsRebuild();
	}

	// 镜像
	public void toggleMirror()
	{
		mirror = (mirror == Mirror.NONE) ? Mirror.LEFT_RIGHT : Mirror.NONE;
		markNeedsRebuild();
	}

	// 蓝图局部坐标同步到世界坐标
	// 先按 Sim-U-Kraft buildDirection 基础映射（facing），再叠加旋转/镜像
	public BlockPos blueprintToWorld(int bx, int by, int bz)
	{
		BlockPos base = facing != null
				? CoordTransform.simukraftPos(bx, by, bz, facing)
				: new BlockPos(bx, by, bz);
		BlockPos transformed = CoordTransform.transformPos(base, mirror, rotation);
		return origin.offset(transformed);
	}
}

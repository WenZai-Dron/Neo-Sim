package com.wenzai.neosim.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

// 蓝图坐标同步到世界坐标
public class CoordTransform
{
	public static BlockPos simukraftPos(int bx, int by, int bz, Direction facing)
	{
		return switch (facing)
		{
			case SOUTH -> new BlockPos(-bx, by, bz);
			case NORTH -> new BlockPos(bx, by, -bz);
			case EAST  -> new BlockPos(bz, by, bx);
			case WEST  -> new BlockPos(-bz, by, -bx);
			default    -> new BlockPos(bx, by, bz);
		};
	}

	// 方块状态随映射
	public static BlockState transformState(BlockState state, Direction facing)
	{
		if (state == null || facing == null) return state;
		if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return state;

		Direction current = state.getValue(BlockStateProperties.HORIZONTAL_FACING);

		// 相对"作者扫描基准"校准
		Direction mapped = mapFacing(mapFacing(current, Direction.SOUTH), facing);
		if (mapped != current)
		{
			state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, mapped);
		}

		// 镜像翻转门的铰链侧
		if (state.hasProperty(BlockStateProperties.DOOR_HINGE))
		{
			state = state.cycle(BlockStateProperties.DOOR_HINGE);
		}
		return state;
	}

	// 水平方向映射
	private static Direction mapFacing(Direction dir, Direction buildFacing)
	{
		return switch (buildFacing)
		{
			// (x,z)->(x,-z)：南北交换
			case NORTH -> switch (dir)
			{
				case NORTH -> Direction.SOUTH;
				case SOUTH -> Direction.NORTH;
				default -> dir;
			};

			// (x,z)->(-x,z)：东西交换
			case SOUTH -> switch (dir)
			{
				case EAST -> Direction.WEST;
				case WEST -> Direction.EAST;
				default -> dir;
			};

			// (x,z)->(z,x)
			case EAST -> switch (dir)
			{
				case NORTH -> Direction.WEST;
				case WEST -> Direction.NORTH;
				case EAST -> Direction.SOUTH;
				case SOUTH -> Direction.EAST;
				default -> dir;
			};

			// (x,z)->(-z,-x)
			case WEST -> switch (dir)
			{
				case NORTH -> Direction.EAST;
				case EAST -> Direction.NORTH;
				case SOUTH -> Direction.WEST;
				case WEST -> Direction.SOUTH;
				default -> dir;
			};
			default -> dir;
		};
	}

	public static BlockPos transformPos(BlockPos pos, Mirror mirror, Rotation rotation)
	{
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		boolean m = true;
		switch (mirror)
		{
			case LEFT_RIGHT: z = -z; break;
			case FRONT_BACK: x = -x; break;
			default: m = false;
		}
		switch (rotation)
		{
			case CLOCKWISE_90: return new BlockPos(-z, y, x);
			case COUNTERCLOCKWISE_90: return new BlockPos(z, y, -x);
			case CLOCKWISE_180: return new BlockPos(-x, y, -z);
			default: return m ? new BlockPos(x, y, z) : pos;
		}
	}
}

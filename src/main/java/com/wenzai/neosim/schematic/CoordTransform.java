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
	// 工具类：只有静态成员，禁止实例化
	private CoordTransform()
	{
	}


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

	// 与 simukraftPos 严格配对的方块状态映射：位置映射是"旋转 + 镜像"，状态就必须用同一对
	// （下面的分解逐条对应 simukraftPos 的四个映射）。
	// 走原版 rotate/mirror，让重写过这两个方法的方块把所有附属属性一起转对：
	//   墙壁告示牌（WallSignBlock）FACING、站立告示牌（StandingSignBlock）ROTATION（0..15 的非朝向属性）、
	//   楼梯（StairBlock）SHAPE、门（DoorBlock）HINGE
	// 只改 HORIZONTAL_FACING 的写法会漏掉 ROTATION / SHAPE / HINGE，表现为"位置转了、朝向没转"
	public static BlockState transformStateByFrame(BlockState state, Direction facing)
	{
		if (state == null || facing == null) return state;

		// 先走原版 rotate/mirror：
		// 重写过这两个方法的方块会把自己所有的附属属性转对 ——
		// 墙壁告示牌（WallSignBlock）转 FACING、站立告示牌（StandingSignBlock）转 ROTATION、
		// 楼梯（StairBlock）转 SHAPE、门（DoorBlock）转 HINGE
		BlockState out = switch (facing)
		{
			case NORTH -> state.mirror(Mirror.LEFT_RIGHT);                              // (x,z)->(x,-z)
			case SOUTH -> state.mirror(Mirror.FRONT_BACK);                              // (x,z)->(-x,z)
			case EAST -> state.rotate(Rotation.CLOCKWISE_90).mirror(Mirror.FRONT_BACK); // (x,z)->(z,x)
			case WEST -> state.rotate(Rotation.CLOCKWISE_90).mirror(Mirror.LEFT_RIGHT); // (x,z)->(-z,-x)
			default -> state;
		};

		// 兜底：原版 BlockBehaviour.rotate/mirror 默认是**空实现**（返回原状态），
		// 而 BedBlock 这类方块有 HORIZONTAL_FACING 却没重写这两个方法 ——
		// 于是位置被镜像了、朝向纹丝不动，表现就是"床方向反了"。
		// 这里按与 simukraftPos 配对的那张表补一次朝向：
		// 对重写过的方块，原版结果与 mapFacing 完全一致，所以是幂等的空操作
		if (out.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
		{
			out = out.setValue(BlockStateProperties.HORIZONTAL_FACING,
					mapFacing(state.getValue(BlockStateProperties.HORIZONTAL_FACING), facing));
		}
		return out;
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

package com.wenzai.neosim.schematic;

import net.minecraft.core.BlockPos;

// 建造扫描顺序：把"逐格推进的方向"从蓝图自己的坐标轴里解出来。
//
// 主循环（ConstructionTask）按 resumeIndex 0,1,2… 逐格推进，索引公式
//   index = y*(sx*sz) + z*sx + x
// 决定了两件固定的事：层（y）在外、x 是最内层。于是"哪条轴铺成一排、哪条轴负责一排排推进"，
// 完全取决于蓝图坐标轴在世界里的指向 —— 玩家按 R 手动旋转会把这两条轴一起转过去，
// 光看索引本身分不出"从近到远"，就会出现"转几次之后从后往前 / 从远往近"。
//
// 这个类把顺序重新钉死成固定口径：
//   层      ：低 → 高
//   每排推进：从玩家近侧往远处推（比较两条轴两端在世界里离模盒的距离，近的那端先铺）
//   排内    ：沿世界 +X 铺满一整排（从左往右）
// 只用到"蓝图哪个轴 → 世界哪个方向"和两端距离，所以蓝图怎么转、怎么镜像都成立。
public final class SchematicScanOrder
{
	// 蓝图里走一步所对应的世界位移（含格式基准角 + 玩家旋转 / 镜像）
	private final int[] stepX;
	private final int[] stepY;
	private final int[] stepZ;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;

	// 推进轴 / 排内轴（0=X, 2=Z）
	private final int aAxis;
	private final int bAxis;
	private final int aSize;
	private final int bSize;

	// 推进轴的值是否要从大到小取：蓝图 (0,0,0) 端离玩家远时，就从另一端开始铺
	private final boolean flipAdvance;

	private SchematicScanOrder(int[] stepX, int[] stepY, int[] stepZ, int sizeX, int sizeY, int sizeZ,
							   boolean flipAdvance)
	{
		this.stepX = stepX;
		this.stepY = stepY;
		this.stepZ = stepZ;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.flipAdvance = flipAdvance;

		// 哪条蓝图轴在世界里更"朝面向方向"：它的世界 Z 位移绝对值更大 → 它就是"一排排推进"的轴
		boolean xAdvances = Math.abs(stepX[2]) > Math.abs(stepZ[2]);
		this.aAxis = xAdvances ? 0 : 2;
		this.bAxis = aAxis == 0 ? 2 : 0;
		this.aSize = aAxis == 0 ? sizeX : sizeZ;
		this.bSize = bAxis == 0 ? sizeX : sizeZ;
	}

	// 落点对齐（landingOffset）是纯平移，不影响方向，所以直接量蓝图各轴走一步的世界方向
	public static SchematicScanOrder of(BlueprintPlacement placement, int sizeX, int sizeY, int sizeZ)
	{
		int[] ox = axis(placement, sizeX, sizeY, sizeZ, 0, 0, 0);
		int[] xs = axis(placement, sizeX, sizeY, sizeZ, 1, 0, 0);
		int[] zs = axis(placement, sizeX, sizeY, sizeZ, 0, 0, 1);
		int[] ys = axis(placement, sizeX, sizeY, sizeZ, 0, 1, 0);

		int[] stepX = { xs[0] - ox[0], xs[1] - ox[1], xs[2] - ox[2] };
		int[] stepY = { ys[0] - ox[0], ys[1] - ox[1], ys[2] - ox[2] };
		int[] stepZ = { zs[0] - ox[0], zs[1] - ox[1], zs[2] - ox[2] };

		// 推进轴在世界里是朝 +Z 还是 -Z：朝 -Z 说明"蓝图 0 那一端在远处" → 从另一端开始铺，
		// 这样第一排永远落在离玩家最近的这一侧，之后一排排往面向方向推。
		boolean xAdvances = Math.abs(stepX[2]) > Math.abs(stepZ[2]);
		int aDirZ = xAdvances ? stepX[2] : stepZ[2];
		boolean flipAdvance = aDirZ < 0;

		return new SchematicScanOrder(stepX, stepY, stepZ, sizeX, sizeY, sizeZ, flipAdvance);
	}

	// 投影里走一步 (dx,dy,dz) 对应的世界方向；步长取 1 或该轴最大值，避免单格宽的轴算出 0
	private static int[] axis(BlueprintPlacement p, int sx, int sy, int sz, int dx, int dy, int dz)
	{
		BlockPos a = p.pos(0, 0, 0);
		BlockPos b = p.pos(dx != 0 ? Math.max(1, sx - 1) : 0,
				dy != 0 ? Math.max(1, sy - 1) : 0,
				dz != 0 ? Math.max(1, sz - 1) : 0);
		return new int[] { sign(b.getX() - a.getX()), sign(b.getY() - a.getY()), sign(b.getZ() - a.getZ()) };
	}

	private static int sign(int v)
	{
		return Integer.compare(v, 0);
	}

	// resumeIndex → 蓝图局部坐标（层 低→高；每排从近到远推进；排内沿 +X）
	public void pos(int index, BlockPos.MutableBlockPos out)
	{
		// 解码必须按**扫描布局**来（不是容器布局）：resumeIndex = 层*(排长*aSize) + 排号*bSize + 排内偏移。
		// 否则 90°/270° 时推进轴换成 X，索引会解到越界坐标（踩过这个坑）。
		int layer = index / (aSize * bSize);
		int rest = index % (aSize * bSize);
		int advanceSlot = rest / bSize;   // 第几排
		int rowSlot = rest % bSize;       // 排内第几格

		// 推进：第一排落在离玩家最近的那一侧，之后一排排朝面向方向推
		int a = flipAdvance ? (aSize - 1 - advanceSlot) : advanceSlot;

		// 排内：沿世界 +X 从左往右铺一整排，轴朝 -X 就反过来数
		int bDir = bAxis == 0 ? stepX[0] : stepZ[0];
		int b = bDir < 0 ? (bSize - 1 - rowSlot) : rowSlot;

		int x = aAxis == 0 ? a : b;
		int z = aAxis == 0 ? b : a;
		out.set(x, layer, z);
	}
}

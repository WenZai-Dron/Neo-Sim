package com.wenzai.neosim.client.preview;

import com.mojang.blaze3d.vertex.VertexConsumer;

// 顶点平移装饰器：给某个 VertexConsumer 的每个顶点加一个固定偏移。
// 用途：原版 LiquidBlockRenderer 是用 (pos & 15) 当局部坐标写顶点的（区块 0..15 的老约定），
// 而预览的模型空间是「帧内坐标 - origin」（世界幽灵）或「以建筑中心为原点」（GUI 缩略图）。
// pos 本身不能改 —— 它同时还要喂给 level 做邻居高度/流向/染色查询，
// 所以只能在输出端把「模型空间位置 - (pos & 15)」补回去，否则流体整片错位（>16 还会按 16 回绕）。
public final class OffsetVertexConsumer implements VertexConsumer
{
	private final VertexConsumer delegate;
	private final float dx;
	private final float dy;
	private final float dz;

	public OffsetVertexConsumer(VertexConsumer delegate, float dx, float dy, float dz)
	{
		this.delegate = delegate;
		this.dx = dx;
		this.dy = dy;
		this.dz = dz;
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z)
	{
		return delegate.addVertex(x + dx, y + dy, z + dz);
	}

	@Override
	public VertexConsumer setColor(int red, int green, int blue, int alpha)
	{
		return delegate.setColor(red, green, blue, alpha);
	}

	@Override
	public VertexConsumer setUv(float u, float v)
	{
		return delegate.setUv(u, v);
	}

	@Override
	public VertexConsumer setUv1(int u, int v)
	{
		return delegate.setUv1(u, v);
	}

	@Override
	public VertexConsumer setUv2(int u, int v)
	{
		return delegate.setUv2(u, v);
	}

	@Override
	public VertexConsumer setNormal(float normalX, float normalY, float normalZ)
	{
		return delegate.setNormal(normalX, normalY, normalZ);
	}
}

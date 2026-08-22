package com.wenzai.neosim.client.gui;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import com.wenzai.neosim.schematic.LightweightBlockContainer;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SpecialMarker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL32C;

import java.util.Map;

// GUI内建筑3D预览渲染器
public final class BuildingPreviewRenderer
{
	// 多槽 LRU 缓存最近 N 个蓝图的 VBO（鼠标扫过多个蓝图按钮时不再每次全量重建+上传）
	private static final int CACHE_SIZE = 4;

	private static final java.util.LinkedHashMap<String, CacheEntry> CACHE =
			new java.util.LinkedHashMap<>(CACHE_SIZE, 0.75f, true)
			{
				@Override
				protected boolean removeEldestEntry(java.util.Map.Entry<String, CacheEntry> eldest)
				{
					if (size() > CACHE_SIZE)
					{
						eldest.getValue().close();
						return true;
					}
					return false;
				}
			};

	private BuildingPreviewRenderer()
	{
	}

	// 释放缓存的VBO
	public static void release()
	{
		for (CacheEntry e : CACHE.values())
		{
			e.close();
		}
		CACHE.clear();
	}

	// 在GUI面板中心绘制建筑
	public static void render(GuiGraphics gfx, SchematicData schematic,
							  int cx, int cy, int size, float yawDeg, float pitchDeg)
	{
		if (schematic == null) return;
		CacheEntry entry = ensureMesh(schematic);
		if (entry == null) return;

		var poseStack = gfx.pose();
		poseStack.pushPose();

		// 3D模型放进GUI正交投影的深度范围
		poseStack.translate(cx, cy, 1050.0F);
		poseStack.scale(1.0F, 1.0F, -1.0F);
		poseStack.translate(0.0F, 0.0F, 1000.0F);

		int maxDim = Math.max(entry.dimX, Math.max(entry.dimY, entry.dimZ));
		float scale = maxDim > 0 ? size * 0.6F / maxDim : size;
		poseStack.scale(scale, scale, scale);

		poseStack.scale(1.0F, -1.0F, 1.0F);

		// 绕建筑中心旋转
		poseStack.mulPose(Axis.YP.rotationDegrees(yawDeg));
		poseStack.mulPose(Axis.XP.rotationDegrees(pitchDeg));

		poseStack.scale(1.0F, 1.0F, -1.0F);

		// L13：深度清空 + 绘制限定在面板矩形内（GL_SCISSOR），避免每帧全屏深度清空拖慢后续 GUI 元素
		int half = Math.max(8, size / 2);
		gfx.enableScissor(cx - half, cy - half, cx + half, cy + half);

		// GUI正交投影的深度与主世界相反
		RenderSystem.clearDepth(0.0f);
		RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, false);
		RenderSystem.depthFunc(GlConst.GL_GEQUAL);
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();

		Matrix4f mvForDet = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());
		Matrix4f prForDet = new Matrix4f(RenderSystem.getProjectionMatrix());
		float totalDet = mvForDet.determinant() * prForDet.determinant();
		RenderSystem.enableCull();
		GL32C.glFrontFace(totalDet >= 0.0F ? GL32C.GL_CCW : GL32C.GL_CW);

		RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
		RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
		entry.vertexBuffer.bind();

		Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
		modelView.mul(poseStack.last().pose());
		entry.vertexBuffer.drawWithShader(modelView,
				RenderSystem.getProjectionMatrix(), RenderSystem.getShader());

		// 恢复GUI默认状态，避免影响后续元素
		gfx.disableScissor();
		GL32C.glFrontFace(GL32C.GL_CCW);
		RenderSystem.disableCull();
		RenderSystem.depthFunc(GlConst.GL_LEQUAL);
		RenderSystem.clearDepth(1.0f);
		RenderSystem.enableBlend();
		poseStack.popPose();
	}

	// 按蓝图名 LRU 缓存，命中直接复用；未命中重建（超容量时 removeEldestEntry 自动释放最久未用项）
	private static CacheEntry ensureMesh(SchematicData schematic)
	{
		String name = schematic.getName();
		CacheEntry hit = CACHE.get(name);
		if (hit != null) return hit;

		LightweightBlockContainer container = schematic.getBlockContainer();
		CacheEntry entry = new CacheEntry();
		entry.dimX = container.getSizeX();
		entry.dimY = container.getSizeY();
		entry.dimZ = container.getSizeZ();

		Minecraft mc = Minecraft.getInstance();
		BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
		BlockColors blockColors = mc.getBlockColors();
		RandomSource random = RandomSource.create();

		ByteBufferBuilder byteBuffer = new ByteBufferBuilder(1 << 20);
		BufferBuilder buf = new BufferBuilder(byteBuffer,
				VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

		MeshBuilder mesh = new MeshBuilder(buf, blockRenderer, blockColors, random,
				entry.dimX / 2.0F, entry.dimY / 2.0F, entry.dimZ / 2.0F);

		// 逐方块生成网格
		for (int y = 0; y < entry.dimY; y++)
		{
			for (int z = 0; z < entry.dimZ; z++)
			{
				for (int x = 0; x < entry.dimX; x++)
				{
					mesh.addBlock(container, x, y, z);
				}
			}
		}

		// 特殊标记
		Map<BlockPos, SpecialMarker> markers = schematic.getSpecialMarkers();
		if (markers != null)
		{
			for (Map.Entry<BlockPos, SpecialMarker> markerEntry : markers.entrySet())
			{
				BlockState markerState = markerEntry.getValue().toBlockState();
				if (markerState != null)
				{
					mesh.addMarker(markerState, markerEntry.getKey());
				}
			}
		}

		// 空容器/全空气直接跳过，避免抛异常把整个界面搞崩
		MeshData meshData = buf.build();
		if (meshData == null)
		{
			byteBuffer.close();
			return null;
		}
		VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
		vb.bind();
		vb.upload(meshData);
		byteBuffer.close();
		entry.vertexBuffer = vb;
		CACHE.put(name, entry);
		return entry;
	}

	// 单个蓝图的VBO缓存项
	private static final class CacheEntry
	{
		VertexBuffer vertexBuffer;
		int dimX, dimY, dimZ;

		void close()
		{
			if (vertexBuffer != null)
			{
				vertexBuffer.close();
				vertexBuffer = null;
			}
		}
	}

	// 网格构建上下文
	private static final class MeshBuilder
	{
		private final BufferBuilder buf;
		private final BlockRenderDispatcher blockRenderer;
		private final BlockColors blockColors;
		private final RandomSource random;
		private final PoseStack ps = new PoseStack();
		private final float cx, cy, cz;

		MeshBuilder(BufferBuilder buf, BlockRenderDispatcher blockRenderer,
					BlockColors blockColors, RandomSource random,
					float cx, float cy, float cz)
		{
			this.buf = buf;
			this.blockRenderer = blockRenderer;
			this.blockColors = blockColors;
			this.random = random;
			this.cx = cx;
			this.cy = cy;
			this.cz = cz;
		}

		// 渲染单个方块：缺模型时画灰色兜底立方体
		void addBlock(LightweightBlockContainer container, int x, int y, int z)
		{
			BlockState state = container.get(x, y, z);
			if (state.isAir()) return;

			BakedModel model = blockRenderer.getBlockModel(state);
			ps.setIdentity();

			ps.translate(x - cx, y - cy, z - cz);
			PoseStack.Pose pose = ps.last();

			boolean hasQuads = false;
			boolean anyVisibleSide = false;
			for (Direction side : Direction.values())
			{
				if (!shouldRenderSide(container, x, y, z, side)) continue;
				anyVisibleSide = true;
				for (BakedQuad q : model.getQuads(state, side, random))
				{
					emitQuad(buf, pose, q, blockColors, state);
					hasQuads = true;
				}
			}
			for (BakedQuad q : model.getQuads(state, null, random))
			{
				emitQuad(buf, pose, q, blockColors, state);
				hasQuads = true;
			}

			if (!hasQuads && anyVisibleSide)
			{
				emitFallbackCube(buf, x - cx, y - cy, z - cz);
			}
		}

		// 特殊标记方块
		void addMarker(BlockState state, BlockPos pos)
		{
			BakedModel model = blockRenderer.getBlockModel(state);
			ps.setIdentity();
			ps.translate(pos.getX() - cx, pos.getY() - cy, pos.getZ() - cz);
			PoseStack.Pose pose = ps.last();
			for (Direction side : Direction.values())
			{
				for (BakedQuad q : model.getQuads(state, side, random))
				{
					emitQuad(buf, pose, q, blockColors, state);
				}
			}
			for (BakedQuad q : model.getQuads(state, null, random))
			{
				emitQuad(buf, pose, q, blockColors, state);
			}
		}
	}

	private static void emitQuad(BufferBuilder buf, PoseStack.Pose pose, BakedQuad quad,
								 BlockColors blockColors, BlockState state)
	{
		float r = 1.0F, g = 1.0F, b = 1.0F;
		if (quad.isTinted())
		{
			int tint = blockColors.getColor(state, null, BlockPos.ZERO, quad.getTintIndex());
			r = ((tint >> 16) & 0xFF) / 255.0F;
			g = ((tint >> 8) & 0xFF) / 255.0F;
			b = (tint & 0xFF) / 255.0F;
		}
		float f = quad.isShade() ? shadeFor(quad.getDirection()) : 1.0F;
		buf.putBulkData(pose, quad,
				new float[]{f, f, f, f},
				r, g, b, 1.0F,
				new int[]{0x00F000F0, 0x00F000F0, 0x00F000F0, 0x00F000F0},
				0, true);
	}

	private static float shadeFor(Direction d)
	{
		return switch (d)
		{
			case DOWN -> 0.5F;
			case UP -> 1.0F;
			case NORTH, SOUTH -> 0.8F;
			default -> 0.6F;
		};
	}

	// 邻居是否完全遮挡该面
	private static boolean isFullyOccluding(BlockState neighbor)
	{
		return neighbor.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}

	// 被完全遮挡方块不渲染
	private static boolean shouldRenderSide(LightweightBlockContainer c, int x, int y, int z,
											Direction side)
	{
		int nx = x + side.getStepX();
		int ny = y + side.getStepY();
		int nz = z + side.getStepZ();
		if (nx < 0 || ny < 0 || nz < 0
				|| nx >= c.getSizeX() || ny >= c.getSizeY() || nz >= c.getSizeZ())
		{
			// 建筑边缘：外面可见
			return true;
		}
		return !isFullyOccluding(c.get(nx, ny, nz));
	}

	// 无标准模型的方块：画灰色纯色立方体
	private static void emitFallbackCube(BufferBuilder buf, float ox, float oy, float oz)
	{
		float[][] faces = {
			{0,1,0, 1,1,0, 1,1,1, 0,1,1},
			{0,0,1, 1,0,1, 1,0,0, 0,0,0},
			{0,0,1, 1,0,1, 1,1,1, 0,1,1},
			{0,0,0, 0,1,0, 1,1,0, 1,0,0},
			{1,0,0, 1,1,0, 1,1,1, 1,0,1},
			{0,0,0, 0,0,1, 0,1,1, 0,1,0},
		};
		int color = 0xFF808080;
		for (float[] f : faces)
		{
			for (int i = 0; i < 4; i++)
			{
				buf.addVertex(f[i * 3] + ox, f[i * 3 + 1] + oy, f[i * 3 + 2] + oz)
						.setUv(0, 0).setColor(color);
			}
		}
	}
}

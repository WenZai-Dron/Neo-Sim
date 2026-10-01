package com.wenzai.neosim.client.gui;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import com.wenzai.neosim.client.preview.PreviewFrame;
import com.wenzai.neosim.schematic.LightweightBlockContainer;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SchematicFrame;
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
							  int centerPosX, int centerPosY, int size, float yawDeg, float pitchDeg)
	{
		if (schematic == null) return;
		CacheEntry entry = ensureMesh(schematic);
		if (entry == null) return;

		var poseStack = gfx.pose();
		poseStack.pushPose();

		// 3D模型放进GUI正交投影的深度范围
		poseStack.translate(centerPosX, centerPosY, 1050.0F);
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

		// 深度清空 + 绘制限定在面板矩形内（GL_SCISSOR），避免每帧全屏深度清空拖慢后续 GUI 元素
		int half = Math.max(8, size / 2);
		gfx.enableScissor(centerPosX - half, centerPosY - half, centerPosX + half, centerPosY + half);

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
		// 缓存键带上实例标识：自定义蓝图重载后名字不变、SchematicData 会换成新对象，
		// 只按名字做键会让 GUI 一直显示重载前的旧模型；帧也进键，.txt 与 .litematic 不会串
		String name = schematic.getName() + "#" + System.identityHashCode(schematic)
				+ "#" + schematic.getFormat();
		CacheEntry hit = CACHE.get(name);
		if (hit != null) return hit;

		LightweightBlockContainer container = schematic.getBlockContainer();
		int sx = container.getSizeX(), sy = container.getSizeY(), sz = container.getSizeZ();
		if (sx <= 0 || sy <= 0 || sz <= 0) return null;

		CacheEntry entry = new CacheEntry();

		// 缩略图画的是「蓝图帧」：.txt 原样，.litematic 转 1 次 + 镜像（见 SchematicFrame）
		BlueprintView view = new BlueprintView(schematic.frame());

		// 铺进蓝图帧时顺手重算连接性方块：栅栏/墙/玻璃板/铁栏杆的连接臂按帧内邻居算，
		// 否则 .txt 蓝图（只存方块 id + meta，映射出来四个连接臂全 false）在缩略图里是一根根孤立的柱子
		PreviewFrame.Framed framed = PreviewFrame.build(container, view::pos, view::state);
		entry.dimX = framed.sizeX();
		entry.dimY = framed.sizeY();
		entry.dimZ = framed.sizeZ();

		Minecraft mc = Minecraft.getInstance();
		BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
		BlockColors blockColors = mc.getBlockColors();
		RandomSource random = RandomSource.create();

		ByteBufferBuilder byteBuffer = new ByteBufferBuilder(1 << 20);
		BufferBuilder buf = new BufferBuilder(byteBuffer,
				VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

		MeshBuilder mesh = new MeshBuilder(buf, blockRenderer, blockColors, random, framed.container(),
				entry.dimX / 2.0F, entry.dimY / 2.0F, entry.dimZ / 2.0F);

		// 逐方块生成网格（坐标已在蓝图帧里，位置/朝向/连接臂都由 PreviewFrame 统一处理）
		for (int y = 0; y < entry.dimY; y++)
		{
			for (int z = 0; z < entry.dimZ; z++)
			{
				for (int x = 0; x < entry.dimX; x++)
				{
					mesh.addBlock(x, y, z);
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
					BlockPos local = markerEntry.getKey();
					BlockPos p = view.pos(local.getX(), local.getY(), local.getZ());
					mesh.addMarker(view.state(markerState),
							p.getX() - framed.min().getX(), p.getY() - framed.min().getY(),
							p.getZ() - framed.min().getZ());
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

	// 网格构建上下文：坐标已经在蓝图帧里，取方块与剔除邻居都直接用帧内坐标
	private static final class MeshBuilder
	{
		private final BufferBuilder buf;
		private final BlockRenderDispatcher blockRenderer;
		private final BlockColors blockColors;
		private final RandomSource random;
		private final LightweightBlockContainer container;
		private final PoseStack ps = new PoseStack();
		private final float cx, cy, cz;

		MeshBuilder(BufferBuilder buf, BlockRenderDispatcher blockRenderer,
					BlockColors blockColors, RandomSource random, LightweightBlockContainer container,
					float cx, float cy, float cz)
		{
			this.buf = buf;
			this.blockRenderer = blockRenderer;
			this.blockColors = blockColors;
			this.random = random;
			this.container = container;
			this.cx = cx;
			this.cy = cy;
			this.cz = cz;
		}

		// 渲染单个方块：缺模型时画灰色兜底立方体
		void addBlock(int x, int y, int z)
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
				// 越界＝建筑边缘，没被挡，照画
				if (isNeighborOpaque(container, x, y, z, side)) continue;
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

		// 特殊标记方块：坐标与朝向已过蓝图帧，这里只画
		void addMarker(BlockState state, int x, int y, int z)
		{
			BakedModel model = blockRenderer.getBlockModel(state);
			ps.setIdentity();
			ps.translate(x - cx, y - cy, z - cz);
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

	// 该方向的邻居是否把这一面完全挡住（越界＝建筑边缘，没被挡）
	private static boolean isNeighborOpaque(LightweightBlockContainer c, int x, int y, int z,
											Direction side)
	{
		int nx = x + side.getStepX();
		int ny = y + side.getStepY();
		int nz = z + side.getStepZ();
		if (nx < 0 || ny < 0 || nz < 0
				|| nx >= c.getSizeX() || ny >= c.getSizeY() || nz >= c.getSizeZ())
		{
			return false;
		}
		return isFullyOccluding(c.get(nx, ny, nz));
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

	// GUI 缩略图的坐标系：画的是「蓝图帧」，不是会建出来的世界朝向
	// .txt 的数据本来就在蓝图帧里（照原样画，与蓝图库一致）；
	// .litematic 的数据是照世界坐标导出的，照原样画会和 .txt 蓝图库差 90° + 镜像，
	// 所以先转进蓝图帧（= 转 1 次 + 镜像，见 SchematicFrame.toBlueprintView）
	private static final class BlueprintView
	{
		private final SchematicFrame frame;

		BlueprintView(SchematicFrame frame)
		{
			this.frame = frame;
		}

		BlockPos pos(int x, int y, int z)
		{
			return frame.toBlueprintView(x, y, z);
		}

		BlockState state(BlockState s)
		{
			return frame.toBlueprintViewState(s);
		}
	}
}

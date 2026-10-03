package com.wenzai.neosim.client.gui;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.wenzai.neosim.client.preview.BlueprintLevelView;
import com.wenzai.neosim.client.preview.OffsetVertexConsumer;
import com.wenzai.neosim.client.preview.PreviewFrame;
import com.wenzai.neosim.schematic.LightweightBlockContainer;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SchematicFrame;
import com.wenzai.neosim.schematic.SpecialMarker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL32C;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

// GUI内建筑3D预览渲染器
// 与世界幽灵预览同一条管线：按 RenderType 分层 —— 流体走原版液体渲染器（水/岩浆是水面而不是灰块）、
// 玻璃/彩色玻璃走 translucent 层（真半透明）、镂空方块走 cutout（alpha 裁剪）；
// 箱子/告示牌/床这类方块实体在缩略图里烘焙一次（静态，不做动画）。
// 差别只在坐标系：缩略图画的是「蓝图帧」，世界幽灵预览画的是「落地帧」。
public final class BuildingPreviewRenderer
{
	private static final Logger LOGGER = LogUtils.getLogger();

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

		Matrix4f mvForDet = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());
		Matrix4f prForDet = new Matrix4f(RenderSystem.getProjectionMatrix());
		float totalDet = mvForDet.determinant() * prForDet.determinant();
		RenderSystem.enableCull();
		GL32C.glFrontFace(totalDet >= 0.0F ? GL32C.GL_CCW : GL32C.GL_CW);

		Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
		modelView.mul(poseStack.last().pose());
		Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());

		Minecraft mc = Minecraft.getInstance();
		LightTexture light = mc.gameRenderer.lightTexture();

		// rendertype_* 会采样光照贴图（Sampler2）。世界渲染结束时调过 turnOffLightLayer()，
		// GUI 阶段 Sampler2 是 0 —— 不自己打开的话整块缩略图会全黑
		light.turnOnLightLayer();

		// 缩略图不吃雾
		float savedFogStart = RenderSystem.getShaderFogStart();
		RenderSystem.setShaderFogStart(Float.MAX_VALUE);

		try
		{
			// 缺模型兜底：老路径（POSITION_TEX_COLOR + 方块图集，不透明）
			if (entry.fallback != null)
			{
				RenderSystem.disableBlend();
				RenderSystem.depthMask(true);
				RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
				RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
				entry.fallback.bind();
				entry.fallback.drawWithShader(modelView, projection, RenderSystem.getShader());
			}

			// 每层用该层自己的 RenderType：混合 / 深度写 / alpha 裁剪 / 采样器都跟着它走
			for (Map.Entry<RenderType, VertexBuffer> layer : orderedLayers(entry.layers))
			{
				RenderType renderType = layer.getKey();
				renderType.setupRenderState();

				// GUI 的深度约定是 GEQUAL + clearDepth(0)（越大越近）；render type 会把它设成 LEQUAL，必须盖回来
				RenderSystem.depthFunc(GlConst.GL_GEQUAL);

				// 同一套反向深度下，render type 的 polygon offset (-1,-10) 会把面推向"远处"：
				// 告示牌文字正是用 polygon offset 贴在牌面上的，于是被自己的牌子挡住 → 看着"无字"。
				// 把偏移反过来（没开 polygon offset 的层调用它无副作用）
				RenderSystem.polygonOffset(1.0F, 10.0F);

				ShaderInstance shader = RenderSystem.getShader();
				if (shader == null)
				{
					renderType.clearRenderState();
					continue;
				}

				// 区块 shader 的 ChunkOffset 原版是逐区块设置并 apply 的：缩略图只有一个原点，先归零
				if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0.0F, 0.0F, 0.0F);

				layer.getValue().bind();
				layer.getValue().drawWithShader(modelView, projection, shader);

				renderType.clearRenderState();
			}
		}
		finally
		{
			RenderSystem.setShaderFogStart(savedFogStart);
			RenderSystem.depthMask(true);
			light.turnOffLightLayer();
		}

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

		LightweightBlockContainer blocks = framed.container();
		BlockPos min = framed.min();

		Minecraft mc = Minecraft.getInstance();
		BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
		BakedModel missingModel = mc.getModelManager().getMissingModel();
		Level level = mc.level;
		RandomSource random = RandomSource.create();

		// 渲染视图：缩略图没有真实世界，帧外一律当空气（面剔除只认蓝图自己的邻居）
		BlueprintLevelView levelView = new BlueprintLevelView(blocks);
		Map<BlockPos, BlockEntity> blockEntities = new LinkedHashMap<>();
		levelView.setBlockEntities(blockEntities);

		float cx = entry.dimX / 2.0F;
		float cy = entry.dimY / 2.0F;
		float cz = entry.dimZ / 2.0F;

		Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
		List<ByteBufferBuilder> pools = new ArrayList<>();

		PoseStack ps = new PoseStack();

		ByteBufferBuilder fallbackPool = new ByteBufferBuilder(1 << 12);
		BufferBuilder fallback = new BufferBuilder(fallbackPool,
				VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		int fallbackQuads = 0;

		// 蓝图里的方块实体数据：键是容器局部坐标，先按蓝图帧映射换算成帧内坐标
		Map<BlockPos, CompoundTag> framedTileEntities = new LinkedHashMap<>();
		Map<BlockPos, CompoundTag> tileEntities = schematic.getTileEntities();
		if (tileEntities != null)
		{
			for (Map.Entry<BlockPos, CompoundTag> e : tileEntities.entrySet())
			{
				BlockPos local = e.getKey();
				BlockPos p = view.pos(local.getX(), local.getY(), local.getZ());
				framedTileEntities.put(new BlockPos(p.getX() - min.getX(), p.getY() - min.getY(),
						p.getZ() - min.getZ()), e.getValue());
			}
		}

		for (int y = 0; y < entry.dimY; y++)
		{
			for (int z = 0; z < entry.dimZ; z++)
			{
				for (int x = 0; x < entry.dimX; x++)
				{
					BlockState state = blocks.get(x, y, z);
					if (state.isAir()) continue;

					BlockPos pos = new BlockPos(x, y, z);
					float ox = x - cx;
					float oy = y - cy;
					float oz = z - cz;

					// 流体（水/岩浆本体，以及含水方块的水面）：原版液体渲染器，水/岩浆本体因此是水面而不是灰块
					FluidState fluid = state.getFluidState();
					if (!fluid.isEmpty())
					{
						// 液体渲染器的顶点是按 (pos & 15) 写的：补回「以建筑中心为原点」这个模型空间位置
						VertexConsumer fluidOut = new OffsetVertexConsumer(
								layer(builders, pools, ItemBlockRenderTypes.getRenderLayer(fluid)),
								ox - (x & 15), oy - (y & 15), oz - (z & 15));
						blockRenderer.renderLiquid(pos, levelView, fluidOut, state, fluid);
					}

					// 方块实体：先造出来，BE 烘焙与 ModelData 都靠它
					if (level != null && state.hasBlockEntity())
					{
						BlockEntity be = createBlockEntity(state, pos, level);
						if (be != null)
						{
							loadBlockEntityData(be, framedTileEntities.get(pos), level);
							blockEntities.put(pos, be);
						}
					}

					// 纯液体方块没有方块模型，流体层已经画过了
					if (state.getRenderShape() == RenderShape.INVISIBLE) continue;

					// ENTITYBLOCK_ANIMATED（箱子/告示牌/旗帜/床/潜影盒/头颅…）：
					// 方块模型只有 particle、压根没有 elements（0 quad），只能交给方块实体渲染器
					if (state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED)
					{
						BlockEntity be = blockEntities.get(pos);
						if (be != null && mc.getBlockEntityRenderDispatcher().getRenderer(be) != null) continue;
					}

					BakedModel model = blockRenderer.getBlockModel(state);
					if (model == missingModel)
					{
						emitFallbackCube(fallback, ox, oy, oz);
						fallbackQuads++;
						continue;
					}

					ModelData modelData = model.getModelData(levelView, pos, state, levelView.getModelData(pos));
					random.setSeed(state.getSeed(pos));
					for (RenderType renderType : model.getRenderTypes(state, random, modelData))
					{
						ps.pushPose();
						ps.translate(ox, oy, oz);
						blockRenderer.renderBatched(state, pos, levelView, ps,
								layer(builders, pools, renderType), true, random, modelData, renderType);
						ps.popPose();
					}
				}
			}
		}

		// 特殊标记
		Map<BlockPos, SpecialMarker> markers = schematic.getSpecialMarkers();
		if (markers != null)
		{
			for (Map.Entry<BlockPos, SpecialMarker> e : markers.entrySet())
			{
				BlockState markerState = e.getValue().toBlockState();
				if (markerState == null) continue;

				BlockPos local = e.getKey();
				BlockPos p = view.pos(local.getX(), local.getY(), local.getZ());
				BlockPos frameLocal = new BlockPos(p.getX() - min.getX(), p.getY() - min.getY(), p.getZ() - min.getZ());
				float ox = frameLocal.getX() - cx;
				float oy = frameLocal.getY() - cy;
				float oz = frameLocal.getZ() - cz;

				BakedModel model = blockRenderer.getBlockModel(markerState);
				if (model == missingModel) continue;

				ModelData modelData = model.getModelData(levelView, frameLocal, markerState,
						levelView.getModelData(frameLocal));
				random.setSeed(markerState.getSeed(frameLocal));
				for (RenderType renderType : model.getRenderTypes(markerState, random, modelData))
				{
					ps.pushPose();
					ps.translate(ox, oy, oz);
					blockRenderer.renderBatched(markerState, frameLocal, levelView, ps,
							layer(builders, pools, renderType), true, random, modelData, renderType);
					ps.popPose();
				}
			}
		}

		// 方块实体烘焙一次：缩略图是静态的，partialTick 给 0，不做动画
		if (!blockEntities.isEmpty())
		{
			BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
			MultiBufferSource capture = renderType -> layer(builders, pools, renderType);
			for (Map.Entry<BlockPos, BlockEntity> e : blockEntities.entrySet())
			{
				BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(e.getValue());
				if (renderer == null) continue;

				BlockPos pos = e.getKey();
				ps.pushPose();
				ps.translate(pos.getX() - cx, pos.getY() - cy, pos.getZ() - cz);
				renderer.render(e.getValue(), 0.0F, ps, capture,
						LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
				ps.popPose();
			}

			// 烘焙完就不再需要这些实例了
			for (BlockEntity be : blockEntities.values())
			{
				be.setRemoved();
			}
			blockEntities.clear();
		}

		// 每个 RenderType 一份顶点缓冲
		for (Map.Entry<RenderType, BufferBuilder> e : builders.entrySet())
		{
			MeshData mesh = e.getValue().build();
			if (mesh == null) continue;

			VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
			vb.bind();
			vb.upload(mesh);
			entry.layers.put(e.getKey(), vb);
		}
		for (ByteBufferBuilder pool : pools) pool.close();

		if (fallbackQuads > 0)
		{
			MeshData mesh = fallback.build();
			if (mesh != null)
			{
				VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
				vb.bind();
				vb.upload(mesh);
				entry.fallback = vb;
			}
		}
		fallbackPool.close();

		CACHE.put(name, entry);
		return entry;
	}

	// 原版区块层的顺序（solid → cutoutMipped → cutout → translucent → tripwire）先画，
	// 实体层（箱子/告示牌用的图集）排在后面：不透明先画、半透明后画，混合结果才稳定
	private static List<Map.Entry<RenderType, VertexBuffer>> orderedLayers(Map<RenderType, VertexBuffer> map)
	{
		List<RenderType> chunkOrder = RenderType.chunkBufferLayers();
		List<Map.Entry<RenderType, VertexBuffer>> ordered = new ArrayList<>(map.size());
		for (RenderType renderType : chunkOrder)
		{
			VertexBuffer buffer = map.get(renderType);
			if (buffer != null) ordered.add(Map.entry(renderType, buffer));
		}
		for (Map.Entry<RenderType, VertexBuffer> entry : map.entrySet())
		{
			if (!chunkOrder.contains(entry.getKey())) ordered.add(entry);
		}
		return ordered;
	}

	// 取（或开）某个 RenderType 的构建缓冲：顶点格式与绘制模式都跟着该层自己走
	private static BufferBuilder layer(Map<RenderType, BufferBuilder> builders,
									   List<ByteBufferBuilder> pools,
									   RenderType renderType)
	{
		BufferBuilder existing = builders.get(renderType);
		if (existing != null) return existing;

		ByteBufferBuilder pool = new ByteBufferBuilder(Math.max(1 << 12, renderType.bufferSize()));
		pools.add(pool);
		BufferBuilder builder = new BufferBuilder(pool, renderType.mode(), renderType.format());
		builders.put(renderType, builder);
		return builder;
	}

	// 造一个缩略图用的方块实体：位置与状态就是帧内那一格
	@Nullable
	private static BlockEntity createBlockEntity(BlockState state, BlockPos pos, Level level)
	{
		if (!(state.getBlock() instanceof EntityBlock entityBlock)) return null;

		BlockEntity be;
		try
		{
			be = entityBlock.newBlockEntity(pos, state);
		}
		catch (Throwable t)
		{
			return null;
		}
		if (be == null) return null;

		// setLevel 要的是 Level：缩略图没有虚拟世界，用真实世界顶上（BER 基本只读自身字段/NBT）
		be.setLevel(level);
		return be;
	}

	// 蓝图里的方块实体数据：键已换算成帧内坐标
	private static void loadBlockEntityData(BlockEntity be, @Nullable CompoundTag stored, Level level)
	{
		// 诊断：告示牌文字到底有没有被装上（一次缩略图构建只打一遍）
		boolean isSign = be instanceof SignBlockEntity;
		if (isSign)
		{
			LOGGER.info("NeoSim-BuildingPreviewRenderer: sign BE at {} storedNbt={} keys={}",
					be.getBlockPos(), stored != null, stored != null ? stored.getAllKeys() : "[]");
		}

		if (stored == null) return;

		CompoundTag tag = stored.copy();
		BlockPos pos = be.getBlockPos();
		tag.putInt("x", pos.getX());
		tag.putInt("y", pos.getY());
		tag.putInt("z", pos.getZ());

		try
		{
			be.loadWithComponents(tag, level.registryAccess());
			if (be instanceof SignBlockEntity sign)
			{
				LOGGER.info("NeoSim-BuildingPreviewRenderer: sign at {} line0='{}' frontText={}",
						be.getBlockPos(), sign.getFrontText().getMessage(0, false).getString(),
						sign.getFrontText());
			}
		}
		catch (Throwable t)
		{
			// 单个方块实体的数据坏了不该拖垮缩略图
			LOGGER.warn("NeoSim-BuildingPreviewRenderer: failed to load block entity data at {} — {}", be.getBlockPos(), t.toString());
		}
	}

	// 无标准模型的方块：画灰色纯色立方体（沿用老路径，放进 POSITION_TEX_COLOR 那份缓冲）
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

	// 单个蓝图的VBO缓存项：每个 RenderType 一份 + 缺模型兜底一份
	private static final class CacheEntry
	{
		final Map<RenderType, VertexBuffer> layers = new LinkedHashMap<>();
		@Nullable
		VertexBuffer fallback;
		int dimX, dimY, dimZ;

		void close()
		{
			for (VertexBuffer vb : layers.values())
			{
				vb.close();
			}
			layers.clear();

			if (fallback != null)
			{
				fallback.close();
				fallback = null;
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

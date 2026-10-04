package com.wenzai.neosim.client.preview;

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
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.client.ui.UiSettings;
import com.wenzai.neosim.client.ui.WhiteTexture;
import com.wenzai.neosim.schematic.*;
import net.minecraft.client.Minecraft;
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
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

// 预览渲染钩子：VBO缓存内嵌于此
// 每个 RenderType 一份 VBO（原版区块网格就是这么分层的）：方块与流体的 shader / 采样器 / 混合 / 剔除 /
// 自发光 / 独立图集全部由 RenderType 自己决定，模组注册的层因此天然生效；
// 「纯色层 + 贴图层」两 pass 与分摊比例与「界面 → 投影预览」页的示意保持一致。
// 方块实体（箱子/告示牌/旗帜/床/潜影盒/模组机器…）走真正的 BlockEntityRenderer，每帧重画，不进静态 VBO。
@EventBusSubscriber(modid = NeoSim.MOD_ID, value = Dist.CLIENT)
public class GhostBlockRenderer
{
	// 工具类：只有静态成员，禁止实例化
	private GhostBlockRenderer()
	{
	}

	private static final Logger LOGGER = LogUtils.getLogger();
	private static BlockPos lastLoggedOrigin = null;

	// 预览外观的实际生效值：变化时打一行，用来确认渲染器到底拿到了什么
	private static int lastSettingsTint = Integer.MIN_VALUE;
	private static int lastSettingsAlpha = Integer.MIN_VALUE;
	private static double lastSettingsMix = Double.NaN;

	@SubscribeEvent
	public static void onRenderLevelStage(RenderLevelStageEvent event)
	{
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

		SchematicPreviewManager mgr = SchematicPreviewManager.getInstance();
		ClientPreviewState state = mgr.getState();
		if (!state.isActive() || state.getSchematic() == null) return;

		// 模盒被破坏则取消预览
		Minecraft mc = Minecraft.getInstance();
		BlockPos conPos = mgr.getConstructorPos();
		if (conPos != null && mc.level != null)
		{
			if (!(mc.level.getBlockState(conPos).getBlock() instanceof com.wenzai.neosim.block.BuildingConstructor))
			{
				mgr.cancelPreview();
				return;
			}
		}

		BlockPos origin = state.getOrigin();
		if (!origin.equals(lastLoggedOrigin))
		{
			LOGGER.info("NeoSim-GhostBlockRenderer: origin=({}, {}, {})", origin.getX(), origin.getY(), origin.getZ());
			lastLoggedOrigin = origin;
		}

		// 预览状态变化时重建VBO
		GhostMeshCache cache = state.getMeshCache();
		if (state.needsRebuild() || !cache.isValid(state))
		{
			cache.rebuild(state);
			state.clearNeedsRebuild();
		}

		Vec3 cam = event.getCamera().getPosition();

		// 模型视图
		Matrix4f pose = new Matrix4f(event.getModelViewMatrix());
		pose.translate((float) (origin.getX() - cam.x),
				(float) (origin.getY() - cam.y),
				(float) (origin.getZ() - cam.z));

		UiSettings.Preview settings = UiSettings.preview();
		logSettings(settings);

		cache.render(pose, event.getProjectionMatrix(), settings, origin,
				event.getPartialTick().getGameTimeDeltaPartialTick(false));
	}

	// 预览外观变化时打一行：alpha=255 才是全不透明（128/255 ≈ 50%）
	private static void logSettings(UiSettings.Preview settings)
	{
		if (settings.tint == lastSettingsTint && settings.alpha == lastSettingsAlpha
				&& settings.textureMix == lastSettingsMix) return;

		lastSettingsTint = settings.tint;
		lastSettingsAlpha = settings.alpha;
		lastSettingsMix = settings.textureMix;
		LOGGER.info("NeoSim-GhostBlockRenderer: preview settings tint=#{}, alpha={}/255, textureMix={}",
				String.format("%06X", settings.tint & 0xFFFFFF), settings.alpha, settings.textureMix);
	}

	// VBO缓存：仅在预览状态变化时重建
	static class GhostMeshCache
	{
		// 每个 RenderType 一份顶点缓冲（solid / cutoutMipped / cutout / translucent / tripwire / 模组自定义层）
		private final Map<RenderType, VertexBuffer> layers = new LinkedHashMap<>();

		// 兜底立方体（缺模型方块）：没有可用的烘焙模型，仍走老的 POSITION_TEX_COLOR 路径
		@Nullable
		private VertexBuffer fallbackBuffer;

		// 方块实体：预览自己造的一份（不属于真实世界），静态网格的 ModelData 与 BE 渲染共用
		private final Map<BlockPos, BlockEntity> blockEntities = new LinkedHashMap<>();

		// BE 每帧重画，顶点缓冲按 RenderType 复用（DYNAMIC），不参与静态缓存
		private final Map<RenderType, VertexBuffer> blockEntityBuffers = new LinkedHashMap<>();

		// 落地帧在世界里的最小角：BE 的顶点存的是相对 origin 的局部坐标，渲染时要用帧原点换算
		private BlockPos frameMin = BlockPos.ZERO;

		@Nullable
		private Level lastLevel;

		private int lastRotationOrdinal;
		private int lastMirrorOrdinal;
		private SchematicData lastSchematic;

		// 缓存是否仍有效（顶点存为相对 origin 的局部坐标，origin 变化不重建，渲染时每帧用当前 origin 平移矩阵）
		public boolean isValid(PreviewState state)
		{
			SchematicData s = state.getSchematic();
			if (s == null) return false;

			// 蓝图重载后名字不变但对象会换新：只比名字会一直画旧网格
			if (s != lastSchematic) return false;

			// 换世界/换维度：方块实体挂的是旧的 Level，必须重建
			if (Minecraft.getInstance().level != lastLevel) return false;

			if (state.getRotation().ordinal() != lastRotationOrdinal) return false;
			if (state.getMirror().ordinal() != lastMirrorOrdinal) return false;
			return true;
		}

		// 重建VBO
		public void rebuild(PreviewState state)
		{
			invalidate();

			SchematicData schematic = state.getSchematic();
			LightweightBlockContainer container = schematic.getBlockContainer();
			Minecraft mc = Minecraft.getInstance();
			BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
			BlockEntityRenderDispatcher blockEntityRenderer = mc.getBlockEntityRenderDispatcher();
			BakedModel missingModel = mc.getModelManager().getMissingModel();
			Level level = mc.level;
			BlockPos origin = state.getOrigin();

			// 朝向与位置都走同一条落地链（BlueprintPlacement）：铺进落地帧时顺手重算连接性方块，
			// 否则 .txt 蓝图（只存方块 id + meta）的栅栏/墙/玻璃板在幽灵预览里是断开的；
			// 帧外与蓝图空气格读真实世界——建造不清场，蓝图边缘的连接臂本来就是连到旁边既有方块的
			BlueprintPlacement placement = state.placement();
			PreviewFrame.Framed framed = PreviewFrame.build(container, placement::pos, placement::state, level);
			LightweightBlockContainer blocks = framed.container();
			BlockPos min = framed.min();
			frameMin = min;

			// 预览视图：AO 邻居、顶点光照贴图、生物群系染色、ModelData 都按「帧内坐标 → 蓝图/真实世界」取；
			// 但面剔除只认蓝图自己 —— 帧外当空气，否则落点周围的地形会把幽灵低于地表那几层的面剔掉
			BlueprintLevelView view = new BlueprintLevelView(blocks, min, level);
			view.setBlockEntities(blockEntities);
			view.setOutsideIsAir(true);

			// 蓝图里的方块实体数据：键是容器局部坐标，先按落地链换算成帧内坐标
			// （与下面的特殊标记同一套换算；.litematic 走世界帧，局部坐标和帧内坐标差着旋转/镜像/平移）
			Map<BlockPos, CompoundTag> tileEntities = schematic.getTileEntities();
			Map<BlockPos, CompoundTag> framedTileEntities = new LinkedHashMap<>();
			if (tileEntities != null)
			{
				for (Map.Entry<BlockPos, CompoundTag> entry : tileEntities.entrySet())
				{
					BlockPos local = entry.getKey();
					BlockPos world = placement.pos(local.getX(), local.getY(), local.getZ());
					framedTileEntities.put(new BlockPos(world.getX() - min.getX(),
							world.getY() - min.getY(), world.getZ() - min.getZ()), entry.getValue());
				}
			}

			Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
			List<ByteBufferBuilder> pools = new ArrayList<>();

			PoseStack ps = new PoseStack();
			RandomSource random = RandomSource.create();

			// 兜底立方体单开一份缓冲：顶点格式与方块层不同（无光照贴图/法线）
			ByteBufferBuilder fallbackPool = new ByteBufferBuilder(1 << 12);
			BufferBuilder fallback = new BufferBuilder(fallbackPool,
					VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
			int fallbackQuads = 0;

			for (int y = 0; y < framed.sizeY(); y++)
			{
				for (int z = 0; z < framed.sizeZ(); z++)
				{
					for (int x = 0; x < framed.sizeX(); x++)
					{
						BlockState blockState = blocks.get(x, y, z);
						if (blockState.isAir()) continue;

						// 帧内坐标：AO / 光照 / 染色 / 方块实体都按这个坐标取
						BlockPos pos = new BlockPos(x, y, z);

						// 顶点存为相对origin的坐标
						float wx = min.getX() + x - origin.getX();
						float wy = min.getY() + y - origin.getY();
						float wz = min.getZ() + z - origin.getZ();

						// 流体（水/岩浆本体，以及含水方块的水面）交给原版液体渲染器，
						// 它按流体类型选自己的 RenderType 层（水是半透明层）
						FluidState fluid = blockState.getFluidState();
						if (!fluid.isEmpty())
						{
							// 液体渲染器的顶点是按 (pos & 15) 写的：补回「帧内坐标 - origin」这个模型空间位置
							VertexConsumer fluidOut = new OffsetVertexConsumer(
									layer(builders, pools, ItemBlockRenderTypes.getRenderLayer(fluid)),
									wx - (x & 15), wy - (y & 15), wz - (z & 15));
							blockRenderer.renderLiquid(pos, view, fluidOut, blockState, fluid);
						}

						// 方块实体：先造出来，BE 渲染与 ModelData 都靠它
						if (level != null && blockState.hasBlockEntity())
						{
							BlockEntity be = createBlockEntity(blockState, pos, level);
							if (be != null)
							{
								loadBlockEntityData(be, framedTileEntities, pos, min, level);
								blockEntities.put(pos, be);
							}
						}

						// 纯液体方块没有方块模型，流体层已经画过了
						if (blockState.getRenderShape() == RenderShape.INVISIBLE) continue;

						// ENTITYBLOCK_ANIMATED（箱子/告示牌/旗帜/床/潜影盒/头颅/装饰罐…）：
						// 原版区块网格不画，整块交给方块实体渲染器；有渲染器时不再叠静态模型
						if (blockState.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED)
						{
							BlockEntity be = blockEntities.get(pos);
							if (be != null && blockEntityRenderer != null
									&& blockEntityRenderer.getRenderer(be) != null) continue;
						}

						BakedModel model = blockRenderer.getBlockModel(blockState);

						// 缺失模型：先用纯色立方体兜底
						if (model == missingModel)
						{
							emitFallbackCube(fallback, wx, wy, wz);
							fallbackQuads++;
							continue;
						}

						// 模型自己的 ModelData：先取方块实体那份（NeoForge 钩子），再让模型补
						ModelData modelData = model.getModelData(view, pos, blockState, view.getModelData(pos));
						random.setSeed(blockState.getSeed(pos));

						// 模型自己声明用哪些 RenderType：模组自定义层 / 自发光 / 独立图集都从这一步带进来
						for (RenderType renderType : model.getRenderTypes(blockState, random, modelData))
						{
							ps.pushPose();
							ps.translate(wx, wy, wz);

							// checkSides=true：按预览视图里的邻居剔除被挡住的面（与原版区块网格一致）
							blockRenderer.renderBatched(blockState, pos, view, ps,
									layer(builders, pools, renderType), true, random, modelData, renderType);
							ps.popPose();
						}
					}
				}
			}

			// 特殊标记位置
			Map<BlockPos, SpecialMarker> markers = schematic.getSpecialMarkers();
			if (markers != null)
			{
				for (Map.Entry<BlockPos, SpecialMarker> entry : markers.entrySet())
				{
					BlockState markerState = entry.getValue().toBlockState();
					if (markerState == null) continue;

					BlockPos local = entry.getKey();
					BlockPos world = placement.pos(local.getX(), local.getY(), local.getZ());
					BlockPos frameLocal = new BlockPos(world.getX() - min.getX(),
							world.getY() - min.getY(), world.getZ() - min.getZ());
					float wx = world.getX() - origin.getX();
					float wy = world.getY() - origin.getY();
					float wz = world.getZ() - origin.getZ();

					BakedModel model = blockRenderer.getBlockModel(markerState);
					if (model == missingModel) continue;

					ModelData modelData = model.getModelData(view, frameLocal, markerState, view.getModelData(frameLocal));
					random.setSeed(markerState.getSeed(frameLocal));
					for (RenderType renderType : model.getRenderTypes(markerState, random, modelData))
					{
						ps.pushPose();
						ps.translate(wx, wy, wz);
						blockRenderer.renderBatched(markerState, frameLocal, view, ps,
								layer(builders, pools, renderType), true, random, modelData, renderType);
						ps.popPose();
					}
				}
			}

			// 每层 build 一次并上传成独立的静态 VBO
			for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet())
			{
				MeshData mesh = entry.getValue().build();
				if (mesh == null) continue;

				VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
				vb.bind();
				vb.upload(mesh);
				layers.put(entry.getKey(), vb);
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
					fallbackBuffer = vb;
				}
			}
			fallbackPool.close();

			lastRotationOrdinal = state.getRotation().ordinal();
			lastMirrorOrdinal = state.getMirror().ordinal();
			lastSchematic = schematic;
			lastLevel = level;
		}

		// 每帧绘制缓存的VBO：每个 RenderType 一层，"纯色层 + 贴图层"两 pass 按「纹理显示度」分摊透明度
		public void render(Matrix4f modelView, Matrix4f projection, UiSettings.Preview settings,
						   BlockPos origin, float partialTick)
		{
			if (layers.isEmpty() && fallbackBuffer == null && blockEntities.isEmpty()) return;

			double mix = Mth.clamp(settings.textureMix, 0.0D, 1.0D);
			float alpha = Mth.clamp(settings.alpha, 0, 255) / 255.0F;
			float red = ((settings.tint >> 16) & 0xFF) / 255.0F;
			float green = ((settings.tint >> 8) & 0xFF) / 255.0F;
			float blue = (settings.tint & 0xFF) / 255.0F;

			if (alpha <= 0.0F) return;

			RenderSystem.enableDepthTest();

			// 幽灵必须写深度：不写的话幽灵自己各层之间没有遮挡，谁后画谁赢 ——
			// 而网格是 y 升序追加的，上面的面会把下面的面盖掉，从上方往下看时
			// "低于玩家的那几层"就会整片消失、露出背后的东西（alpha 拉满时尤其明显）。
			RenderSystem.depthMask(true);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();

			// 幽灵不吃雾：rendertype_* 自带雾，雾色≈天空色，被雾一染背对天空就看着像"变透明"；
			// 而且雾距离在 vsh 里按 Position+ChunkOffset 算，我们的顶点是 origin 相对坐标、
			// ChunkOffset 又归零，算出来的不是相机距离（大蓝图 / 低视距 / 雾生物群系下会莫名起雾）。
			// 预览不需要雾，画完还原。
			float savedFogStart = RenderSystem.getShaderFogStart();
			RenderSystem.setShaderFogStart(Float.MAX_VALUE);

			try
			{
				// 兜底立方体：老路径（POSITION_TEX_COLOR + 方块图集），两 pass 与方块层同一套语义
				if (fallbackBuffer != null)
				{
					if (mix < 0.999D)
					{
						drawFallback(fallbackBuffer, modelView, projection, WhiteTexture.location(),
								red, green, blue, (float) (alpha * (1.0D - mix)));
					}
					if (mix > 0.001D)
					{
						drawFallback(fallbackBuffer, modelView, projection, TextureAtlas.LOCATION_BLOCKS,
								red, green, blue, (float) (alpha * mix));
					}
				}

				// 静态方块层：不透明先画、半透明后画，每层用该层自己的 RenderType
				for (Map.Entry<RenderType, VertexBuffer> entry : orderedLayers(layers))
				{
					if (mix < 0.999D)
					{
						drawLayer(entry.getValue(), entry.getKey(), modelView, projection,
								WhiteTexture.location(), red, green, blue, (float) (alpha * (1.0D - mix)));
					}
					if (mix > 0.001D)
					{
						drawLayer(entry.getValue(), entry.getKey(), modelView, projection,
								null, red, green, blue, (float) (alpha * mix));
					}
				}

				// 方块实体：必须每帧重新生成（箱子盖/告示牌/潜影盒都会动）
				drawBlockEntities(modelView, projection, origin, partialTick,
						mix, alpha, red, green, blue);
			}
			finally
			{
				RenderSystem.setShaderFogStart(savedFogStart);

				// setShaderColor 是全局状态：不复位会污染之后的世界与 GUI 渲染
				RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
				RenderSystem.depthMask(true);
				RenderSystem.disableBlend();

				// 原版区块层画完也会解绑：别把预览的 VAO 留给后面的实体/粒子渲染
				VertexBuffer.unbind();
			}
		}

		// 方块实体：每帧重新生成几何、逐 RenderType 上传到复用的 DYNAMIC 缓冲，
		// 再用与方块层同一条两 pass 路径画（这样染色/透明度/纹理显示度对 BE 一样生效）
		private void drawBlockEntities(Matrix4f modelView, Matrix4f projection, BlockPos origin,
									   float partialTick, double mix,
									   float alpha, float red, float green, float blue)
		{
			if (blockEntities.isEmpty()) return;

			Minecraft mc = Minecraft.getInstance();
			BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
			if (dispatcher == null) return;

			Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
			List<ByteBufferBuilder> pools = new ArrayList<>();

			// BER 要哪层就给哪层的构建缓冲
			MultiBufferSource capture = renderType -> layer(builders, pools, renderType);

			PoseStack ps = new PoseStack();
			for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet())
			{
				BlockEntity be = entry.getValue();
				BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(be);
				if (renderer == null) continue;

				// 与静态方块同坐标系：顶点存相对 origin 的局部坐标
				BlockPos local = entry.getKey();
				ps.pushPose();
				ps.translate(local.getX() + frameMin.getX() - origin.getX(),
						local.getY() + frameMin.getY() - origin.getY(),
						local.getZ() + frameMin.getZ() - origin.getZ());
				renderer.render(be, partialTick, ps, capture, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
				ps.popPose();
			}

			// 本帧真正用到的层：复用缓冲，重新 upload
			Map<RenderType, VertexBuffer> frame = new LinkedHashMap<>();
			for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet())
			{
				MeshData mesh = entry.getValue().build();
				if (mesh == null) continue;

				RenderType renderType = entry.getKey();
				VertexBuffer buffer = blockEntityBuffers.get(renderType);
				if (buffer == null)
				{
					buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
					blockEntityBuffers.put(renderType, buffer);
				}

				buffer.bind();
				buffer.upload(mesh);
				frame.put(renderType, buffer);
			}
			for (ByteBufferBuilder pool : pools) pool.close();

			// 上一帧有、这一帧没有的层：缓冲留着没意义，关掉免得占显存
			Iterator<Map.Entry<RenderType, VertexBuffer>> stale = blockEntityBuffers.entrySet().iterator();
			while (stale.hasNext())
			{
				Map.Entry<RenderType, VertexBuffer> entry = stale.next();
				if (!frame.containsKey(entry.getKey()))
				{
					entry.getValue().close();
					stale.remove();
				}
			}

			for (Map.Entry<RenderType, VertexBuffer> entry : orderedLayers(frame))
			{
				if (mix < 0.999D)
				{
					drawLayer(entry.getValue(), entry.getKey(), modelView, projection,
							WhiteTexture.location(), red, green, blue, (float) (alpha * (1.0D - mix)));
				}
				if (mix > 0.001D)
				{
					drawLayer(entry.getValue(), entry.getKey(), modelView, projection,
							null, red, green, blue, (float) (alpha * mix));
				}
			}
		}

		// 原版区块层的顺序（solid → cutoutMipped → cutout → translucent → tripwire）先画，
		// 模组自定义层与 BE 用的实体层排在后面：不透明先画、半透明后画，混合结果才稳定
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

		// 单层单 pass：RenderType 决定 shader / 采样器 / 混合 / 剔除 / 光照贴图，预览只覆盖深度写、混合与染色
		private static void drawLayer(VertexBuffer buffer, RenderType renderType,
									  Matrix4f modelView, Matrix4f projection,
									  @Nullable ResourceLocation textureOverride,
									  float red, float green, float blue, float alpha)
		{
			if (alpha <= 0.0F) return;

			renderType.setupRenderState();

			// 预览统一：开深度测试、开深度写、开混合（覆盖各层自己的设置）
			// 深度写必须开：幽灵自己各层之间要靠深度排序，光靠绘制顺序（y 升序）会互相盖掉
			RenderSystem.enableDepthTest();
			RenderSystem.depthMask(true);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();

			// 纯色层：把该层要采样的图集换成 1×1 纯白，剩下的正好是染色 × 透明度
			if (textureOverride != null) RenderSystem.setShaderTexture(0, textureOverride);
			RenderSystem.setShaderColor(red, green, blue, alpha);

			ShaderInstance shader = RenderSystem.getShader();
			if (shader == null)
			{
				renderType.clearRenderState();
				return;
			}

			// 区块 shader 的 ChunkOffset 原版是逐区块设置并 apply 的：预览只有一个原点，先归零
			if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0.0F, 0.0F, 0.0F);

			buffer.bind();
			buffer.drawWithShader(modelView, projection, shader);

			renderType.clearRenderState();
		}

		// 兜底立方体：沿用老路径的 position_tex_color shader，纹理由调用方给（纯白 / 方块图集）
		private static void drawFallback(VertexBuffer buffer, Matrix4f modelView, Matrix4f projection,
										 ResourceLocation texture,
										 float red, float green, float blue, float alpha)
		{
			if (alpha <= 0.0F) return;

			RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
			RenderSystem.setShaderTexture(0, texture);
			RenderSystem.setShaderColor(red, green, blue, alpha);
			buffer.bind();
			buffer.drawWithShader(modelView, projection, RenderSystem.getShader());
		}

		// 预览结束/失效时释放显存
		public void invalidate()
		{
			for (VertexBuffer buffer : layers.values())
			{
				buffer.close();
			}
			layers.clear();

			if (fallbackBuffer != null)
			{
				fallbackBuffer.close();
				fallbackBuffer = null;
			}

			// 预览的方块实体不属于真实世界：丢掉前标记 removed，别让模组侧留着活引用
			for (BlockEntity be : blockEntities.values())
			{
				be.setRemoved();
			}
			blockEntities.clear();

			for (VertexBuffer buffer : blockEntityBuffers.values())
			{
				buffer.close();
			}
			blockEntityBuffers.clear();

			lastSchematic = null;
			lastLevel = null;
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

		// 造一个预览用的方块实体：位置与状态就是帧内那一格（BER 读到的就是蓝图里的朝向）
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
				// 模组方块实体构造炸了不该拖垮预览：这一格退回静态模型
				LOGGER.warn("NeoSim-GhostBlockRenderer: failed to create block entity at {} — {}", pos, t.toString());
				return null;
			}
			if (be == null) return null;

			// setLevel 要的是 Level：预览没有自己的虚拟 Level，先用真实世界顶上。
			// BER 读自身字段/NBT、getLevel().getGameTime() 都没问题；
			// 少数按 getLevel().getBlockState(自 pos) 读世界的模组机器会取到真实世界的错误位置（已知取舍）
			be.setLevel(level);
			return be;
		}

		// 蓝图里的方块实体数据：键已经换算成帧内坐标（读取器统一成容器局部坐标，外面再走一遍落地链）
		private static void loadBlockEntityData(BlockEntity be, Map<BlockPos, CompoundTag> framedTileEntities,
												BlockPos pos, BlockPos min, Level level)
		{
			CompoundTag stored = framedTileEntities.get(pos);
			if (stored == null) return;

			CompoundTag tag = stored.copy();

			// x/y/z 改成本次预览的世界落点：BE 的坐标与它实际所在位置一致
			BlockPos world = min.offset(pos);
			tag.putInt("x", world.getX());
			tag.putInt("y", world.getY());
			tag.putInt("z", world.getZ());

			try
			{
				be.loadWithComponents(tag, level.registryAccess());
				if (be instanceof SignBlockEntity sign)
				{
					LOGGER.info("NeoSim-GhostBlockRenderer: sign at {} line0='{}'",
							pos, sign.getFrontText().getMessage(0, false).getString());
				}
			}
			catch (Throwable t)
			{
				LOGGER.warn("NeoSim-GhostBlockRenderer: failed to load block entity data at {} — {}", pos, t.toString());
			}
		}

		// 无标准模型方块：画纯色立方体（采样方块图集白色纹理，alpha 混合下可见）
		private static void emitFallbackCube(BufferBuilder buf, float ox, float oy, float oz)
		{
			TextureAtlasSprite white = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
					.apply(ResourceLocation.withDefaultNamespace("block/white_concrete"));
			float u0 = white.getU0();
			float v0 = white.getV0();
			float u1 = white.getU1();
			float v1 = white.getV1();

			// 6个面，每面4顶点
			float[][] faces = {
				{0,1,0, 1,1,0, 1,1,1, 0,1,1},
				{0,0,1, 1,0,1, 1,0,0, 0,0,0},
				{0,0,1, 1,0,1, 1,1,1, 0,1,1},
				{0,0,0, 0,1,0, 1,1,0, 1,0,0},
				{1,0,0, 1,1,0, 1,1,1, 1,0,1},
				{0,0,0, 0,0,1, 0,1,1, 0,1,0},
			};
			for (float[] f : faces)
			{
				for (int i = 0; i < 4; i++)
				{
					float vx = f[i * 3] + ox;
					float vy = f[i * 3 + 1] + oy;
					float vz = f[i * 3 + 2] + oz;

					// 面内 UV 对角展开，避免整面采样单点
					float u = (i == 1 || i == 2) ? u1 : u0;
					float v = (i >= 2) ? v1 : v0;
					buf.addVertex(vx, vy, vz).setUv(u, v).setColor(0xFFFFFFFF);
				}
			}
		}
	}
}

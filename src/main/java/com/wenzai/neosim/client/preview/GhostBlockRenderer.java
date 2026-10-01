package com.wenzai.neosim.client.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.client.ui.UiSettings;
import com.wenzai.neosim.client.ui.WhiteTexture;
import com.wenzai.neosim.schematic.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.Map;

// 预览渲染钩子：VBO缓存内嵌于此
@EventBusSubscriber(modid = NeoSim.MOD_ID, value = Dist.CLIENT)
public class GhostBlockRenderer
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static BlockPos lastLoggedOrigin = null;

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

		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();

		// 直接绘制缓存的VBO（染色 / 透明度 / 纹理显示度都是 uniform，拖滑块不需要重建网格）
		cache.render(pose, event.getProjectionMatrix(), UiSettings.preview());

		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
	}

	// VBO缓存：仅在预览状态变化时重建
	static class GhostMeshCache
	{
		private VertexBuffer vertexBuffer;
		private int lastRotationOrdinal;
		private int lastMirrorOrdinal;
		private String lastSchematicName;
		private SchematicData lastSchematic;

		// 缓存是否仍有效（顶点存为相对 origin 的局部坐标，origin 变化不重建，渲染时每帧用当前 origin 平移矩阵）
		public boolean isValid(PreviewState state)
		{
			if (vertexBuffer == null) return false;
			SchematicData s = state.getSchematic();
			if (s == null) return false;

			// 蓝图重载后名字不变但对象会换新：只比名字会一直画旧网格
			if (s != lastSchematic) return false;
			if (state.getRotation().ordinal() != lastRotationOrdinal) return false;
			if (state.getMirror().ordinal() != lastMirrorOrdinal) return false;
			return true;
		}

		// 重建VBO
		public void rebuild(PreviewState state)
		{
			if (vertexBuffer != null)
			{
				vertexBuffer.close();
				vertexBuffer = null;
			}

			SchematicData schematic = state.getSchematic();
			LightweightBlockContainer container = schematic.getBlockContainer();
			BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();

			ByteBufferBuilder byteBuffer = new ByteBufferBuilder(1 << 20);
			BufferBuilder buf = new BufferBuilder(byteBuffer,
					VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

			// 顶点色恒为不透明白：真正的染色与透明度由渲染时的 ColorModulator 给，
			// 颜色进网格的话每改一次颜色都要重建整份 VBO，大体量蓝图会卡
			int overlayColor = 0xFFFFFFFF;
			RandomSource random = RandomSource.create();

			BlockPos origin = state.getOrigin();

			// 朝向与位置都走同一条落地链（BlueprintPlacement）：铺进落地帧时顺手重算连接性方块，
			// 否则 .txt 蓝图（只存方块 id + meta）的栅栏/墙/玻璃板在幽灵预览里是断开的；
			// 帧外与蓝图空气格读真实世界——建造不清场，蓝图边缘的连接臂本来就是连到旁边既有方块的
			BlueprintPlacement placement = state.placement();
			PreviewFrame.Framed framed = PreviewFrame.build(container, placement::pos, placement::state,
					Minecraft.getInstance().level);
			LightweightBlockContainer blocks = framed.container();
			BlockPos min = framed.min();

			for (int y = 0; y < framed.sizeY(); y++)
			{
				for (int z = 0; z < framed.sizeZ(); z++)
				{
					for (int x = 0; x < framed.sizeX(); x++)
					{
						BlockState blockState = blocks.get(x, y, z);
						if (blockState.isAir()) continue;

						// 顶点存为相对origin的坐标
						float wx = min.getX() + x - origin.getX();
						float wy = min.getY() + y - origin.getY();
						float wz = min.getZ() + z - origin.getZ();

						BakedModel model = blockRenderer.getBlockModel(blockState);

						// 缺失模型 / 箱子（方块实体渲染器绘制、无标准方块模型）：直接画半透明立方体
						if (model == Minecraft.getInstance().getModelManager().getMissingModel()
								|| blockState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)
						{
							if (blockState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)
							{
								LOGGER.debug("NeoSim-GhostBlockRenderer: chest fallback cube at ({},{},{})", x, y, z);
							}
							emitFallbackCube(buf, wx, wy, wz, overlayColor);
							continue;
						}

						int quads = 0;
						for (Direction side : Direction.values())
						{
							for (BakedQuad quad : model.getQuads(blockState, side, random))
							{
								emitQuad(buf, quad, wx, wy, wz, overlayColor);
								quads++;
							}
						}
						for (BakedQuad quad : model.getQuads(blockState, null, random))
						{
							emitQuad(buf, quad, wx, wy, wz, overlayColor);
							quads++;
						}

						// 无标准模型：画纯色立方体
						if (quads == 0)
						{
							emitFallbackCube(buf, wx, wy, wz, overlayColor);
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
					float wx = world.getX() - origin.getX();
					float wy = world.getY() - origin.getY();
					float wz = world.getZ() - origin.getZ();

					BakedModel model = blockRenderer.getBlockModel(markerState);
					for (Direction side : Direction.values())
					{
						for (BakedQuad quad : model.getQuads(markerState, side, random))
						{
							emitQuad(buf, quad, wx, wy, wz, overlayColor);
						}
					}
					for (BakedQuad quad : model.getQuads(markerState, null, random))
					{
						emitQuad(buf, quad, wx, wy, wz, overlayColor);
					}
				}
			}

			MeshData mesh = buf.buildOrThrow();

			// 上传到专用GPU缓冲，之后每帧直接绘制
			VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
			vb.bind();
			vb.upload(mesh);
			byteBuffer.close();
			this.vertexBuffer = vb;

			this.lastRotationOrdinal = state.getRotation().ordinal();
			this.lastMirrorOrdinal = state.getMirror().ordinal();
			this.lastSchematicName = schematic.getName();
			this.lastSchematic = schematic;
		}

		// 每帧绘制缓存的VBO：贴图层 + 纯色层按「纹理显示度」分摊透明度
		public void render(Matrix4f modelView, Matrix4f projection, UiSettings.Preview settings)
		{
			if (vertexBuffer == null) return;

			double mix = Mth.clamp(settings.textureMix, 0.0D, 1.0D);
			float alpha = Mth.clamp(settings.alpha, 0, 255) / 255.0F;
			float red = ((settings.tint >> 16) & 0xFF) / 255.0F;
			float green = ((settings.tint >> 8) & 0xFF) / 255.0F;
			float blue = (settings.tint & 0xFF) / 255.0F;

			if (alpha <= 0.0F) return;

			ResourceLocation white = mix < 0.999D ? WhiteTexture.location() : null;

			try
			{
				// 纯色层：采样 1×1 纯白纹理，剩下的正好是染色 × 透明度（纹理显示度越低这层越重）
				if (white != null)
				{
					RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
					RenderSystem.setShaderTexture(0, white);
					RenderSystem.setShaderColor(red, green, blue, (float) (alpha * (1.0D - mix)));
					vertexBuffer.bind();
					vertexBuffer.drawWithShader(modelView, projection, RenderSystem.getShader());
				}

				// 贴图层：方块图集原样，纹理显示度为 1 时这一层就是全部
				if (mix > 0.001D)
				{
					RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
					RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
					RenderSystem.setShaderColor(red, green, blue, (float) (alpha * mix));
					vertexBuffer.bind();
					vertexBuffer.drawWithShader(modelView, projection, RenderSystem.getShader());
				}
			}
			finally
			{
				// setShaderColor 是全局状态：不复位会污染之后的世界与 GUI 渲染
				RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			}
		}

		// 预览结束/失效时释放显存
		public void invalidate()
		{
			if (vertexBuffer != null)
			{
				vertexBuffer.close();
				vertexBuffer = null;
			}
			lastSchematicName = null;
		}

		private static void emitQuad(BufferBuilder buf, BakedQuad quad,
									  float ox, float oy, float oz, int color)
		{
			int[] verts = quad.getVertices();
			int stride = DefaultVertexFormat.BLOCK.getVertexSize() / 4;
			for (int i = 0; i < 4; i++)
			{
				int base = i * stride;
				float vx = Float.intBitsToFloat(verts[base]) + ox;
				float vy = Float.intBitsToFloat(verts[base + 1]) + oy;
				float vz = Float.intBitsToFloat(verts[base + 2]) + oz;
				float u = Float.intBitsToFloat(verts[base + 4]);
				float v = Float.intBitsToFloat(verts[base + 5]);
				buf.addVertex(vx, vy, vz).setUv(u, v).setColor(color);
			}
		}

		// 无标准模型方块：画纯色立方体（采样方块图集白色纹理，alpha 混合下可见）
		private static void emitFallbackCube(BufferBuilder buf,
										  float ox, float oy, float oz, int color)
		{
			net.minecraft.client.renderer.texture.TextureAtlasSprite white =
					Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
							.apply(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/white_concrete"));
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
					buf.addVertex(vx, vy, vz).setUv(u, v).setColor(color);
				}
			}
		}
	}
}

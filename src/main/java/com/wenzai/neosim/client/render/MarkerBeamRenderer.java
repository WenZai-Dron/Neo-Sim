package com.wenzai.neosim.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wenzai.neosim.NeoSim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

// 标记矩形外框光幕
@EventBusSubscriber(modid = NeoSim.MOD_ID, value = Dist.CLIENT)
public class MarkerBeamRenderer
{
	private static ResourceKey<Level> cachedDim = null;
	private static List<List<BlockPos>> cachedRects = List.of();

	// 金色（半透明）
	private static final float RED = 1.0f;
	private static final float GREEN = 0.843f;
	private static final float BLUE = 0.0f;
	private static final float ALPHA = 0.5f;

	// 光幕高度
	private static final float WALL_HEIGHT = 0.5f;

	// 可见距离
	private static final double RENDER_DIST = 128.0;

	private static final float MODEL_TOP = 15.0f / 16.0f;

	// 自建渲染类型
	private static final RenderType MARKER_WALL = RenderType.create(
			"neosim_marker_wall",
			DefaultVertexFormat.POSITION_COLOR,
			VertexFormat.Mode.TRIANGLES,
			1024,
			false,
			true,
			RenderType.CompositeState.builder()
					.setShaderState(new RenderType.ShaderStateShard(GameRenderer::getPositionColorShader))
					.setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
					.setCullState(RenderType.NO_CULL)
					.setDepthTestState(RenderType.NO_DEPTH_TEST)
					.setWriteMaskState(RenderType.COLOR_WRITE)
					.createCompositeState(false)
	);

	// 服务端同步：只记录数据
	public static void onSync(ResourceKey<Level> dim, List<List<BlockPos>> rects)
	{
		cachedDim = dim;
		cachedRects = rects.stream().map(List::copyOf).toList();
	}

	// 存档切换清空
	public static void clear()
	{
		cachedDim = null;
		cachedRects = List.of();
	}

	// GUI 读取当前活动矩形（与光幕同源数据）
	public static List<List<BlockPos>> getCachedRects()
	{
		return cachedRects;
	}

	public static ResourceKey<Level> getCachedDim()
	{
		return cachedDim;
	}

	@SubscribeEvent
	public static void onRenderLevelStage(RenderLevelStageEvent event)
	{
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		if (cachedDim == null || !cachedDim.equals(mc.level.dimension())) return;
		if (cachedRects.isEmpty()) return;

		Vec3 cam = event.getCamera().getPosition();

		PoseStack ps = event.getPoseStack();
		ps.pushPose();
		ps.translate(-cam.x, -cam.y, -cam.z);
		MultiBufferSource.BufferSource source = mc.renderBuffers().bufferSource();
		VertexConsumer vc = source.getBuffer(MARKER_WALL);
		Matrix4f m = ps.last().pose();

		for (List<BlockPos> corners : cachedRects)
		{
			if (corners.size() != 4) continue;

			int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
			int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
			for (BlockPos p : corners)
			{
				minX = Math.min(minX, p.getX());
				maxX = Math.max(maxX, p.getX());
				minZ = Math.min(minZ, p.getZ());
				maxZ = Math.max(maxZ, p.getZ());
			}
			float yTop = corners.get(0).getY() + MODEL_TOP;
			float yBottom = yTop - WALL_HEIGHT;

			// 距离裁剪
			float centerX = (minX + maxX) * 0.5f;
			float centerZ = (minZ + maxZ) * 0.5f;
			double dx = cam.x - centerX;
			double dz = cam.z - centerZ;
			if (dx * dx + dz * dz > RENDER_DIST * RENDER_DIST) continue;

			// 矩形四边各一面竖直光幕
			float[][] rect = { { minX, minZ }, { maxX, minZ }, { maxX, maxZ }, { minX, maxZ } };
			for (int i = 0; i < 4; i++)
			{
				float ax = rect[i][0] + 0.5f, az = rect[i][1] + 0.5f;
				float bx = rect[(i + 1) % 4][0] + 0.5f, bz = rect[(i + 1) % 4][1] + 0.5f;

				vc.addVertex(m, ax, yBottom, az).setColor(RED, GREEN, BLUE, ALPHA);
				vc.addVertex(m, ax, yTop, az).setColor(RED, GREEN, BLUE, ALPHA);
				vc.addVertex(m, bx, yTop, bz).setColor(RED, GREEN, BLUE, ALPHA);
				vc.addVertex(m, ax, yBottom, az).setColor(RED, GREEN, BLUE, ALPHA);
				vc.addVertex(m, bx, yTop, bz).setColor(RED, GREEN, BLUE, ALPHA);
				vc.addVertex(m, bx, yBottom, bz).setColor(RED, GREEN, BLUE, ALPHA);
			}
		}

		ps.popPose();
		source.endBatch();
	}
}

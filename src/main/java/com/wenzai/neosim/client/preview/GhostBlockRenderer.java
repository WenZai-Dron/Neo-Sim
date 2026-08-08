package com.wenzai.neosim.client.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.schematic.CoordTransform;
import com.wenzai.neosim.schematic.LightweightBlockContainer;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SpecialMarker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
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
        PreviewState state = mgr.getState();
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

        // 直接绘制缓存的VBO
        cache.render(pose, event.getProjectionMatrix());

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    // VBO缓存：仅在预览状态变化时重建
    static class GhostMeshCache
    {
        private VertexBuffer vertexBuffer;
        private BlockPos lastOrigin;
        private int lastRotationOrdinal;
        private int lastMirrorOrdinal;
        private String lastSchematicName;

        // 缓存是否仍有效
        public boolean isValid(PreviewState state)
        {
            if (vertexBuffer == null) return false;
            SchematicData s = state.getSchematic();
            if (s == null) return false;
            if (!s.getName().equals(lastSchematicName)) return false;
            if (!state.getOrigin().equals(lastOrigin)) return false;
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

            // 覆盖色
            int overlayColor = 0x80 << 24 | 0xFB << 16 | 0xFD << 8 | 0xFF;
            RandomSource random = RandomSource.create();

            BlockPos origin = state.getOrigin();
            Rotation rotation = state.getRotation();
            Mirror mirror = state.getMirror();

            for (int y = 0; y < container.getSizeY(); y++)
            {
                for (int z = 0; z < container.getSizeZ(); z++)
                {
                    for (int x = 0; x < container.getSizeX(); x++)
                    {
                        BlockState blockState = container.get(x, y, z);
                        if (blockState.isAir()) continue;

                        // 应用镜像/旋转
                        blockState = CoordTransform.transformState(blockState, state.getFacing());
                        if (mirror != Mirror.NONE)
                        {
                            blockState = blockState.mirror(mirror);
                        }
                        if (rotation != Rotation.NONE)
                        {
                            blockState = blockState.rotate(rotation);
                        }

                        // 顶点存为相对origin的坐标
                        BlockPos world = state.blueprintToWorld(x, y, z);
                        float wx = world.getX() - origin.getX();
                        float wy = world.getY() - origin.getY();
                        float wz = world.getZ() - origin.getZ();

                        BakedModel model = blockRenderer.getBlockModel(blockState);
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
                    BlockPos world = state.blueprintToWorld(local.getX(), local.getY(), local.getZ());
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

            this.lastOrigin = state.getOrigin();
            this.lastRotationOrdinal = state.getRotation().ordinal();
            this.lastMirrorOrdinal = state.getMirror().ordinal();
            this.lastSchematicName = schematic.getName();
        }

        // 每帧绘制缓存的VBO
        public void render(Matrix4f modelView, Matrix4f projection)
        {
            if (vertexBuffer == null) return;
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            vertexBuffer.bind();
            vertexBuffer.drawWithShader(modelView, projection, RenderSystem.getShader());
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
            lastOrigin = null;
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

        // 无标准模型方块：画纯色立方体
        private static void emitFallbackCube(BufferBuilder buf,
                                              float ox, float oy, float oz, int color)
        {
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
                    buf.addVertex(vx, vy, vz).setUv(0, 0).setColor(color);
                }
            }
        }
    }
}

package com.wenzai.neosim.npc;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class Renderer extends HumanoidMobRenderer<Entity, Model<Entity>>
{
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "nsnpc"), "main");

    public static final ModelLayerLocation SLIM_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "nsnpc"), "slim");

    private static final ResourceLocation DEFAULT_SKIN =
            ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "skins/female/osukaari.png");

    // 文件皮肤纹理缓存
    private static final Map<String, ResourceLocation> FILE_TEXTURE_CACHE = new HashMap<>();

    private final Model<Entity> wideModel;
    private final Model<Entity> slimModel;

    public Renderer(EntityRendererProvider.Context context)
    {
        super(context, new Model<>(context.bakeLayer(LAYER)), 0.5F);
        this.wideModel = this.model;
        this.slimModel = new Model<>(context.bakeLayer(SLIM_LAYER));
    }

    @Override
    public ResourceLocation getTextureLocation(Entity entity)
    {
        String skin = entity.getSkin();
        if (!skin.isEmpty())
        {
            // 文件皮肤
            if (skin.startsWith("file:"))
            {
                return getOrLoadFileTexture(skin);
            }

            // 内置资源
            return ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, skin);
        }
        return DEFAULT_SKIN;
    }

    // 从文件加载皮肤纹理（有缓存）
    private static ResourceLocation getOrLoadFileTexture(String skinPath)
    {
        if (FILE_TEXTURE_CACHE.containsKey(skinPath))
        {
            return FILE_TEXTURE_CACHE.get(skinPath);
        }

        // 去掉 "file:" 前缀，得到文件名
        String fileName = skinPath.substring(5);
        Path file = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("Skins").resolve(fileName);

        if (Files.exists(file))
        {
            try (InputStream is = Files.newInputStream(file))
            {
                NativeImage image = NativeImage.read(is);
                ResourceLocation key = ResourceLocation.fromNamespaceAndPath(
                        NeoSim.MOD_ID, "skins/file/" + fileName.toLowerCase().replace('.', '_'));
                DynamicTexture texture = new DynamicTexture(image);
                Minecraft.getInstance().getTextureManager().register(key, texture);
                FILE_TEXTURE_CACHE.put(skinPath, key);
                LOGGER.debug("NeoSim-Renderer: Loaded file skin {} -> {}", skinPath, key);
                return key;
            }
            catch (IOException e)
            {
                LOGGER.error("NeoSim-Renderer: Failed to load file skin {}", skinPath, e);
            }
        }
        else
        {
            LOGGER.warn("NeoSim-Renderer: File skin not found: {}", file.toAbsolutePath());
        }
        return DEFAULT_SKIN;
    }

    @Override
    public void render(Entity entity, float entityYaw, float partialTicks,
                       com.mojang.blaze3d.vertex.PoseStack poseStack,
                       net.minecraft.client.renderer.MultiBufferSource buffer, int packedLight)
    {
        this.model = Model.isSlim(entity.getSkin()) ? slimModel : wideModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    // 玩家16格范围内常显姓名
    @Override
    protected boolean shouldShowName(Entity entity)
    {
        if (!entity.hasCustomName()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return entity.distanceToSqr(mc.player) <= 16.0D * 16.0D;
    }
}

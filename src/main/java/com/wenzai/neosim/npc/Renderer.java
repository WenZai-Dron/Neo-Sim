package com.wenzai.neosim.npc;

import com.wenzai.neosim.NeoSim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public class Renderer extends HumanoidMobRenderer<Entity, Model<Entity>>
{
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "nsnpc"), "main");

    private static final ResourceLocation DEFAULT_SKIN =
            ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "skins/female/osukaari.png");

    public Renderer(EntityRendererProvider.Context context)
    {
        super(context, new Model<>(context.bakeLayer(LAYER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(Entity entity)
    {
        String skin = entity.getSkin();
        if (!skin.isEmpty())
        {
            return ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, skin);
        }
        return DEFAULT_SKIN;
    }

    // 玩家16格范围内常显姓名
    @Override
    protected boolean shouldShowName(Entity entity)
    {
        if (!entity.hasCustomName())
        {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
        {
            return false;
        }
        return entity.distanceToSqr(mc.player) <= 16.0D * 16.0D;
    }
}

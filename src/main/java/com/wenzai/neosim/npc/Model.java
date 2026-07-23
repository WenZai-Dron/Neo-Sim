package com.wenzai.neosim.npc;

import com.mojang.logging.LogUtils;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import net.neoforged.fml.loading.FMLPaths;

public class Model<T extends Entity> extends HumanoidModel<T>
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Boolean> CACHE = new HashMap<>();

    private static final int ARM_EDGE_X = 47;
    private static final int ARM_Y_START = 52;
    private static final int ARM_Y_END = 63;

    public Model(ModelPart root)
    {
        super(root);
    }

    // 宽臂模型
    public static LayerDefinition createBodyLayer()
    {
        MeshDefinition meshDefinition = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        return LayerDefinition.create(meshDefinition, 64, 64);
    }

    // 细臂模型
    public static LayerDefinition createSlimBodyLayer()
    {
        MeshDefinition meshDefinition = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        PartDefinition root = meshDefinition.getRoot();

        root.addOrReplaceChild("right_arm",
                CubeListBuilder.create().texOffs(40, 16)
                        .addBox(-2.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F, CubeDeformation.NONE),
                PartPose.offset(-5.0F, 2.0F, 0.0F));

        root.addOrReplaceChild("left_arm",
                CubeListBuilder.create().texOffs(40, 16).mirror()
                        .addBox(-1.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F, CubeDeformation.NONE),
                PartPose.offset(5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(meshDefinition, 64, 64);
    }

    public static boolean isSlim(String skinPath)
    {
        if (skinPath == null || skinPath.isEmpty())
        {
            return false;
        }

        return CACHE.computeIfAbsent(skinPath, Model::classify);
    }

    private static boolean classify(String skinPath)
    {
        BufferedImage image = loadImage(skinPath);
        if (image == null)
        {
            // 加载失败默认宽臂
            return false;
        }

        // 64x32皮肤没有手臂区域，默认宽臂
        if (image.getWidth() <= ARM_EDGE_X || image.getHeight() <= ARM_Y_END)
        {
            LOGGER.debug("NeoSim-classify: {} | small image ({}x{}), default wide",
                    skinPath, image.getWidth(), image.getHeight());
            return false;
        }

        for (int y = ARM_Y_START; y <= ARM_Y_END; y++)
        {
            int alpha = (image.getRGB(ARM_EDGE_X, y) >> 24) & 0xFF;
            if (alpha <= 128)
            {
                LOGGER.debug("NeoSim-classify: {} | slim (transparent at x={}, y={})", skinPath, ARM_EDGE_X, y);

                // 检测到透明像素就用细臂
                return true;
            }
        }

        LOGGER.debug("NeoSim-classify: {} | wide", skinPath);
        return false;
    }

    private static BufferedImage loadImage(String skinPath)
    {
        // 文件皮肤
        if (skinPath.startsWith("file:"))
        {
            String fileName = skinPath.substring(5);
            Path file = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("Skins").resolve(fileName);
            if (Files.exists(file))
            {
                try (InputStream is = Files.newInputStream(file))
                {
                    return ImageIO.read(is);
                }
                catch (Exception e)
                {
                    LOGGER.warn("NeoSim-loadImage: Failed to decode file skin: {}", skinPath, e);
                    return null;
                }
            }
            LOGGER.warn("NeoSim-loadImage: File skin not found: {}", file.toAbsolutePath());
            return null;
        }

        // 内置资源
        String resourcePath = "/assets/neo_sim/" + skinPath;
        InputStream is = Model.class.getResourceAsStream(resourcePath);

        if (is == null)
        {
            LOGGER.warn("NeoSim-loadImage: Resource not found: {}", skinPath);
            return null;
        }

        try
        {
            return ImageIO.read(is);
        }
        catch (Exception e)
        {
            LOGGER.warn("NeoSim-loadImage: Failed to decode image: {}", skinPath, e);
            return null;
        }
        finally
        {
            try { is.close(); } catch (Exception ignored) {}
        }
    }

    public static void clearCache()
    {
        CACHE.clear();
    }
}

package com.wenzai.neosim.client.render;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.npc.Entity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

// NPC 皮肤按玩家模型渲染：外层（帽子 / 外套 / 袖子 / 裤腿）与 1.8 皮肤的分区（左臂 32,48、左腿 16,48）
// 都由 PlayerModel 提供，同一张皮肤穿在 NPC 身上和穿在玩家身上长得一样
public class Model<T extends Entity> extends PlayerModel<T>
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Map<String, Boolean> CACHE = new HashMap<>();

	private static final int ARM_EDGE_X = 47;
	private static final int ARM_Y_START = 52;
	private static final int ARM_Y_END = 63;

	public Model(ModelPart root, boolean slim)
	{
		super(root, slim);
	}

	// 抬手
	@Override
	public void setupAnim(T entity, float limbSwing, float limbSwingAmount,
						  float ageInTicks, float netHeadYaw, float headPitch)
	{
		super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

		float anim = entity.getBuildAnim();
		if (anim <= 0.001F) return;

		// 客户端插值平滑
		float prev = entity.getPrevBuildAnim();
		float partial = ageInTicks - (float) Math.floor(ageInTicks);
		float t = prev + (anim - prev) * partial;

		// 慢起快落
		float eased = 1.0F - (1.0F - t) * (1.0F - t);

		this.rightArm.xRot = -0.1F + (-(float) Math.PI * 0.98F + 0.1F) * eased;

		// 抬手时轻微外展
		this.rightArm.zRot = 0.06F * eased;

		// 父类同步袖子发生在这条手臂被改写之前，改写完要再同步一次，否则外层袖子留在原地
		this.rightSleeve.copyFrom(this.rightArm);
	}

	// 宽臂模型
	public static LayerDefinition createBodyLayer()
	{
		return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64);
	}

	// 细臂模型
	public static LayerDefinition createSlimBodyLayer()
	{
		return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, true), 64, 64);
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
			try
			{
				is.close();
			}
			catch (Exception ignored)
			{
			}
		}
	}

	public static void clearCache()
	{
		CACHE.clear();
	}
}

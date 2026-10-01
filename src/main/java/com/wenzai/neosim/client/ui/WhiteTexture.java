package com.wenzai.neosim.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.wenzai.neosim.NeoSim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

// 1×1 纯白纹理：投影"纯色层"用它替掉方块图集，于是只剩染色与透明度。
// 这是零 shader 方案的关键——原版 position_tex_color 会给 贴图 × 顶点色 × ColorModulator，
// 顶点色恒白、贴图换成白色，剩下可调的正好就是颜色与透明度。
@OnlyIn(Dist.CLIENT)
public final class WhiteTexture
{
	private static final ResourceLocation LOCATION =
			ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "dynamic/white");

	// 注册结果：null = 还没注册；成功后再调用直接返回位置
	private static boolean attempted;
	private static boolean ready;

	private WhiteTexture()
	{
	}

	// 纹理位置；注册失败返回 null（调用方跳过纯色层，不影响贴图层）
	@Nullable
	public static ResourceLocation location()
	{
		if (!attempted)
		{
			attempted = true;
			try
			{
				NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
				image.setPixelRGBA(0, 0, 0xFFFFFFFF);
				Minecraft.getInstance().getTextureManager().register(LOCATION, new DynamicTexture(image));
				ready = true;
			}
			catch (Throwable t)
			{
				// 拿不到就退化成"只有贴图层"，不弹缺失纹理
				ready = false;
				NeoSim.LOGGER.warn("NeoSim-WhiteTexture: register failed — {}", t.getMessage());
			}
		}
		return ready ? LOCATION : null;
	}
}

package com.wenzai.neosim.util;

import net.minecraft.network.chat.Component;

// 蓝图名组件：用于服务端发出的公告/提示
// 中文客户端显示汉化名（blueprint.neosim.* 键由 datagen 从 zh_cn_names.json 生成），
// 其他语言（以及未收录的自定义蓝图）回落到蓝图自带英文名
public final class BlueprintName
{
	private static final String PREFIX = "blueprint.neosim.";

	private BlueprintName()
	{
	}

	public static Component component(String schematicName)
	{
		if (schematicName == null || schematicName.isEmpty()) return Component.literal("");
		return Component.translatableWithFallback(PREFIX + schematicName, schematicName);
	}
}

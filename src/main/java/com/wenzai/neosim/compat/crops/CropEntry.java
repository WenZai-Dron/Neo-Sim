package com.wenzai.neosim.compat.crops;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;

// 可种植作物条目：种子 → 种下后方块（可能非 CropBlock，如 FD 番茄幼苗/水稻）。
// matureBlock 仅在两阶段作物（幼苗 → 成株）时与 plantBlock 不同，其余情况等于 plantBlock。
public record CropEntry(
		Item seed,
		Block plantBlock,
		Block matureBlock,
		boolean needsWater,
		String modId)
{
	// 是否标准 CropBlock（成熟判定走 AGE >= maxAge）
	public boolean isStandard()
	{
		return plantBlock instanceof CropBlock;
	}

	// 种下方块注册名（持久化标识，如 farmersdelight:cabbages）
	public ResourceLocation plantBlockId()
	{
		return plantBlock.builtInRegistryHolder().key().location();
	}
}

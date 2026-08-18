package com.wenzai.neosim.building;

import com.wenzai.neosim.network.ServerToClientPayloads.MissingScanResponsePayload.MissingEntry;
import com.wenzai.neosim.schematic.MaterialCalculator;
import com.wenzai.neosim.schematic.SchematicData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.List;

// 服务端缺料扫描：替代客户端 hasSingleplayerServer 路径（联机/单机统一）
public final class MissingScanService
{
	private MissingScanService() {}

	public static List<MissingEntry> scan(ServerLevel level, BlockPos boxPos)
	{
		List<MissingEntry> out = new ArrayList<>();
		ConstructionTask task = ConstructionEngine.findTask(boxPos);
		if (task == null || task.getBuilding() == null) return out;
		SchematicData sd = task.getBuilding().getSchematic();
		if (sd == null) return out;

		List<ChestBlockEntity> chests = new ArrayList<>(InventoryManager.findNearbyChests(level, boxPos));
		BlockPos cp = task.getBuilding().getControlBoxPos();
		if (cp != null && !cp.equals(boxPos))
		{
			for (ChestBlockEntity chest : InventoryManager.findNearbyChests(level, cp))
			{
				if (!chests.contains(chest)) chests.add(chest);
			}
		}

		// 缺料量按全服全局模式计算（立项基线 0.1：模式不按城市隔离）
		byte mode = com.wenzai.neosim.storage.ModSavedData.get(level).getMode();

		for (MaterialCalculator.MaterialEntry e : MaterialCalculator.calculate(sd, mode))
		{
			int have = InventoryManager.countItems(chests, e.item);
			int missing = e.count - have;
			if (missing > 0)
			{
				out.add(new MissingEntry(e.item, missing));
			}
		}
		out.sort((a, b) -> Integer.compare(b.count(), a.count()));
		return out;
	}
}

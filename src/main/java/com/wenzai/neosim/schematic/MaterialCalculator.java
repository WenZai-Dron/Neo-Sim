package com.wenzai.neosim.schematic;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 统计蓝图所需的全部方块数量
public class MaterialCalculator
{
    public static boolean isAttachedBlock(BlockState state)
    {
        Block block = state.getBlock();

        // 依附性方块
        if (block instanceof BaseRailBlock) return true;            // 铁轨/动力轨/探测轨
        if (block instanceof TorchBlock) return true;               // 火把/壁火把/红石火把
        if (block instanceof LadderBlock) return true;              // 梯子
        if (block instanceof DoorBlock) return true;                // 门
        if (block instanceof ButtonBlock) return true;              // 按钮
        if (block instanceof LeverBlock) return true;               // 拉杆
        if (block instanceof VineBlock) return true;                // 藤蔓
        if (block instanceof StandingSignBlock) return true;        // 立牌
        if (block instanceof WallSignBlock) return true;            // 墙牌
        if (block instanceof PressurePlateBlock) return true;       // 压力板
        if (block instanceof RedStoneWireBlock) return true;        // 红石线
        if (block instanceof CropBlock) return true;                // 农作物
        if (block instanceof StemBlock) return true;                // 南瓜/西瓜茎
        if (block instanceof AttachedStemBlock) return true;        // 附着茎
        if (block instanceof SaplingBlock) return true;             // 树苗
        if (block instanceof BushBlock) return true;                // 花/草丛/小蘑菇/两格高植物
        if (block instanceof SugarCaneBlock) return true;           // 甘蔗
        if (block instanceof FlowerPotBlock) return true;           // 花盆
        if (block instanceof BannerBlock) return true;              // 旗帜
        if (block instanceof BedBlock) return true;                 // 床
        if (block instanceof CocoaBlock) return true;               // 可可豆
        if (block instanceof TripWireHookBlock) return true;        // 绊线钩
        if (block instanceof TripWireBlock) return true;            // 绊线
        if (block instanceof SnowLayerBlock) return true;           // 雪层
        if (block instanceof AnvilBlock) return true;               // 铁砧
        if (block instanceof CarpetBlock) return true;              // 地毯（需下方支撑，第二阶段放）

        // 连接性方块
        if (block instanceof WallBlock) return true;                // 墙
        if (block instanceof FenceBlock) return true;               // 栅栏
        if (block instanceof IronBarsBlock) return true;            // 铁栏杆
        if (block instanceof StainedGlassPaneBlock) return true;    // 玻璃板
        return false;
    }
    public static boolean requiresMaterial(BlockState state)
    {
        if (state.isAir()) return false;

        // 免费清单：水、岩浆、草、火
        String name = state.getBlock().builtInRegistryHolder().key().location().getPath();
        if (name.contains("grass") || name.contains("bed") || name.contains("sign")
                || name.contains("cake") || name.contains("door") || name.contains("slab")
                || name.contains("farmland"))
            return false;
        return true;
    }

    // 按数量降序排列的材料清单条目
    public static List<MaterialEntry> calculate(SchematicData schematic)
    {
        LightweightBlockContainer container = schematic.getBlockContainer();
        Map<Item, Integer> counts = new LinkedHashMap<>();

        for (int y = 0; y < container.getSizeY(); y++)
        {
            for (int z = 0; z < container.getSizeZ(); z++)
            {
                for (int x = 0; x < container.getSizeX(); x++)
                {
                    BlockState state = container.get(x, y, z);
                    if (state.isAir()) continue;

                    Item item = state.getBlock().asItem();
                    counts.merge(item, 1, Integer::sum);
                }
            }
        }

        List<MaterialEntry> entries = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : counts.entrySet())
        {
            entries.add(new MaterialEntry(e.getKey(), e.getValue()));
        }
        entries.sort((a, b) -> Integer.compare(b.count, a.count));
        return entries;
    }

    public static class MaterialEntry
    {
        public final Item item;
        public final int count;

        MaterialEntry(Item item, int count)
        {
            this.item = item;
            this.count = count;
        }

        public String formatted()
        {
            int stacks = count / 64;
            int remainder = count % 64;
            if (stacks == 0) return "× " + count;
            if (remainder == 0) return "× " + count + " (" + stacks + " stacks)";
            return "× " + count + " (" + stacks + " stacks + " + remainder + ")";
        }
    }
}

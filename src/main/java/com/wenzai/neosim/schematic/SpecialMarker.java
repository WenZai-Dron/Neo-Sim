package com.wenzai.neosim.schematic;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

// Sim-U-Kraft建筑文件中的特殊标记
public enum SpecialMarker
{
    LIVING_POINT,
    CONTROL_BOX,
    LIGHT_BOX,
    MARKER_0,
    MARKER_1,
    MARKER_2,
    MARKER_3,
    MARKER_4,
    MARKER_5,
    MARKER_6,
    MARKER_7,
    MARKER_8,
    MARKER_9;

    // 从.txt字符解析为SpecialMarker，非标记字符返回null
    @Nullable
    public static SpecialMarker fromChar(char c)
    {
        return switch (c)
        {
            case '!' -> LIVING_POINT;
            case '$' -> CONTROL_BOX;
            case '*' -> LIGHT_BOX;
            case '0' -> MARKER_0;
            case '1' -> MARKER_1;
            case '2' -> MARKER_2;
            case '3' -> MARKER_3;
            case '4' -> MARKER_4;
            case '5' -> MARKER_5;
            case '6' -> MARKER_6;
            case '7' -> MARKER_7;
            case '8' -> MARKER_8;
            case '9' -> MARKER_9;
            default -> null;
        };
    }

    // 判断字符是否为特殊标记
    public static boolean isMarkerChar(char c)
    {
        return fromChar(c) != null;
    }

    // 特殊标记对应的可放置方块状态（null=保持空气）
    @Nullable
    public BlockState toBlockState()
    {
        return switch (this)
        {
            case CONTROL_BOX  -> com.wenzai.neosim.block.ModBlocks.CONTROL_BOX.get().defaultBlockState();
            case LIGHT_BOX    -> Blocks.GLOWSTONE.defaultBlockState();

            // 生活点：居民生成点，不放置方块
            case LIVING_POINT -> null;
            default           -> com.wenzai.neosim.block.ModBlocks.MARKER.get().defaultBlockState();   // MARKER_0-9
        };
    }
}

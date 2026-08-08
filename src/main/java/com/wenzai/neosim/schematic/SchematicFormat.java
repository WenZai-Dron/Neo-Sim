package com.wenzai.neosim.schematic;

import java.nio.file.Path;

// 支持的文件格式
public enum SchematicFormat
{
    // GZIP压缩NBT(.litematic) — 主格式
    LITEMATICA(".litematic"),

    // Sim-U-Kraft文本格式(.txt) — 旧版兼容
    SIM_UKRAFT_TXT(".txt"),

    // Sponge schematic(.schem) - 后续支持
    SPONGE_SCHEM(".schem"),

    // 原版结构NBT(.nbt) — 后续支持
    VANILLA_NBT(".nbt"),

    // 未知/不支持的格式 - ？？？
    UNKNOWN("");

    private final String extension;

    SchematicFormat(String extension)
    {
        this.extension = extension;
    }

    public String getExtension()
    {
        return extension;
    }

    // 从文件名或路径检测格式
    public static SchematicFormat detect(String fileName)
    {
        if (fileName == null) return UNKNOWN;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".litematic")) return LITEMATICA;
        if (lower.endsWith(".txt"))       return SIM_UKRAFT_TXT;
        if (lower.endsWith(".schem"))     return SPONGE_SCHEM;
        if (lower.endsWith(".nbt"))      return VANILLA_NBT;
        return UNKNOWN;
    }

    // 从{@link Path}检测格式
    public static SchematicFormat detect(Path path)
    {
        if (path == null) return UNKNOWN;
        return detect(path.getFileName().toString());
    }
}

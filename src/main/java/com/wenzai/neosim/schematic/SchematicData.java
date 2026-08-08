package com.wenzai.neosim.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 统一数据模型
public class SchematicData
{
    private final String name;
    private final String author;
    @Nullable
    private final String description;
    private final BuildingType type;
    
    private final SchematicFormat format;
    private final long timeCreated;
    private final long timeModified;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    private final LightweightBlockContainer blockContainer;
    private final Map<BlockPos, CompoundTag> tileEntities;
    private final List<SchematicEntity> entities;
    private final Map<BlockPos, SpecialMarker> specialMarkers;

    private SchematicData(Builder builder)
    {
        this.name = builder.name;
        this.author = (builder.author == null || builder.author.isEmpty())
                ? "Unknown" : builder.author;
        this.description = builder.description;
        this.type = builder.type != null ? builder.type : BuildingType.OTHER;
        this.format = builder.format != null ? builder.format : SchematicFormat.UNKNOWN;
        this.timeCreated = builder.timeCreated;
        this.timeModified = builder.timeModified;
        this.sizeX = builder.sizeX;
        this.sizeY = builder.sizeY;
        this.sizeZ = builder.sizeZ;
        this.blockContainer = builder.blockContainer;
        this.tileEntities = builder.tileEntities != null
                ? Collections.unmodifiableMap(new HashMap<>(builder.tileEntities))
                : Collections.emptyMap();
        this.entities = builder.entities != null
                ? List.copyOf(builder.entities)
                : Collections.emptyList();
        this.specialMarkers = builder.specialMarkers != null
                ? Collections.unmodifiableMap(new HashMap<>(builder.specialMarkers))
                : Collections.emptyMap();
    }

    public String getName()                                   { return name; }
    @Nullable public String getDescription()                  { return description; }
    public BuildingType getType()                             { return type; }
    public SchematicFormat getFormat()                        { return format; }
    public long getTimeCreated()                              { return timeCreated; }
    public long getTimeModified()                             { return timeModified; }
    public int getSizeX()                                     { return sizeX; }
    public int getSizeY()                                     { return sizeY; }
    public int getSizeZ()                                     { return sizeZ; }
    public LightweightBlockContainer getBlockContainer()      { return blockContainer; }
    public Map<BlockPos, CompoundTag> getTileEntities()       { return tileEntities; }
    public List<SchematicEntity> getEntities()                 { return entities; }
    public Map<BlockPos, SpecialMarker> getSpecialMarkers()   { return specialMarkers; }

    public String getDimensionString()
    {
        return sizeX + " × " + sizeY + " × " + sizeZ + " (W×H×D)";
    }

    // 方块总数
    public int getTotalSolidBlocks()
    {
        return blockContainer.countSolidBlocks();
    }

    // 总体积（含空气）
    public int getTotalVolume()
    {
        return blockContainer.getTotalVolume();
    }

    // 返回作者，无作者元数据时返回{@code "Unknown"}
    public String getAuthor()
    {
        return author;
    }

    // 要改的，或许要删除
    public String getAuthorByLine()
    {
        return hasKnownAuthor() ? "by " + author : "";
    }

    // 作者是否为已知作者，GUI据此决定渲染颜色（应该要改）
    public boolean hasKnownAuthor()
    {
        return !"Unknown".equals(author);
    }

    // 构造
    public static Builder builder()
    {
        return new Builder();
    }

    public static class Builder
    {
        private String name = "Unnamed";
        private String author;
        private String description;
        private BuildingType type = BuildingType.OTHER;
        private SchematicFormat format;
        private long timeCreated;
        private long timeModified;
        private int sizeX, sizeY, sizeZ;
        private LightweightBlockContainer blockContainer;
        private Map<BlockPos, CompoundTag> tileEntities;
        private List<SchematicEntity> entities;
        private Map<BlockPos, SpecialMarker> specialMarkers;

        public Builder name(String v)                                        { this.name = v; return this; }
        public Builder author(String v)                                      { this.author = v; return this; }
        public Builder description(String v)                                 { this.description = v; return this; }
        public Builder type(BuildingType v)                                  { this.type = v; return this; }
        public Builder format(SchematicFormat v)                             { this.format = v; return this; }
        public Builder timeCreated(long v)                                   { this.timeCreated = v; return this; }
        public Builder timeModified(long v)                                  { this.timeModified = v; return this; }
        public Builder sizeX(int v)                                          { this.sizeX = v; return this; }
        public Builder sizeY(int v)                                          { this.sizeY = v; return this; }
        public Builder sizeZ(int v)                                          { this.sizeZ = v; return this; }
        public Builder blockContainer(LightweightBlockContainer v)           { this.blockContainer = v; return this; }
        public Builder tileEntities(Map<BlockPos, CompoundTag> v)             { this.tileEntities = v; return this; }
        public Builder entities(List<SchematicEntity> v)                      { this.entities = v; return this; }
        public Builder specialMarkers(Map<BlockPos, SpecialMarker> v)         { this.specialMarkers = v; return this; }

        public SchematicData build()
        {
            if (blockContainer == null)
            {
                throw new IllegalStateException("blockContainer is required");
            }
            return new SchematicData(this);
        }
    }

    // Schematic中存储的实体数据
    public record SchematicEntity(BlockPos position, CompoundTag nbtData)
    {
        public SchematicEntity
        {
            if (nbtData == null)
            {
                throw new IllegalArgumentException("nbtData must not be null");
            }
        }
    }

    @Override
    public String toString()
    {
        return "SchematicData{name='" + name + "', author='" + author
                + "', " + getDimensionString() + ", type=" + type
                + ", solidBlocks=" + getTotalSolidBlocks() + "}";
    }
}

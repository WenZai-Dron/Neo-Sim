package com.wenzai.neosim.schematic;

import com.wenzai.neosim.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

// 统一数据模型
public class SchematicData
{
	private final String name;
	private final String author;
	@Nullable
	private final String description;
	private final BuildingType type;

	// 蓝图是否含控制箱（构造时判定）：只有住宅类建筑会放控制箱
	private final boolean controlBox;

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
		this.controlBox = containsControlBox(builder.blockContainer, builder.specialMarkers);
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

	public String getName()
	{
		return name;
	}

	@Nullable public String getDescription()
	{
		return description;
	}

	public BuildingType getType()
	{
		return type;
	}

	// 蓝图内是否含控制箱
	public boolean hasControlBox()
	{
		return controlBox;
	}

	// 住宅判定
	// 自定义建筑没有目录可归类，含控制箱即视为住宅，无需玩家手动声明；
	// 内置建筑一律以目录归类为准——老库里商业/工业蓝图同样带控制箱，
	// 若让控制箱覆盖目录类型，它们会被误判成住宅
	public boolean isResidential()
	{
		return type == BuildingType.RESIDENTIAL
						|| (type == BuildingType.CUSTOM && controlBox);
	}


	// 该蓝图用哪套坐标系算法（.txt 与 .litematic 分开，见 SchematicFrame）
	public SchematicFrame frame()
	{
		return SchematicFrame.of(this);
	}

	public SchematicFormat getFormat()
	{
		return format;
	}

	public long getTimeCreated()
	{
		return timeCreated;
	}

	public long getTimeModified()
	{
		return timeModified;
	}

	public int getSizeX()
	{
		return sizeX;
	}

	public int getSizeY()
	{
		return sizeY;
	}

	public int getSizeZ()
	{
		return sizeZ;
	}

	public LightweightBlockContainer getBlockContainer()
	{
		return blockContainer;
	}

	public Map<BlockPos, CompoundTag> getTileEntities()
	{
		return tileEntities;
	}

	public List<SchematicEntity> getEntities()
	{
		return entities;
	}

	public Map<BlockPos, SpecialMarker> getSpecialMarkers()
	{
		return specialMarkers;
	}

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

	// 判断蓝图是否含控制箱。控制箱有两种承载形式，必须都认：
	//   1) .litematic 里直接放下的 control_box 方块 → 在方块容器调色板里
	//   2) .txt 的 '$' 字符，以及 .litematic 的 NeoSim_SpecialBlocks 元数据
	//      → 解析成 SpecialMarker.CONTROL_BOX，不进方块容器（建造时才由标记回填方块）
	// 只扫调色板会漏掉第 2 种，而内置住宅蓝图全是 .txt，故两种都查
	// 方块只看种类忽略朝向等状态；蓝图加载在注册表冻结之后，此处取方块是安全的
	private static boolean containsControlBox(LightweightBlockContainer container,
		@Nullable Map<BlockPos, SpecialMarker> markers)
	{
		if (markers != null && markers.containsValue(SpecialMarker.CONTROL_BOX))
		{
			return true;
		}

		Block controlBox = ModBlocks.CONTROL_BOX.get();
		BlockStatePalette palette = container.getPalette();
		int size = palette.size();
		for (int i = 0; i < size; i++)
		{
			if (palette.getBlockState(i).is(controlBox))
			{
				return true;
			}
		}
		return false;
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

		public Builder name(String v)
		{
			this.name = v;
			return this;
		}

		public Builder author(String v)
		{
			this.author = v;
			return this;
		}

		public Builder description(String v)
		{
			this.description = v;
			return this;
		}

		public Builder type(BuildingType v)
		{
			this.type = v;
			return this;
		}

		public Builder format(SchematicFormat v)
		{
			this.format = v;
			return this;
		}

		public Builder timeCreated(long v)
		{
			this.timeCreated = v;
			return this;
		}

		public Builder timeModified(long v)
		{
			this.timeModified = v;
			return this;
		}

		public Builder sizeX(int v)
		{
			this.sizeX = v;
			return this;
		}

		public Builder sizeY(int v)
		{
			this.sizeY = v;
			return this;
		}

		public Builder sizeZ(int v)
		{
			this.sizeZ = v;
			return this;
		}

		public Builder blockContainer(LightweightBlockContainer v)
		{
			this.blockContainer = v;
			return this;
		}

		public Builder tileEntities(Map<BlockPos, CompoundTag> v)
		{
			this.tileEntities = v;
			return this;
		}

		public Builder entities(List<SchematicEntity> v)
		{
			this.entities = v;
			return this;
		}

		public Builder specialMarkers(Map<BlockPos, SpecialMarker> v)
		{
			this.specialMarkers = v;
			return this;
		}

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

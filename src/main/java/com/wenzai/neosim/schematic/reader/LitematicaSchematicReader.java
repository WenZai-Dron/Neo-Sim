package com.wenzai.neosim.schematic.reader;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.block.ModBlocks;
import com.wenzai.neosim.schematic.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// .litematic文件读取
public class LitematicaSchematicReader implements ISchematicReader
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int MIN_SUPPORTED_VERSION = 5;

	@Override
	public SchematicFormat getFormat()
	{
		return SchematicFormat.LITEMATICA;
	}

	@Override
	public SchematicData read(Path filePath) throws IOException
	{
		try
		{
			return readInternal(filePath);
		}
		catch (IOException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-LitematicaSchematicReader: {} when reading {}: {}",
					e.getClass().getSimpleName(), filePath, e.getMessage(), e);
			throw new IOException("Failed to read litematic: " + filePath, e);
		}
	}

	private SchematicData readInternal(Path filePath) throws IOException
	{
		CompoundTag root;
		try (InputStream in = Files.newInputStream(filePath))
		{
			// L18：设解压上限（256MB），损坏/恶意文件超限即跳过，防 OOM
			root = NbtIo.readCompressed(in, NbtAccounter.create(256L * 1024L * 1024L));
		}

		int version = root.getInt("Version");
		int dataVersion = root.getInt("MinecraftDataVersion");

		if (version < MIN_SUPPORTED_VERSION)
		{
			LOGGER.warn("NeoSim-LitematicaSchematicReader: version {} (< {}) may need remapping",
					version, MIN_SUPPORTED_VERSION);
		}

		// metadata
		CompoundTag meta = root.getCompound("Metadata");
		String name = readString(meta, "Name", null);
		String author = readString(meta, "Author", null);
		String description = readString(meta, "Description", null);
		long timeCreated = meta.contains("TimeCreated") ? meta.getLong("TimeCreated") : 0L;
		long timeModified = meta.contains("TimeModified") ? meta.getLong("TimeModified") : 0L;

		// 文件名兜底：metadata中Name为空时取文件名
		if (name == null || name.isEmpty())
		{
			String fileName = filePath.getFileName().toString();
			int dot = fileName.lastIndexOf('.');
			name = dot > 0 ? fileName.substring(0, dot) : fileName;
		}

		// EnclosingSize兼容三种格式：IntArray[I;X,Y,Z]/List[X,Y,Z]/Compound{X,Y,Z}
		int encX, encY, encZ;
		if (meta.contains("EnclosingSize", Tag.TAG_INT_ARRAY))
		{
			int[] arr = meta.getIntArray("EnclosingSize");
			encX = arr.length > 0 ? arr[0] : 0;
			encY = arr.length > 1 ? arr[1] : 0;
			encZ = arr.length > 2 ? arr[2] : 0;
		}
		else if (meta.contains("EnclosingSize", Tag.TAG_LIST))
		{
			ListTag list = meta.getList("EnclosingSize", Tag.TAG_INT);
			encX = list.size() > 0 ? list.getInt(0) : 0;
			encY = list.size() > 1 ? list.getInt(1) : 0;
			encZ = list.size() > 2 ? list.getInt(2) : 0;
		}
		else
		{
			CompoundTag enclosingSize = meta.getCompound("EnclosingSize");

			// 兼容大写和小写两种键名
			encX = Math.abs(enclosingSize.contains("X") ? enclosingSize.getInt("X") : enclosingSize.getInt("x"));
			encY = Math.abs(enclosingSize.contains("Y") ? enclosingSize.getInt("Y") : enclosingSize.getInt("y"));
			encZ = Math.abs(enclosingSize.contains("Z") ? enclosingSize.getInt("Z") : enclosingSize.getInt("z"));
		}

		// regions
		CompoundTag regionsTag = root.getCompound("Regions");

		Map<BlockPos, CompoundTag> allTileEntities = new HashMap<>();
		List<SchematicData.SchematicEntity> allEntities = new ArrayList<>();
		Map<BlockPos, SpecialMarker> allMarkers = new HashMap<>();

		// 扩展字段（特殊标记）：Metadata.NeoSim_SpecialBlocks
		parseSpecialMarkers(meta, allMarkers);

		// 扫描所有region，计算全局世界坐标范围
		int globalMinX = Integer.MAX_VALUE, globalMinY = Integer.MAX_VALUE, globalMinZ = Integer.MAX_VALUE;
		int globalMaxX = Integer.MIN_VALUE, globalMaxY = Integer.MIN_VALUE, globalMaxZ = Integer.MIN_VALUE;
		for (String regionName : regionsTag.getAllKeys())
		{
			CompoundTag region = regionsTag.getCompound(regionName);
			CompoundTag posTag = region.getCompound("Position");
			int offsetX = posTag.contains("X") ? posTag.getInt("X") : posTag.getInt("x");
			int offsetY = posTag.contains("Y") ? posTag.getInt("Y") : posTag.getInt("y");
			int offsetZ = posTag.contains("Z") ? posTag.getInt("Z") : posTag.getInt("z");
			CompoundTag sizeTag = region.getCompound("Size");
			int sizeXSigned = sizeTag.contains("X") ? sizeTag.getInt("X") : sizeTag.getInt("x");
			int sizeYSigned = sizeTag.contains("Y") ? sizeTag.getInt("Y") : sizeTag.getInt("y");
			int sizeZSigned = sizeTag.contains("Z") ? sizeTag.getInt("Z") : sizeTag.getInt("z");

			// 世界坐标范围
			int minX = sizeXSigned < 0 ? offsetX + sizeXSigned + 1 : offsetX;
			int maxX = sizeXSigned < 0 ? offsetX : offsetX + sizeXSigned - 1;
			int minY = sizeYSigned < 0 ? offsetY + sizeYSigned + 1 : offsetY;
			int maxY = sizeYSigned < 0 ? offsetY : offsetY + sizeYSigned - 1;
			int minZ = sizeZSigned < 0 ? offsetZ + sizeZSigned + 1 : offsetZ;
			int maxZ = sizeZSigned < 0 ? offsetZ : offsetZ + sizeZSigned - 1;
			globalMinX = Math.min(globalMinX, minX);
			globalMinY = Math.min(globalMinY, minY);
			globalMinZ = Math.min(globalMinZ, minZ);
			globalMaxX = Math.max(globalMaxX, maxX);
			globalMaxY = Math.max(globalMaxY, maxY);
			globalMaxZ = Math.max(globalMaxZ, maxZ);
		}

		if (regionsTag.getAllKeys().isEmpty())
		{
			throw new IOException("NeoSim-LitematicaSchematicReader: no regions in " + filePath);
		}

		// 容器按真实数据范围建
		int realEncX = globalMaxX - globalMinX + 1;
		int realEncY = globalMaxY - globalMinY + 1;
		int realEncZ = globalMaxZ - globalMinZ + 1;
		if (encX != realEncX || encY != realEncY || encZ != realEncZ)
		{
			LOGGER.warn("NeoSim-LitematicaSchematicReader: EnclosingSize ({},{},{}) != real data span ({},{},{})",
					encX, encY, encZ, realEncX, realEncY, realEncZ);
		}
		LightweightBlockContainer container = new LightweightBlockContainer(realEncX, realEncY, realEncZ);

		// 解析并写入
		for (String regionName : regionsTag.getAllKeys())
		{
			CompoundTag region = regionsTag.getCompound(regionName);

			CompoundTag posTag = region.getCompound("Position");
			int offsetX = posTag.contains("X") ? posTag.getInt("X") : posTag.getInt("x");
			int offsetY = posTag.contains("Y") ? posTag.getInt("Y") : posTag.getInt("y");
			int offsetZ = posTag.contains("Z") ? posTag.getInt("Z") : posTag.getInt("z");

			CompoundTag sizeTag = region.getCompound("Size");
			int sizeXSigned = sizeTag.contains("X") ? sizeTag.getInt("X") : sizeTag.getInt("x");
			int sizeYSigned = sizeTag.contains("Y") ? sizeTag.getInt("Y") : sizeTag.getInt("y");
			int sizeZSigned = sizeTag.contains("Z") ? sizeTag.getInt("Z") : sizeTag.getInt("z");
			int regSizeX = Math.abs(sizeXSigned);
			int regSizeY = Math.abs(sizeYSigned);
			int regSizeZ = Math.abs(sizeZSigned);

			// palette
			ListTag paletteTag = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
			BlockStatePalette palette = parsePalette(paletteTag);

			// block states
			long[] blockStateArray;
			if (region.contains("BlockStates", Tag.TAG_LONG_ARRAY))
			{
				blockStateArray = region.getLongArray("BlockStates");
			}
			else if (region.contains("BlockStates", Tag.TAG_INT_ARRAY))
			{
				int[] intArray = region.getIntArray("BlockStates");
				blockStateArray = new long[intArray.length];
				for (int i = 0; i < intArray.length; i++)
				{
					blockStateArray[i] = intArray[i] & 0xFFFFFFFFL;
				}
			}
			else
			{
				LOGGER.warn("NeoSim-LitematicaSchematicReader: no BlockStates in region '{}', skip", regionName);
				continue;
			}

			int bits = palette.getBits();
			int totalVolume = regSizeX * regSizeY * regSizeZ;
			LightweightBitArray bitArray = new LightweightBitArray(blockStateArray, totalVolume, bits);

			// 复制
			for (int y = 0; y < regSizeY; y++)
			{
				for (int z = 0; z < regSizeZ; z++)
				{
					for (int x = 0; x < regSizeX; x++)
					{
						int regionIndex = y * (regSizeX * regSizeZ) + z * regSizeX + x;
						int paletteId = (int) bitArray.getAt(regionIndex);
						if (paletteId == 0) continue;

						BlockState state = palette.getBlockState(paletteId);

						// 负数尺寸：数据从远端开始存储，坐标从Position+Size+1算起
						int wx = offsetX + (sizeXSigned < 0 ? sizeXSigned + 1 + x : x);
						int wy = offsetY + (sizeYSigned < 0 ? sizeYSigned + 1 + y : y);
						int wz = offsetZ + (sizeZSigned < 0 ? sizeZSigned + 1 + z : z);

						// 生活点方块约定：该格标记为 LIVING_POINT，不写入容器（保持空气）
						if (state.getBlock() == ModBlocks.LIVING_POINT.get())
						{
							allMarkers.putIfAbsent(new BlockPos(wx - globalMinX, wy - globalMinY, wz - globalMinZ),
									SpecialMarker.LIVING_POINT);
							continue;
						}

						container.set(wx - globalMinX, wy - globalMinY, wz - globalMinZ, state);
					}
				}
			}

			// tile entities
			if (region.contains("TileEntities"))
			{
				ListTag teList = region.getList("TileEntities", Tag.TAG_COMPOUND);
				for (int i = 0; i < teList.size(); i++)
				{
					CompoundTag te = teList.getCompound(i);
					int tx = te.getInt("x") + offsetX;
					int ty = te.getInt("y") + offsetY;
					int tz = te.getInt("z") + offsetZ;
					allTileEntities.put(new BlockPos(tx, ty, tz), te);
				}
			}

			// entities
			if (region.contains("Entities"))
			{
				ListTag entList = region.getList("Entities", Tag.TAG_COMPOUND);
				for (int i = 0; i < entList.size(); i++)
				{
					CompoundTag ent = entList.getCompound(i);
					ListTag posList = ent.getList("Pos", Tag.TAG_DOUBLE);
					double ex = posList.getDouble(0) + offsetX;
					double ey = posList.getDouble(1) + offsetY;
					double ez = posList.getDouble(2) + offsetZ;
					ent.put("Pos", newPosList(ex, ey, ez));
					allEntities.add(new SchematicData.SchematicEntity(BlockPos.containing(ex, ey, ez), ent));
				}
			}

			LOGGER.debug("NeoSim-LitematicaSchematicReader: loaded region '{}' {}×{}×{} at offset ({},{},{})",
					regionName, regSizeX, regSizeY, regSizeZ, offsetX, offsetY, offsetZ);
		}

		return SchematicData.builder()
				.name(name).author(author).description(description)
				.type(BuildingType.OTHER)
				.format(SchematicFormat.LITEMATICA)
				.timeCreated(timeCreated).timeModified(timeModified)
				.sizeX(realEncX).sizeY(realEncY).sizeZ(realEncZ)
				.blockContainer(container)
				.tileEntities(allTileEntities.isEmpty() ? null : allTileEntities)
				.entities(allEntities.isEmpty() ? null : allEntities)
				.specialMarkers(allMarkers.isEmpty() ? null : allMarkers)
				.build();
	}

	// 解析扩展字段
	private void parseSpecialMarkers(CompoundTag meta, Map<BlockPos, SpecialMarker> out)
	{
		if (!meta.contains("NeoSim_SpecialBlocks", Tag.TAG_LIST)) return;

		ListTag list = meta.getList("NeoSim_SpecialBlocks", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++)
		{
			CompoundTag entry = list.getCompound(i);
			int x = entry.getInt("X");
			int y = entry.getInt("Y");
			int z = entry.getInt("Z");
			String type = entry.getString("Type");
			try
			{
				out.put(new BlockPos(x, y, z), SpecialMarker.valueOf(type));
				LOGGER.info("NeoSim-LitematicaSchematicReader: marker {} at ({},{},{})", type, x, y, z);
			}
			catch (IllegalArgumentException e)
			{
				LOGGER.warn("NeoSim-LitematicaSchematicReader: unknown SpecialMarker type '{}' at ({},{},{}) — skipped",
						type, x, y, z);
			}
		}
	}

	// palette解析
	private BlockStatePalette parsePalette(ListTag paletteTag)
	{
		BlockStatePalette palette = new BlockStatePalette();
		for (int i = 0; i < paletteTag.size(); i++)
		{
			CompoundTag tag = paletteTag.getCompound(i);
			BlockState state = parseBlockState(tag);
			if (i == 0 && state.isAir())
			{
				continue;
			}
			palette.idFor(state);
		}
		return palette;
	}

	private BlockState parseBlockState(CompoundTag tag)
	{
		String blockName = tag.getString("Name");
		ResourceLocation rl = ResourceLocation.parse(blockName);
		Block block = BuiltInRegistries.BLOCK.get(rl);
		if (block == null)
		{
			LOGGER.warn("NeoSim-LitematicaSchematicReader: unknown block '{}', fallback to AIR", blockName);
			return Blocks.AIR.defaultBlockState();
		}

		BlockState state = block.defaultBlockState();

		if (tag.contains("Properties", Tag.TAG_COMPOUND))
		{
			CompoundTag props = tag.getCompound("Properties");
			for (String key : props.getAllKeys())
			{
				Property<?> property = block.getStateDefinition().getProperty(key);
				if (property != null)
				{
					String valueStr = props.getString(key);
					state = applyProperty(state, property, valueStr);
				}
			}
		}

		return state;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static BlockState applyProperty(BlockState state, Property property, String valueStr)
	{
		return (BlockState) property.getValue(valueStr)
				.map(v -> state.setValue(property, (Comparable) v))
				.orElse(state);
	}

	private static String readString(CompoundTag tag, String key, String defaultValue)
	{
		return tag.contains(key) ? tag.getString(key) : defaultValue;
	}

	private static ListTag newPosList(double x, double y, double z)
	{
		ListTag list = new ListTag();
		list.add(net.minecraft.nbt.DoubleTag.valueOf(x));
		list.add(net.minecraft.nbt.DoubleTag.valueOf(y));
		list.add(net.minecraft.nbt.DoubleTag.valueOf(z));
		return list;
	}
}

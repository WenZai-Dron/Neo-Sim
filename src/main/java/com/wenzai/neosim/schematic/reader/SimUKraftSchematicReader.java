package com.wenzai.neosim.schematic.reader;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.schematic.*;
import com.wenzai.neosim.schematic.mapping.BlockIdMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// .txt文件读取
public class SimUKraftSchematicReader implements ISchematicReader
{

	private static final Logger LOGGER = LogUtils.getLogger();
	private final BlockIdMapping blockIdMapping;

	public SimUKraftSchematicReader(BlockIdMapping blockIdMapping)
	{
		this.blockIdMapping = blockIdMapping;
	}

	@Override
	public SchematicFormat getFormat()
	{
		return SchematicFormat.SIM_UKRAFT_TXT;
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
			LOGGER.error("NeoSim-SimUKraftSchematicReader: {} when reading {}: {}",
					e.getClass().getSimpleName(), filePath, e.getMessage(), e);
			throw new IOException("Failed to read SimU-Kraft txt: " + filePath, e);
		}
	}

	private SchematicData readInternal(Path filePath) throws IOException
	{
		List<String> lines;
		try (BufferedReader reader = Files.newBufferedReader(filePath))
		{
			lines = new ArrayList<>();
			String line;
			while ((line = reader.readLine()) != null)
			{
				lines.add(line);
			}
		}

		if (lines.size() < 2)
		{
			throw new IOException("NeoSim-SimUKraftSchematicReader: file too short: " + filePath);
		}

		String fileName = filePath.getFileName().toString();

		// 第1行：尺寸
		String dimLine = lines.get(0).trim();
		int[] dims = parseDimensions(dimLine);
		int sizeX = dims[0];
		int sizeZ = dims[1];
		int sizeY = dims[2];

		// 第2行：字符映射
		String mapLine = lines.get(1).trim();
		Map<Character, String> charMap = new HashMap<>();
		String extractedAuthor = null;
		for (String mapping : mapLine.split(";"))
		{
			String[] kv = mapping.split("=", 2);
			if (kv.length != 2) continue;

			String key = kv[0].trim();
			String value = kv[1].trim();

			if ("AU".equalsIgnoreCase(key))
			{
				extractedAuthor = value;
				continue;
			}

			if (key.length() == 1)
			{
				charMap.put(key.charAt(0), value);
			}
		}

		// 从第3行开始读取各层
		if (lines.size() < 2 + sizeY)
		{
			throw new IOException("NeoSim-SimUKraftSchematicReader: expected " + sizeY
					+ " layers, got " + (lines.size() - 2) + " in " + filePath);
		}

		// L17：先预扫各层建立最终 palette，再按最终 bits 一次性分配存储，避免 set 过程中 palette 跳档反复整容器重拷
		int expectedLen = sizeX * sizeZ;
		List<String> layerLines = new ArrayList<>();
		for (int y = 0; y < sizeY; y++)
		{
			String layerLine = lines.get(2 + y);
			if (layerLine == null || layerLine.isEmpty())
			{
				layerLines.add(null);
				continue;
			}
			if (layerLine.length() < expectedLen)
			{
				StringBuilder sb = new StringBuilder(layerLine);
				while (sb.length() < expectedLen) sb.append('A');
				layerLine = sb.toString();
			}
			layerLines.add(layerLine);
		}

		BlockStatePalette palette = new BlockStatePalette();
		Map<BlockPos, SpecialMarker> markers = new HashMap<>();
		for (String layerLine : layerLines)
		{
			if (layerLine == null) continue;
			for (int z = 0; z < sizeZ; z++)
			{
				for (int x = 0; x < sizeX; x++)
				{
					int charIdx = z * sizeX + x;
					if (charIdx >= layerLine.length()) break;
					char c = layerLine.charAt(charIdx);
					if (SpecialMarker.fromChar(c) != null) continue;
					if (c == 'A') continue;
					String mappingValue = charMap.get(c);
					palette.idFor(mappingValue != null
							? resolveBlockState(mappingValue)
							: BlockIdMapping.UNKNOWN_BLOCK);
				}
			}
		}

		// 按最终 palette bits 一次分配存储（预分配后 fill 阶段不再 resize）
		LightweightBlockContainer container = new LightweightBlockContainer(
				sizeX, sizeY, sizeZ, palette,
				new LightweightBitArray(sizeX * sizeY * sizeZ, palette.getBits()));

		for (int y = 0; y < sizeY; y++)
		{
			String layerLine = layerLines.get(y);
			if (layerLine == null) continue;

			for (int z = 0; z < sizeZ; z++)
			{
				for (int x = 0; x < sizeX; x++)
				{
					int charIdx = z * sizeX + x;
					if (charIdx >= layerLine.length()) break;

					char c = layerLine.charAt(charIdx);

					SpecialMarker marker = SpecialMarker.fromChar(c);
					if (marker != null)
					{
						markers.put(new BlockPos(x, y, z), marker);
						container.set(x, y, z, Blocks.AIR.defaultBlockState());
						continue;
					}

					if (c == 'A')
					{
						container.set(x, y, z, Blocks.AIR.defaultBlockState());
						continue;
					}

					String mappingValue = charMap.get(c);
					if (mappingValue != null)
					{
						container.set(x, y, z, resolveBlockState(mappingValue));
					}
					else
					{
						container.set(x, y, z, BlockIdMapping.UNKNOWN_BLOCK);
						LOGGER.debug("NeoSim-SimUKraftSchematicReader: unmapped char '{}' at ({},{},{}) in {}",
								c, x, y, z, fileName);
					}
				}
			}
		}

		String author = (extractedAuthor != null && !extractedAuthor.isEmpty())
				? extractedAuthor : null;

		return SchematicData.builder()
				.name(stripPkidPrefix(stripExtension(fileName)))
				.author(author)
				.type(BuildingType.OTHER)
				.format(SchematicFormat.SIM_UKRAFT_TXT)
				.sizeX(sizeX).sizeY(sizeY).sizeZ(sizeZ)
				.blockContainer(container)
				.specialMarkers(markers.isEmpty() ? null : markers)
				.build();
	}

	public static int[] parseDimensions(String line)
	{
		String[] parts = line.split("[x×]");
		if (parts.length != 3)
		{
			throw new IllegalArgumentException("Invalid dimension line: '" + line
					+ "'. Expected 'width x depth x height'.");
		}
		return new int[]{
				Integer.parseInt(parts[0].trim()),
				Integer.parseInt(parts[1].trim()),
				Integer.parseInt(parts[2].trim())
		};
	}

	private BlockState resolveBlockState(String mappingValue)
	{
		if (mappingValue == null || mappingValue.isEmpty() || "AIR".equalsIgnoreCase(mappingValue))
		{
			return Blocks.AIR.defaultBlockState();
		}
		String[] parts = mappingValue.split(":");
		int blockId = Integer.parseInt(parts[0]);
		int meta = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
		return blockIdMapping.convert(blockId, meta);
	}

	private static String stripExtension(String fileName)
	{
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	// 剥掉PKID前缀
	private static String stripPkidPrefix(String name)
	{
		if (name.startsWith("PKID"))
		{
			int hyphen = name.indexOf('-');

			// "PKID"+至少一位数字+"-"
			if (hyphen > 4)
			{
				return name.substring(hyphen + 1);
			}
		}
		return name;
	}
}

package com.wenzai.neosim.schematic.mapping;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

// 映射
public class BlockIdMapping
{
	private static final Logger LOGGER = LogUtils.getLogger();
	public static final BlockState UNKNOWN_BLOCK = Blocks.STRUCTURE_VOID.defaultBlockState();

	private final Map<String, BlockState> mapping = new HashMap<>();

	public BlockIdMapping() {}

	// 加载内置映射
	public void loadBuiltin()
	{
		String path = "/assets/neo_sim/mappings/block_id_mapping.json";
		try (InputStream is = getClass().getResourceAsStream(path))
		{
			if (is == null)
			{
				LOGGER.warn("NeoSim-BlockIdMapping: builtin mapping not found at {}", path);
				return;
			}
			try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8))
			{
				mergeFromJson(reader);
			}
			LOGGER.info("NeoSim-BlockIdMapping: loaded builtin, {} entries", mapping.size());
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-BlockIdMapping: failed to load builtin, {}", e.getMessage(), e);
		}
	}

	// 加载外部覆盖映射，合并到内置映射之上，外部条目优先（后面要改）
	public void loadExternal(Path filePath)
	{
		if (!Files.exists(filePath))
		{
			return;
		}
		try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8))
		{
			int before = mapping.size();
			mergeFromJson(reader);
			int added = mapping.size() - before;
			LOGGER.info("NeoSim-BlockIdMapping: loaded external, {} new/overridden from {}", added, filePath);
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-BlockIdMapping: failed to load external from {}, {}", filePath, e.getMessage());
		}
	}

	private void mergeFromJson(Reader reader)
	{
		JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
		for (Map.Entry<String, JsonElement> entry : root.entrySet())
		{
			String key = entry.getKey();
			String blockStateStr = entry.getValue().getAsString();
			BlockState state = parseBlockStateString(blockStateStr);
			if (state != null)
			{
				mapping.put(key, state);
			}
		}
	}

	@Nullable
	public BlockState convert(int blockId, int meta)
	{
		String key = blockId + ":" + meta;
		BlockState state = mapping.get(key);
		if (state != null)
		{
			return state;
		}
		if (meta != 0)
		{
			state = mapping.get(blockId + ":0");
			if (state != null)
			{
				return state;
			}
		}
		LOGGER.warn("NeoSim-BlockIdMapping: unmapped {}:{} → STRUCTURE_VOID", blockId, meta);
		return UNKNOWN_BLOCK;
	}

	public int size() { return mapping.size(); }

	public boolean isMapped(int blockId, int meta)
	{
		return mapping.containsKey(blockId + ":" + meta)
				|| (meta != 0 && mapping.containsKey(blockId + ":0"));
	}

	// 解析
	@Nullable
	static BlockState parseBlockStateString(String str)
	{
		if (str == null || str.isEmpty()) return null;

		String blockName;
		String propsStr = null;
		int bracketIdx = str.indexOf('[');
		if (bracketIdx >= 0 && str.endsWith("]"))
		{
			blockName = str.substring(0, bracketIdx);
			propsStr = str.substring(bracketIdx + 1, str.length() - 1);
		}
		else
		{
			blockName = str;
		}

		ResourceLocation rl = ResourceLocation.parse(blockName);
		Block block = BuiltInRegistries.BLOCK.get(rl);
		if (block == null)
		{
			LOGGER.warn("NeoSim-BlockIdMapping: unknown block '{}' in mapping", blockName);
			return null;
		}

		BlockState state = block.defaultBlockState();
		if (propsStr != null && !propsStr.isEmpty())
		{
			for (String prop : propsStr.split(","))
			{
				String[] kv = prop.split("=", 2);
				if (kv.length == 2)
				{
					Property<?> property = block.getStateDefinition().getProperty(kv[0].trim());
					if (property != null)
					{
						state = applyProperty(state, property, kv[1].trim());
					}
				}
			}
		}
		return state;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static BlockState applyProperty(BlockState state, Property property, String valueStr)
	{
		Optional<?> optValue = property.getValue(valueStr);
		if (optValue.isPresent())
		{
			return state.setValue(property, (Comparable) optValue.get());
		}
		LOGGER.warn("NeoSim-BlockIdMapping: invalid property '{}'='{}'", property.getName(), valueStr);
		return state;
	}
}

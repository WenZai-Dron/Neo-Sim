package com.wenzai.neosim.schematic;

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
	// L19：整数键 (id<<8)|meta → BlockState（构建时一次性转换，运行期免字符串拼接查表）
	private final Map<Integer, BlockState> intMapping = new HashMap<>();

	public BlockIdMapping()
	{
	}

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
				Integer intKey = parseIntKey(key);
				if (intKey != null)
				{
					// 具体 meta 优先于通配 meta:0
					intMapping.merge(intKey, state, (oldV, newV) -> newV);
				}
			}
		}
	}

	// 解析 "id:meta" 为整数键 (id<<8)|meta；无法解析返回null
	@Nullable
	private static Integer parseIntKey(String key)
	{
		int colon = key.indexOf(':');
		if (colon <= 0) return null;
		try
		{
			int id = Integer.parseInt(key.substring(0, colon));
			int meta = Integer.parseInt(key.substring(colon + 1));
			return (id << 8) | meta;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	@Nullable
	public BlockState convert(int blockId, int meta)
	{
		// L19：整数键查表，免每格字符串拼接
		BlockState state = intMapping.get((blockId << 8) | meta);
		if (state != null)
		{
			return state;
		}
		if (meta != 0)
		{
			state = intMapping.get(blockId << 8);
			if (state != null)
			{
				return state;
			}
		}
		LOGGER.warn("NeoSim-BlockIdMapping: unmapped {}:{} → STRUCTURE_VOID", blockId, meta);
		return UNKNOWN_BLOCK;
	}

	public int size()
	{
		return mapping.size();
	}

	public boolean isMapped(int blockId, int meta)
	{
		return intMapping.containsKey((blockId << 8) | meta)
				|| (meta != 0 && intMapping.containsKey(blockId << 8));
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
		// 注意：BuiltInRegistries.BLOCK 是带默认值的注册表，get() 对不存在的 id 返回 air 而不是 null，
		// 所以必须用 containsKey 判断：否则「名字改了/写错了」的映射条目会**静默变成空气**，
		// 加载 500 多条的表时一声不响（1.21.1 把 grass_path 改名成 dirt_path 就是这么丢的）
		if (!BuiltInRegistries.BLOCK.containsKey(rl))
		{
			LOGGER.warn("NeoSim-BlockIdMapping: block '{}' does not exist in this version (renamed or removed?) — treated as air", blockName);
			return Blocks.AIR.defaultBlockState();
		}
		Block block = BuiltInRegistries.BLOCK.get(rl);

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

package com.wenzai.neosim.schematic;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.schematic.mapping.BlockIdMapping;
import com.wenzai.neosim.schematic.reader.ISchematicReader;
import com.wenzai.neosim.schematic.reader.LitematicaSchematicReader;
import com.wenzai.neosim.schematic.reader.SimUKraftSchematicReader;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

// 蓝图注册
public class SchematicRegistry
{

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final SchematicRegistry INSTANCE = new SchematicRegistry();

	private static final String OLD_BASE = "assets/neo_sim/buildings/old";
	private static final String NEW_BASE = "assets/neo_sim/buildings/new";

	// L22：体积预算——单文件超过 64MB 或总加载超过 256MB 直接跳过（防大 .litematic 把全部蓝图常驻内存吃爆）
	private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
	private static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
	private static long totalLoadedBytes = 0L;

	private final Map<String, SchematicData> loadedSchematics = new ConcurrentHashMap<>();
	private final Map<BuildingType, List<SchematicData>> byType = new ConcurrentHashMap<>();
	private final BlockIdMapping blockIdMapping = new BlockIdMapping();

	// 自定义蓝图
	private final Map<String, Long> customFileStamps = new ConcurrentHashMap<>();

	private volatile boolean loaded = false;

	// 只允许一个端加载线程
	private final java.util.concurrent.atomic.AtomicBoolean initStarted = new java.util.concurrent.atomic.AtomicBoolean();

	private SchematicRegistry()
	{
		for (BuildingType type : BuildingType.values())
		{
			byType.put(type, new CopyOnWriteArrayList<>());
		}
	}

	public static SchematicRegistry getInstance() { return INSTANCE; }

	// 异步加载资源中的蓝图
	public void initializeAsync()
	{
		if (!initStarted.compareAndSet(false, true)) return;
		blockIdMapping.loadBuiltin();
		Path externalMapping = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("block_id_mapping.json");
		if (Files.exists(externalMapping))
		{
			blockIdMapping.loadExternal(externalMapping);
		}

		Thread loader = new Thread(() ->
		{
			LOGGER.info("NeoSim-SchematicRegistry: loading from classpath resources");
			for (BuildingType type : BuildingType.values())
			{
				String dir = OLD_BASE + "/" + type.name().toLowerCase();
				loadClasspathDir(dir, new SimUKraftSchematicReader(blockIdMapping), type);
			}
			for (BuildingType type : BuildingType.values())
			{
				String dir = NEW_BASE + "/" + type.name().toLowerCase();
				loadClasspathDir(dir, new LitematicaSchematicReader(), type);
			}
			// new/ 根目录下的文件默认归类为 OTHER
			loadClasspathDir(NEW_BASE, new LitematicaSchematicReader(), BuildingType.OTHER);

			// 游戏根目录自定义蓝图
			loadCustomDir();

			loaded = true;
			LOGGER.info("NeoSim-SchematicRegistry: load complete, total={} schematics", loadedSchematics.size());
			for (BuildingType type : BuildingType.values())
			{
				LOGGER.info("NeoSim-SchematicRegistry:   {} = {} blueprints", type.name(), byType.get(type).size());
			}
		}, "SchematicLoader");
		loader.setDaemon(true);
		loader.start();
	}

	public boolean isLoaded() { return loaded; }

	private void loadClasspathDir(String classpathDir, ISchematicReader reader, BuildingType type)
	{
		boolean loaded = false;

		URL url = NeoSim.class.getResource("/" + classpathDir);
		if (url != null)
		{
			try
			{
				loaded = loadDir(classpathDirPath(url), reader, type);
			}
			catch (Exception e)
			{
				// 资源目录读取失败只跳过该目录，不杀死加载线程
				LOGGER.error("NeoSim-SchematicRegistry: bad resource — {}, {}", classpathDir, e.getMessage(), e);
			}
		}

		if (!loaded)
		{
			Path devDir = FMLPaths.GAMEDIR.get().getParent().resolve("src").resolve("main").resolve("resources").resolve(classpathDir);
			if (Files.isDirectory(devDir))
			{
				loadDir(devDir, reader, type);
			}
		}
	}

	private static Path classpathDirPath(URL url) throws URISyntaxException, IOException
	{
		if ("jar".equals(url.getProtocol()))
		{
			String spec = url.toExternalForm();
			URI jarUri = URI.create(spec.substring(0, spec.indexOf('!')));
			FileSystem fs;
			try
			{
				fs = FileSystems.newFileSystem(jarUri, Map.of());
			}
			catch (FileSystemAlreadyExistsException e)
			{
				fs = FileSystems.getFileSystem(jarUri);
			}
			return fs.getPath(spec.substring(spec.indexOf('!') + 1));
		}
		return Path.of(url.toURI());
	}

	// 返回 true表示成功加载到了文件
	private boolean loadDir(Path dirPath, ISchematicReader reader, BuildingType type)
	{
		if (!Files.isDirectory(dirPath)) return false;

		try (Stream<Path> files = Files.list(dirPath))
		{
			List<Path> fileList = files.filter(Files::isRegularFile)
					.filter(f ->
					{
						String name = f.getFileName().toString();
						return name.endsWith(".txt") || name.endsWith(".litematic");
					})
					.toList();

			LOGGER.info("NeoSim-SchematicRegistry: {} '{}' — {} files", type, dirPath, fileList.size());

			for (Path file : fileList)
			{
				// L22：体积预算——超限文件跳过，总加载超限则停止本目录
				try
				{
					long sz = Files.size(file);
					if (sz > MAX_FILE_BYTES)
					{
						LOGGER.warn("NeoSim-SchematicRegistry: skip oversized {} ({} bytes)", file.getFileName(), sz);
						continue;
					}
					if (totalLoadedBytes + sz > MAX_TOTAL_BYTES)
					{
						LOGGER.warn("NeoSim-SchematicRegistry: total volume budget exceeded at {}", file.getFileName());
						break;
					}
					totalLoadedBytes += sz;
				}
				catch (IOException e)
				{
					LOGGER.warn("NeoSim-SchematicRegistry: stat failed {}, skip", file.getFileName());
					continue;
				}
				try
				{
					SchematicData data = reader.read(file);
					SchematicData typed = SchematicData.builder()
							.name(data.getName()).author(data.getAuthor()).description(data.getDescription())
							.type(type)
							.format(data.getFormat())
							.timeCreated(data.getTimeCreated()).timeModified(data.getTimeModified())
							.sizeX(data.getSizeX()).sizeY(data.getSizeY()).sizeZ(data.getSizeZ())
							.blockContainer(data.getBlockContainer())
							.tileEntities(data.getTileEntities().isEmpty() ? null : data.getTileEntities())
							.entities(data.getEntities().isEmpty() ? null : data.getEntities())
							.specialMarkers(data.getSpecialMarkers().isEmpty() ? null : data.getSpecialMarkers())
							.build();

					loadedSchematics.put(typed.getName(), typed);
					byType.get(type).add(typed);
				}
				catch (Exception e)
				{
					// 单个文件损坏只跳过该文件，不影响其余蓝图
					LOGGER.error("NeoSim-SchematicRegistry: failed {}, {}", file.getFileName(), e.getMessage(), e);
				}
			}
			return !fileList.isEmpty();
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-SchematicRegistry: scan error {}, {}", dirPath, e.getMessage());
			return false;
		}
	}

	// 自定义蓝图目录
	private Path customDir()
	{
		return FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("Buildings");
	}

	// 全量重载自定义蓝图
	private void loadCustomDir()
	{
		Path dir = customDir();
		if (!Files.isDirectory(dir)) return;

		List<Path> fileList;
		try (Stream<Path> files = Files.list(dir))
		{
			fileList = files.filter(Files::isRegularFile)
					.filter(f ->
					{
						String name = f.getFileName().toString().toLowerCase();
						return name.endsWith(".txt") || name.endsWith(".litematic");
					})
					.toList();
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-SchematicRegistry: custom dir scan error — {}", e.getMessage());
			return;
		}

		List<SchematicData> customList = byType.get(BuildingType.CUSTOM);
		customList.clear();
		loadedSchematics.entrySet().removeIf(e -> e.getValue().getType() == BuildingType.CUSTOM);
		customFileStamps.clear();

		for (Path file : fileList)
		{
			// L22：体积预算——超限文件跳过，总加载超限则停止
			try
			{
				long sz = Files.size(file);
				if (sz > MAX_FILE_BYTES)
				{
					LOGGER.warn("NeoSim-SchematicRegistry: skip oversized custom {} ({} bytes)", file.getFileName(), sz);
					continue;
				}
				if (totalLoadedBytes + sz > MAX_TOTAL_BYTES)
				{
					LOGGER.warn("NeoSim-SchematicRegistry: total volume budget exceeded at custom {}", file.getFileName());
					break;
				}
				totalLoadedBytes += sz;
			}
			catch (IOException e)
			{
				LOGGER.warn("NeoSim-SchematicRegistry: stat failed custom {}, skip", file.getFileName());
				continue;
			}
			try
			{
				customFileStamps.put(file.getFileName().toString(), Files.getLastModifiedTime(file).toMillis());

				// 按扩展名选择解析器
				String fileName = file.getFileName().toString().toLowerCase();
				ISchematicReader reader = fileName.endsWith(".txt")
						? new SimUKraftSchematicReader(blockIdMapping)
						: new LitematicaSchematicReader();
				SchematicData data = reader.read(file);
				SchematicData typed = SchematicData.builder()
						.name(data.getName()).author(data.getAuthor()).description(data.getDescription())
						.type(BuildingType.CUSTOM)
						.format(data.getFormat())
						.timeCreated(data.getTimeCreated()).timeModified(data.getTimeModified())
						.sizeX(data.getSizeX()).sizeY(data.getSizeY()).sizeZ(data.getSizeZ())
						.blockContainer(data.getBlockContainer())
						.tileEntities(data.getTileEntities().isEmpty() ? null : data.getTileEntities())
						.entities(data.getEntities().isEmpty() ? null : data.getEntities())
						.specialMarkers(data.getSpecialMarkers().isEmpty() ? null : data.getSpecialMarkers())
						.build();

				loadedSchematics.put(typed.getName(), typed);
				customList.add(typed);
			}
			catch (Exception e)
			{
				// 单个自定义文件损坏只跳过该文件
				LOGGER.error("NeoSim-SchematicRegistry: custom failed {}, {}", file.getFileName(), e.getMessage(), e);
			}
		}
		LOGGER.info("NeoSim-SchematicRegistry: custom '{}' — {} blueprints", dir, customList.size());
	}

	// 打开自定义页时调用：目录内容或文件修改时间没变化就直接跳过，避免每页重复解析大文件
	public void refreshCustom()
	{
		Path dir = customDir();
		if (!Files.isDirectory(dir))
		{
			if (!customFileStamps.isEmpty())
			{
				customFileStamps.clear();
				byType.get(BuildingType.CUSTOM).clear();
				loadedSchematics.entrySet().removeIf(e -> e.getValue().getType() == BuildingType.CUSTOM);
			}
			return;
		}

		Map<String, Long> current = new HashMap<>();
		try (Stream<Path> files = Files.list(dir))
		{
			for (Path f : files.filter(Files::isRegularFile).toList())
			{
				String n = f.getFileName().toString().toLowerCase();
				if (!n.endsWith(".txt") && !n.endsWith(".litematic")) continue;
				try
				{
					current.put(f.getFileName().toString(), Files.getLastModifiedTime(f).toMillis());
				}
				catch (IOException ignored) {}
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-SchematicRegistry: custom refresh scan error — {}", e.getMessage());
			return;
		}

		if (current.equals(customFileStamps)) return;
		loadCustomDir();
	}

	public Map<String, SchematicData> getAll() { return Collections.unmodifiableMap(loadedSchematics); }
	public int size() { return loadedSchematics.size(); }

	@Nullable
	public SchematicData get(String name) { return loadedSchematics.get(name); }

	public List<SchematicData> getByType(BuildingType type)
	{
		return Collections.unmodifiableList(byType.getOrDefault(type, List.of()));
	}

	public Set<String> getAllAuthors()
	{
		Set<String> authors = new TreeSet<>();
		for (SchematicData data : loadedSchematics.values())
		{
			authors.add(data.getAuthor());
		}
		return authors;
	}

	// 搜索
	public List<SchematicData> search(String query)
	{
		if (query == null || query.isBlank())
		{
			List<SchematicData> all = new ArrayList<>(loadedSchematics.values());
			all.sort(Comparator.comparing(SchematicData::getName, String.CASE_INSENSITIVE_ORDER));
			return all;
		}

		String q = query.trim();

		if (q.startsWith("/author "))
		{
			String author = q.substring(8).trim().toLowerCase();
			List<SchematicData> result = new ArrayList<>();
			for (SchematicData data : loadedSchematics.values())
			{
				if (data.getAuthor().toLowerCase().contains(author))
				{
					result.add(data);
				}
			}
			result.sort(Comparator.comparing(SchematicData::getName, String.CASE_INSENSITIVE_ORDER));
			return result;
		}

		if (q.startsWith("/size "))
		{
			int[] dims = parseSizeFilter(q.substring(6).trim());
			if (dims != null)
			{
				List<SchematicData> result = new ArrayList<>();
				for (SchematicData data : loadedSchematics.values())
				{
					if (data.getSizeX() == dims[0] && data.getSizeY() == dims[1] && data.getSizeZ() == dims[2])
					{
						result.add(data);
					}
				}
				result.sort(Comparator.comparing(SchematicData::getName, String.CASE_INSENSITIVE_ORDER));
				return result;
			}
		}

		List<String> words = new ArrayList<>();
		Integer filterW = null, filterD = null, filterH = null;

		for (String token : q.split("\\s+"))
		{
			if (token.startsWith("w:") || token.startsWith("W:"))
			{
				try { filterW = Integer.parseInt(token.substring(2)); } catch (NumberFormatException ignored) {}
			}
			else if (token.startsWith("d:") || token.startsWith("D:"))
			{
				try { filterD = Integer.parseInt(token.substring(2)); } catch (NumberFormatException ignored) {}
			}
			else if (token.startsWith("h:") || token.startsWith("H:"))
			{
				try { filterH = Integer.parseInt(token.substring(2)); } catch (NumberFormatException ignored) {}
			}
			else
			{
				words.add(token.toLowerCase());
			}
		}

		List<SchematicData> result = new ArrayList<>();
		for (SchematicData data : loadedSchematics.values())
		{
			if (filterW != null && data.getSizeX() != filterW) continue;
			if (filterD != null && data.getSizeZ() != filterD) continue;
			if (filterH != null && data.getSizeY() != filterH) continue;

			if (!words.isEmpty())
			{
				boolean allMatch = true;
				for (String word : words)
				{
					if (!data.getName().toLowerCase().contains(word)
							&& !data.getAuthor().toLowerCase().contains(word))
					{
						allMatch = false;
						break;
					}
				}
				if (!allMatch) continue;
			}

			result.add(data);
		}

		result.sort(Comparator.comparing(SchematicData::getName, String.CASE_INSENSITIVE_ORDER));
		return result;
	}

	public List<String> suggest(String query)
	{
		if (query == null || query.isBlank()) return List.of();
		String lower = query.trim().toLowerCase();
		List<Map.Entry<String, Integer>> distances = new ArrayList<>();

		for (String name : loadedSchematics.keySet())
		{
			int dist = levenshtein(lower, name.toLowerCase());
			if (dist <= 3)
			{
				distances.add(new AbstractMap.SimpleEntry<>(name, dist));
			}
		}

		distances.sort(Map.Entry.comparingByValue());
		List<String> result = new ArrayList<>();
		for (int i = 0; i < Math.min(3, distances.size()); i++)
		{
			result.add(distances.get(i).getKey());
		}
		return result;
	}

	@Nullable
	private static int[] parseSizeFilter(String s)
	{
		String[] parts = s.split("[x×]");
		if (parts.length != 3) return null;
		try
		{
			return new int[]{
					Integer.parseInt(parts[0].trim()),
					Integer.parseInt(parts[1].trim()),
					Integer.parseInt(parts[2].trim())
			};
		}
		catch (NumberFormatException e) { return null; }
	}

	private static int levenshtein(String a, String b)
	{
		int[][] dp = new int[a.length() + 1][b.length() + 1];
		for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
		for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
		for (int i = 1; i <= a.length(); i++)
		{
			for (int j = 1; j <= b.length(); j++)
			{
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
			}
		}
		return dp[a.length()][b.length()];
	}

}

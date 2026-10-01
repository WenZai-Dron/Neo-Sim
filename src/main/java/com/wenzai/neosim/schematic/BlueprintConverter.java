package com.wenzai.neosim.schematic;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nullable;

// .txt（Sim-U-Kraft 旧版）→ .litematic 转换核心，GUI 与命令共用。
//
// 为什么要转：作者名只活在 .txt 第 2 行的 AU= 里，转成 .litematic 后进 Metadata.Author，
// 而 Metadata.Author 在 Litematica 的元数据白名单里，用 Litematica 再存一次也不会丢。
//
// 坐标系：.txt 的数据在「作者帧」（SchematicFrame.SUKRAFT），.litematic 存的是世界帧。
// 两帧之间就是 C = 左右镜像 + 逆时针 90°，位置和方块状态都必须走同一条变换：
//   位置  C(x,y,z) = (-z, y, -x)            等价于 CoordTransform.transformPos(p, LEFT_RIGHT, CCW90)
//   状态  state.mirror(LEFT_RIGHT).rotate(CCW90)
// C 在平移意义下自反（C∘C = 恒等），所以将来做反向转换时同一套代码可以复用。
// 用 MC 自己的 mirror/rotate（而不是像放置路径那样只手改 facing）能顺带把铁轨 shape、
// 原木 axis、藤蔓四面的布尔值一起转对。
public final class BlueprintConverter
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// 一列可转换的源文件（列表页只读头两行，不必整份解析）
	public record Source(Path file, @Nullable String author, String dimensions, long sizeBytes,
						 @Nullable String problem)
	{
		public String fileName()
		{
			return file.getFileName().toString();
		}
	}

	// 一次转换的结果（output == null 表示失败，error 里是原因）
	public record Outcome(Path source, @Nullable Path output, String name, @Nullable String author,
						  int blocks, int paletteSize, @Nullable String error)
	{
		public boolean ok()
		{
			return output != null;
		}
	}

	private BlueprintConverter()
	{
	}

	// 自定义蓝图目录（与蓝图库注册表同一个源）
	public static Path customDir()
	{
		return SchematicRegistry.getInstance().customDir();
	}

	// 已经转换过：同名 .litematic 就在旁边（列表里不再显示这类 .txt）
	public static boolean isConverted(Path txt)
	{
		return Files.isRegularFile(outputFor(txt));
	}

	public static Path outputFor(Path txt)
	{
		String name = txt.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return txt.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".litematic");
	}

	// 列出自定义目录下所有「还没转换过」的 .txt
	public static List<Source> listConvertible()
	{
		Path dir = customDir();
		if (!Files.isDirectory(dir)) return List.of();

		List<Source> sources = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir))
		{
			for (Path file : files.filter(Files::isRegularFile).toList())
			{
				if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt")) continue;
				if (isConverted(file)) continue;
				sources.add(describe(file));
			}
		}
		catch (IOException e)
		{
			LOGGER.error("NeoSim-BlueprintConverter: custom dir scan failed — {}", e.getMessage());
		}

		sources.sort(Comparator.comparing(s -> s.file().getFileName().toString().toLowerCase(Locale.ROOT)));
		return sources;
	}

	// 读头两行做列表展示：尺寸 + AU 作者（读不了也不挡着转换，problem 交给界面显示）
	private static Source describe(Path file)
	{
		String dimensions = "?";
		String author = null;
		String problem = null;

		try (BufferedReader reader = Files.newBufferedReader(file))
		{
			String dimLine = reader.readLine();
			String mapLine = reader.readLine();

			if (dimLine != null)
			{
				try
				{
					// 第 1 行是「宽 x 深 x 高」，展示按 X×Y×Z 排
					int[] d = SimUKraftSchematicReader.parseDimensions(dimLine.trim());
					dimensions = d[0] + "×" + d[2] + "×" + d[1];
				}
				catch (RuntimeException e)
				{
					problem = "尺寸行无法解析";
				}
			}

			if (mapLine != null)
			{
				author = SimUKraftSchematicReader.parseAuthorFromMapLine(mapLine);
			}
		}
		catch (IOException e)
		{
			problem = e.getMessage();
		}

		long sizeBytes = 0L;
		try
		{
			sizeBytes = Files.size(file);
		}
		catch (IOException ignored)
		{
		}

		return new Source(file, author, dimensions, sizeBytes, problem);
	}

	// 转换一个文件：解析 → 世界帧烘焙 → 写 .litematic → 读回自校验
	// authorOverride 为空时用文件里的 AU=，都没有则 Unknown；operator 只进 Description 留痕
	public static Outcome convert(Path txt, @Nullable String authorOverride, @Nullable String operator)
	{
		String fileName = txt.getFileName().toString();
		try
		{
			SchematicData source = SchematicRegistry.getInstance().readerFor(txt).read(txt);
			if (source.getFormat() != SchematicFormat.SIM_UKRAFT_TXT)
			{
				return new Outcome(txt, null, fileName, null, 0, 0, "不是 Sim-U-Kraft 的 .txt 蓝图");
			}

			// AU= 原值永远留在 Description 里，方便回溯异拼/缺失的情况
			String rawAuthor = source.getAuthor();
			String author = resolveAuthor(authorOverride, rawAuthor);

			SchematicData baked = bakeToWorldFrame(source, author,
					provenance(fileName, rawAuthor, operator));

			Path output = outputFor(txt);
			LitematicaSchematicWriter.write(baked, output);

			// 自校验：用模组自己的读取器读回来逐格比对，任何一格对不上就删掉产物当失败处理
			String problem = verifyRoundTrip(baked, output);
			if (problem != null)
			{
				Files.deleteIfExists(output);
				LOGGER.error("NeoSim-BlueprintConverter: {} round-trip check failed — {}", fileName, problem);
				return new Outcome(txt, null, baked.getName(), author, 0, 0, problem);
			}

			LightweightBlockContainer container = baked.getBlockContainer();
			LOGGER.info("NeoSim-BlueprintConverter: {} -> {} (author {}, {} blocks, {} palette entries)",
					fileName, output.getFileName(), author,
					container.countSolidBlocks(), container.getPalette().size());

			return new Outcome(txt, output, baked.getName(), author,
					container.countSolidBlocks(), container.getPalette().size(), null);
		}
		// LinkageError 也要接：别的模组给方块状态变换挂的 mixin 出问题时抛的是
		// ExceptionInInitializerError/NoClassDefFoundError（Error 不是 Exception），
		// 一份蓝图坏掉不该把整批转换带崩
		catch (Exception | LinkageError e)
		{
			LOGGER.error("NeoSim-BlueprintConverter: {} failed to convert — {}", fileName, e.getMessage(), e);
			return new Outcome(txt, null, fileName, null, 0, 0,
					e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}
	}

	// 同一人的异拼统一（与外部转换工具同一张表）；原始值不进 Description 也能从源 .txt 查到
	private static final Map<String, String> AUTHOR_ALIASES = Map.of(
			"Spatial Heather", "SpatialHeather",
			"hikachan", "Hikachan",
			"Mr.Bongman", "Mr. Bongman",
			"xreyakx@aim.com", "xreyakx",
			"Subby", "Subby72",
			"Robbw2811", "Robbe2811");

	// 明显不是人名的占位值 → Unknown
	private static final Set<String> BAD_AUTHOR_VALUES = Set.of("%", "ASYM", "UNKNOWN", "NONE", "NULL");

	// 作者优先级：界面填的名字 > 文件里的 AU= > Unknown。
	// 内置那 154 份的"没有 AU= 就记老版默认作者 Satscape"是给官方建筑的默认值，
	// 玩家自己的文件不能替他冒领，所以这里缺失一律 Unknown
	private static String resolveAuthor(@Nullable String override, @Nullable String rawAuthor)
	{
		if (override != null && !override.isBlank()) return override.trim();

		String value = SimUKraftSchematicReader.cleanAuthorValue(rawAuthor);
		if (value.isEmpty() || "null".equalsIgnoreCase(value)) return "Unknown";
		if (BAD_AUTHOR_VALUES.contains(value.toUpperCase(Locale.ROOT))) return "Unknown";
		return AUTHOR_ALIASES.getOrDefault(value, value);
	}

	private static String provenance(String fileName, @Nullable String rawAuthor, @Nullable String operator)
	{
		StringBuilder sb = new StringBuilder("由 ").append(fileName).append(" 转换");
		if (rawAuthor != null && !rawAuthor.isBlank())
		{
			sb.append("（AU=").append(rawAuthor.trim()).append("）");
		}
		if (operator != null && !operator.isBlank())
		{
			sb.append("，转换者 ").append(operator.trim());
		}
		return sb.toString();
	}

	// ---- 烘焙：作者帧（.txt）→ 世界帧（.litematic） ----

	public static SchematicData bakeToWorldFrame(SchematicData source, String author, String description)
	{
		LightweightBlockContainer in = source.getBlockContainer();
		int sizeX = in.getSizeX();
		int sizeY = in.getSizeY();
		int sizeZ = in.getSizeZ();

		// C 把长宽互换：输出 X 来自源 Z，输出 Z 来自源 X；C 之后整体平移 (+sizeZ-1, 0, +sizeX-1) 保证坐标非负
		LightweightBlockContainer out = new LightweightBlockContainer(sizeZ, sizeY, sizeX);

		Map<BlockPos, SpecialMarker> markers = source.getSpecialMarkers();
		boolean hasMarkers = markers != null && !markers.isEmpty();

		// 生活点标记在文件里落成 neo_sim:living_point 方块，读取器会把它反解回标记，
		// 所以位置要一起转过去记下来，写回自校验才对得上（其余标记在读取侧就是普通方块）
		Map<BlockPos, SpecialMarker> livingPoints = new HashMap<>();

		for (int y = 0; y < sizeY; y++)
		{
			for (int z = 0; z < sizeZ; z++)
			{
				for (int x = 0; x < sizeX; x++)
				{
					int outX = (sizeZ - 1) - z;
					int outZ = (sizeX - 1) - x;
					BlockState state = in.get(x, y, z);

					if (hasMarkers)
					{
						SpecialMarker marker = markers.get(new BlockPos(x, y, z));
						if (marker != null)
						{
							state = materialize(marker);
							if (marker == SpecialMarker.LIVING_POINT)
							{
								livingPoints.put(new BlockPos(outX, y, outZ), marker);
							}
						}
					}

					if (state.isAir()) continue;
					out.set(outX, y, outZ, bakeState(state));
				}
			}
		}

		// 贴合类方块的朝向按几何纠正（旧版 .txt 的方向约定和几何对不上，见 repairWallAttachments 注释）
		int repaired = repairWallAttachments(out);
		if (repaired > 0)
		{
			LOGGER.info("NeoSim-BlueprintConverter: {} repaired {} wall-attached block facings", source.getName(), repaired);
		}

		return SchematicData.builder()
				.name(source.getName())
				.author(author)
				.description(description)
				.type(source.getType())
				.format(SchematicFormat.LITEMATICA)
				.timeCreated(source.getTimeCreated())
				.sizeX(sizeZ).sizeY(sizeY).sizeZ(sizeX)
				.blockContainer(out)
				.specialMarkers(livingPoints.isEmpty() ? null : livingPoints)
				.build();
	}

	// ---- 贴合类方块的朝向修复 ----
	//
	// 为什么需要：旧版 .txt 里方块元数据的"方向约定"和它自己的几何对不上——把位置和状态都按
	// 同一条变换搬到世界帧之后，全量 154 份里仍有 340/617 个贴合类方块（梯子、墙上火把、墙上告示牌…）
	// 把朝向指进空气里，落地就会弹掉。元数据本身已经不可靠（同一个 meta 在不同蓝图里指向不同的支撑方向），
	// 所以只能按**几何**把朝向改对：支撑必须落在实心邻居那一侧。
	// 判定用 MC 自己的 canOcclude()（整面方块为真；台阶/楼梯/栅栏/雪片为假），比按名字硬编码可靠。
	private static int repairWallAttachments(LightweightBlockContainer container)
	{
		int repaired = 0;

		for (int y = 0; y < container.getSizeY(); y++)
		{
			for (int z = 0; z < container.getSizeZ(); z++)
			{
				for (int x = 0; x < container.getSizeX(); x++)
				{
					BlockState state = container.get(x, y, z);
					if (!isWallAttached(state) || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
					{
						continue;
					}

					Direction current = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
					if (hasSupport(container, x, y, z, current.getOpposite()))
					{
						continue;   // 朝向已经落在实心邻居上
					}

					Direction picked = null;
					for (Direction dir : Direction.Plane.HORIZONTAL)
					{
						if (hasSupport(container, x, y, z, dir))
						{
							picked = dir;
							break;
						}
					}
					if (picked == null)
					{
						continue;   // 周围没有可贴的面：保持原样（这种方块本来就放不住）
					}

					container.set(x, y, z, state.setValue(BlockStateProperties.HORIZONTAL_FACING,
							picked.getOpposite()));
					repaired++;
				}
			}
		}

		return repaired;
	}

	// 贴合类方块：朝向 = 背靠支撑那一侧的相反方向
	private static boolean isWallAttached(BlockState state)
	{
		Block block = state.getBlock();
		if (block instanceof WallTorchBlock || block instanceof RedstoneWallTorchBlock) return true;
		if (block instanceof LadderBlock || block instanceof TripWireHookBlock) return true;
		if (block instanceof WallSignBlock || block instanceof WallHangingSignBlock
				|| block instanceof WallBannerBlock) return true;
		return (block instanceof LeverBlock || block instanceof ButtonBlock)
				&& state.hasProperty(BlockStateProperties.ATTACH_FACE)
				&& state.getValue(BlockStateProperties.ATTACH_FACE) == AttachFace.WALL;
	}

	private static boolean hasSupport(LightweightBlockContainer container, int x, int y, int z, Direction dir)
	{
		int nx = x + dir.getStepX();
		int nz = z + dir.getStepZ();
		if (nx < 0 || nz < 0 || nx >= container.getSizeX() || nz >= container.getSizeZ())
		{
			return false;
		}
		return container.get(nx, y, nz).canOcclude();
	}

	// 标记在 .txt 里是字符，.litematic / Litematica 只认方块，所以烘焙时必须落成方块。
	// 生活点方块会被读取器反解回 LIVING_POINT 标记（LitematicaSchematicReader），
	// 所以转换前后模组看到的标记语义完全一致，Litematica 里也是看得见的方块。
	private static BlockState materialize(SpecialMarker marker)
	{
		if (marker == SpecialMarker.LIVING_POINT)
		{
			return ModBlocks.LIVING_POINT.get().defaultBlockState();
		}
		BlockState state = marker.toBlockState();
		return state != null ? state : Blocks.AIR.defaultBlockState();
	}

	// 方块状态同一条 C：先镜像再逆时针 90°（和位置映射配套，别单独改一个）
	private static BlockState bakeState(BlockState state)
	{
		return state.mirror(Mirror.LEFT_RIGHT).rotate(Rotation.COUNTERCLOCKWISE_90);
	}

	// ---- 写回自校验 ----

	// 读回产物，逐格与烘焙结果比对；返回 null 表示一致，否则返回问题描述。
	// 生活点方块读回来会变成空气 + LIVING_POINT 标记，两边都按空气比较标记格。
	private static String verifyRoundTrip(SchematicData expected, Path file)
	{
		SchematicData readBack;
		try
		{
			readBack = new LitematicaSchematicReader().read(file);
		}
		catch (IOException e)
		{
			return "读回失败：" + e.getMessage();
		}

		LightweightBlockContainer a = expected.getBlockContainer();
		LightweightBlockContainer b = readBack.getBlockContainer();

		if (a.getSizeX() != b.getSizeX() || a.getSizeY() != b.getSizeY() || a.getSizeZ() != b.getSizeZ())
		{
			return "读回尺寸不一致：" + a.getDimensionString() + " vs " + b.getDimensionString();
		}

		int mismatches = 0;
		String first = null;
		for (int y = 0; y < a.getSizeY(); y++)
		{
			for (int z = 0; z < a.getSizeZ(); z++)
			{
				for (int x = 0; x < a.getSizeX(); x++)
				{
					BlockState expectedState = comparable(a.get(x, y, z));
					BlockState actualState = comparable(b.get(x, y, z));
					if (expectedState.equals(actualState)) continue;

					mismatches++;
					if (first == null)
					{
						first = "(" + x + "," + y + "," + z + ") " + expectedState + " vs " + actualState;
					}
				}
			}
		}

		if (mismatches > 0)
		{
			return mismatches + " 格读回不一致，首处 " + first;
		}

		// 生活点标记也必须原样读回来
		Map<BlockPos, SpecialMarker> expectedMarkers = expected.getSpecialMarkers();
		Map<BlockPos, SpecialMarker> actualMarkers = readBack.getSpecialMarkers();
		int expectedLiving = expectedMarkers == null ? 0 : countLivingPoints(expectedMarkers);
		int actualLiving = actualMarkers == null ? 0 : countLivingPoints(actualMarkers);
		if (expectedLiving != actualLiving)
		{
			return "生活点标记数量不一致：" + expectedLiving + " vs " + actualLiving;
		}

		if (!expected.getName().equals(readBack.getName()))
		{
			return "读回名字不一致：" + expected.getName() + " vs " + readBack.getName();
		}

		if (expected.getAuthor() != null && !expected.getAuthor().equals(readBack.getAuthor()))
		{
			return "读回作者不一致：" + expected.getAuthor() + " vs " + readBack.getAuthor();
		}

		return null;
	}

	private static int countLivingPoints(Map<BlockPos, SpecialMarker> markers)
	{
		int count = 0;
		for (SpecialMarker marker : markers.values())
		{
			if (marker == SpecialMarker.LIVING_POINT) count++;
		}
		return count;
	}

	private static BlockState comparable(BlockState state)
	{
		return state.getBlock() == ModBlocks.LIVING_POINT.get()
				? Blocks.AIR.defaultBlockState() : state;
	}
}

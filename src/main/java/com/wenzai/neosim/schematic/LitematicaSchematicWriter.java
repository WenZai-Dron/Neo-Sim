package com.wenzai.neosim.schematic;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

// .litematic（Litematica v7）写入：把 SchematicData 落成单 region 的投影文件。
// 与读取侧严格同口径（LitematicaSchematicReader / BlockStatePalette）：
//   palette[0] 固定 air、region 索引 (y*sizeZ+z)*sizeX+x、BlockStates 是 LSB-first 打包的 long[]，
//   bits 由 BlockStatePalette 给出（最低 2 bit，和 Litematica 的 Math.max(2, ...) 一致）
public final class LitematicaSchematicWriter
{
	private static final int VERSION = 7;
	private static final int SUB_VERSION = 1;
	private static final String REGION_NAME = "main";

	private LitematicaSchematicWriter()
	{
	}

	// 写出文件（父目录不存在时创建）
	public static void write(SchematicData data, Path outFile) throws IOException
	{
		Path parent = outFile.getParent();
		if (parent != null)
		{
			Files.createDirectories(parent);
		}

		try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(outFile)))
		{
			NbtIo.writeCompressed(buildTag(data), out);
		}
	}

	// 组装完整 NBT（单测/预览用，与 write 写出的内容一致）
	public static CompoundTag buildTag(SchematicData data)
	{
		CompoundTag root = new CompoundTag();
		root.putInt("Version", VERSION);
		root.putInt("SubVersion", SUB_VERSION);
		// 用运行期数据版本，升级 MC 时不用手改常量
		root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
		root.put("Metadata", metadataTag(data));

		CompoundTag regions = new CompoundTag();
		regions.put(REGION_NAME, regionTag(data));
		root.put("Regions", regions);
		return root;
	}

	private static CompoundTag metadataTag(SchematicData data)
	{
		LightweightBlockContainer container = data.getBlockContainer();
		long now = System.currentTimeMillis();

		CompoundTag meta = new CompoundTag();
		meta.putString("Name", data.getName() == null ? "" : data.getName());
		meta.putString("Author", data.getAuthor() == null || data.getAuthor().isEmpty()
				? "Unknown" : data.getAuthor());
		meta.putString("Description", data.getDescription() == null ? "" : data.getDescription());
		meta.putInt("RegionCount", 1);
		meta.putLong("TotalVolume", container.getTotalVolume());
		meta.putInt("TotalBlocks", container.countSolidBlocks());
		// 源文件没有时间戳时用当前时间，避免写出 0 让 Litematica 显示 1970
		meta.putLong("TimeCreated", data.getTimeCreated() > 0L ? data.getTimeCreated() : now);
		meta.putLong("TimeModified", now);

		// Litematica 的 BlockSize 用小写 x/y/z；读取侧两种都认，这里跟 Litematica 一致
		CompoundTag enclosing = new CompoundTag();
		enclosing.putInt("x", container.getSizeX());
		enclosing.putInt("y", container.getSizeY());
		enclosing.putInt("z", container.getSizeZ());
		meta.put("EnclosingSize", enclosing);
		return meta;
	}

	private static CompoundTag regionTag(SchematicData data)
	{
		LightweightBlockContainer container = data.getBlockContainer();

		CompoundTag region = new CompoundTag();
		region.put("Position", posTag(0, 0, 0));
		region.put("Size", posTag(container.getSizeX(), container.getSizeY(), container.getSizeZ()));
		region.put("BlockStatePalette", container.writePaletteToNBT());
		region.putLongArray("BlockStates", container.writeBlockStatesToNBT());

		// .txt 来源没有方块实体 / 实体，写空表；Litematica 读空表是安全的
		region.put("TileEntities", new ListTag());
		region.put("Entities", new ListTag());
		region.put("PendingBlockTicks", new ListTag());
		region.put("PendingFluidTicks", new ListTag());
		return region;
	}

	private static CompoundTag posTag(int x, int y, int z)
	{
		CompoundTag tag = new CompoundTag();
		tag.putInt("x", x);
		tag.putInt("y", y);
		tag.putInt("z", z);
		return tag;
	}
}

package com.wenzai.neosim.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.state.BlockState;

// 三维方块容器，使用{@link LightweightBitArray}紧凑存储和{@link BlockStatePalette}
public class LightweightBlockContainer
{
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final int totalVolume;

	private LightweightBitArray storage;
	private BlockStatePalette palette;

	private transient int cachedSolidBlockCount = -1;

	// 创建全空气的空容器
	public LightweightBlockContainer(int sizeX, int sizeY, int sizeZ)
	{
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.totalVolume = sizeX * sizeY * sizeZ;
		this.palette = new BlockStatePalette();
		this.storage = new LightweightBitArray(totalVolume, 1);
	}

	// 从已有palette和bit array创建
	public LightweightBlockContainer(int sizeX, int sizeY, int sizeZ,
									  BlockStatePalette palette,
									  LightweightBitArray storage)
	{
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.totalVolume = sizeX * sizeY * sizeZ;
		this.palette = palette;
		this.storage = storage;
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

	public int getTotalVolume()
	{
		return totalVolume;
	}

	public String getDimensionString()
	{
		return sizeX + " × " + sizeY + " × " + sizeZ + " (W×H×D)";
	}

	public BlockStatePalette getPalette()
	{
		return palette;
	}

	public LightweightBitArray getStorage()
	{
		return storage;
	}

	// X-major线性索引
	public int getIndex(int x, int y, int z)
	{
		return y * (sizeX * sizeZ) + z * sizeX + x;
	}

	// 读取坐标处的BlockState
	public BlockState get(int x, int y, int z)
	{
		int index = getIndex(x, y, z);
		long paletteId = storage.getAt(index);
		return palette.getBlockState((int) paletteId);
	}

	// 读取蓝图局部坐标处 BlockState
	public BlockState get(BlockPos pos)
	{
		return get(pos.getX(), pos.getY(), pos.getZ());
	}

	// 设置坐标处的 BlockState，palette扩容时自动重分配storage
	public void set(int x, int y, int z, BlockState state)
	{
		int index = getIndex(x, y, z);
		int id = palette.idFor(state);

		int requiredBits = palette.getBits();
		if (requiredBits != storage.bitsPerEntry())
		{
			resizeStorage(requiredBits);
		}

		storage.setAt(index, id);
		cachedSolidBlockCount = -1;
	}

	// 非空气方块计数，写入后缓存
	public int countSolidBlocks()
	{
		if (cachedSolidBlockCount >= 0)
		{
			return cachedSolidBlockCount;
		}
		int count = 0;
		for (int i = 0; i < totalVolume; i++)
		{
			if (storage.getAt(i) != 0)
			{
				count++;
			}
		}
		cachedSolidBlockCount = count;
		return count;
	}

	// 一次性统计每个 palette id 的出现次数（返回按 palette id 索引的计数数组，长度 = palette.size()）
	// 供材料统计等场景按 palette 项聚合，避免逐格重复分类
	public int[] countPaletteUsage()
	{
		int n = palette.size();
		int[] usage = new int[n];
		for (int i = 0; i < totalVolume; i++)
		{
			int id = (int) storage.getAt(i);
			if (id >= 0 && id < n)
			{
				usage[id]++;
			}
		}
		return usage;
	}

	public void invalidateCache()
	{
		cachedSolidBlockCount = -1;
	}

	// 从palette和raw long[]重建容器
	public static LightweightBlockContainer readFromNBT(BlockStatePalette palette,
														 long[] blockStates,
														 int sizeX, int sizeY, int sizeZ)
	{
		int bits = palette.getBits();
		int totalVolume = sizeX * sizeY * sizeZ;
		LightweightBitArray storage = new LightweightBitArray(blockStates, totalVolume, bits);
		return new LightweightBlockContainer(sizeX, sizeY, sizeZ, palette, storage);
	}

	public ListTag writePaletteToNBT()
	{
		return palette.writeToNBT();
	}

	public long[] writeBlockStatesToNBT()
	{
		return storage.backingArray().clone();
	}

	private void resizeStorage(int newBits)
	{
		LightweightBitArray newStorage = new LightweightBitArray(totalVolume, newBits);
		for (int i = 0; i < totalVolume; i++)
		{
			newStorage.setAt(i, storage.getAt(i));
		}
		this.storage = newStorage;
	}

	@Override
	public String toString()
	{
		return "LightweightBlockContainer{" + getDimensionString()
				+ ", volume=" + totalVolume
				+ ", palette=" + palette.size() + " entries"
				+ ", storage=" + storage + "}";
	}
}

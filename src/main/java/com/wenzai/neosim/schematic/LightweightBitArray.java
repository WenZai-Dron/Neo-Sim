package com.wenzai.neosim.schematic;

// 紧凑位打包数组，使用{@code long[]}作为底层存储
public class LightweightBitArray
{
	private final long[] data;
	private final int size;
	private final int bitsPerEntry;
	private final long maxEntryValue;

	// 零初始化位数组
	public LightweightBitArray(int size, int bitsPerEntry)
	{
		if (size < 0)
		{
			throw new IllegalArgumentException("size must be non-negative, got " + size);
		}
		if (bitsPerEntry < 1 || bitsPerEntry > 32)
		{
			throw new IllegalArgumentException("bitsPerEntry must be 1-32, got " + bitsPerEntry);
		}

		this.size = size;
		this.bitsPerEntry = bitsPerEntry;
		this.maxEntryValue = bitsPerEntry == 32
				? 0xFFFFFFFFL
				: (1L << bitsPerEntry) - 1;

		if (size == 0)
		{
			this.data = new long[0];
			return;
		}

		long totalBits = (long) size * bitsPerEntry;
		long arraySize = (totalBits + 63) / 64;
		if (arraySize > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException(
					"Array too large: " + size + " entries * " + bitsPerEntry
					+ " bits would need " + arraySize + " longs (max " + Integer.MAX_VALUE + ")");
		}
		this.data = new long[(int) arraySize];
	}

	// 从已有打包数据创建
	public LightweightBitArray(long[] data, int size, int bitsPerEntry)
	{
		if (data == null)
		{
			throw new IllegalArgumentException("data must not be null");
		}
		if (size < 0)
		{
			throw new IllegalArgumentException("size must be non-negative, got " + size);
		}
		if (bitsPerEntry < 1 || bitsPerEntry > 32)
		{
			throw new IllegalArgumentException("bitsPerEntry must be 1-32, got " + bitsPerEntry);
		}

		this.size = size;
		this.bitsPerEntry = bitsPerEntry;
		this.maxEntryValue = bitsPerEntry == 32
				? 0xFFFFFFFFL
				: (1L << bitsPerEntry) - 1;
		this.data = data;
	}

	public int size()
	{
		return size;
	}

	public int bitsPerEntry()
	{
		return bitsPerEntry;
	}

	public long maxEntryValue()
	{
		return maxEntryValue;
	}

	// 返回底层{@code long[]}直接引用
	public long[] backingArray()
	{
		return data;
	}

	// 读取指定索引的无符号值
	public long getAt(int index)
	{
		checkIndex(index);

		int startOffset = index * bitsPerEntry;
		int startArrIdx = startOffset >>> 6;
		int startBitOff = startOffset & 63;

		if (startBitOff + bitsPerEntry <= 64)
		{
			return (data[startArrIdx] >>> startBitOff) & maxEntryValue;
		}
		else
		{
			int bitsInFirstLong = 64 - startBitOff;
			long high = data[startArrIdx] >>> startBitOff;
			long low = data[startArrIdx + 1] << bitsInFirstLong;
			return (high | low) & maxEntryValue;
		}
	}

	// 写入无符号值
	public void setAt(int index, long value)
	{
		checkIndex(index);

		if (value > maxEntryValue)
		{
			throw new IllegalArgumentException(
					"Value " + value + " exceeds maxEntryValue " + maxEntryValue
					+ " for bitsPerEntry=" + bitsPerEntry);
		}

		int startOffset = index * bitsPerEntry;
		int startArrIdx = startOffset >>> 6;
		int startBitOff = startOffset & 63;

		if (startBitOff + bitsPerEntry <= 64)
		{
			long mask = maxEntryValue << startBitOff;
			data[startArrIdx] = (data[startArrIdx] & ~mask) | (value << startBitOff);
		}
		else
		{
			int bitsInFirstLong = 64 - startBitOff;
			int bitsInSecondLong = bitsPerEntry - bitsInFirstLong;

			long firstKeepMask = (1L << startBitOff) - 1;
			data[startArrIdx] = (data[startArrIdx] & firstKeepMask) | (value << startBitOff);

			long secondClearMask = ~((1L << bitsInSecondLong) - 1);
			data[startArrIdx + 1] = (data[startArrIdx + 1] & secondClearMask)
								  | (value >>> bitsInFirstLong);
		}
	}

	private void checkIndex(int index)
	{
		if (index < 0 || index >= size)
		{
			throw new IndexOutOfBoundsException(
					"index " + index + " out of bounds [0, " + size + ")");
		}
	}

	@Override
	public String toString()
	{
		return "LightweightBitArray{size=" + size + ", bits=" + bitsPerEntry
				+ ", maxValue=" + maxEntryValue + ", longs=" + data.length + "}";
	}
}

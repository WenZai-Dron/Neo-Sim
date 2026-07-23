# Litematica 文件格式详解

> 基于 litematica-LTS-1.21.11 源码分析

---

## 1. 文件概述

`.litematic` 文件本质上是一个 **GZIP 压缩的 NBT（Named Binary Tag）CompoundTag**，使用 Minecraft 的 NBT 数据结构来存储建筑蓝图。

核心类：`fi.dy.masa.litematica.schematic.LitematicaSchematic`

### 关键常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `FILE_EXTENSION` | `.litematic` | 文件扩展名 |
| `SCHEMATIC_VERSION` | 7 | 当前格式版本 |
| `SCHEMATIC_VERSION_SUB` | 1 | 子版本（数据修复用） |
| `MINECRAFT_DATA_VERSION` | 当前 MC 版本 | 如 1.20.4 = 3700 |

### 支持的文件格式

| 扩展名 | 类型 |
|--------|------|
| `.litematic` | Litematica 原生格式 |
| `.schematic` | 旧版 Schematica 格式 |
| `.schem` | Sponge Schematic 格式 |
| `.nbt` | 原版结构方块格式 |

---

## 2. 顶层 NBT 结构

```
.litematic 文件（GZIP 压缩的 CompoundTag）
├── Version: int                          // 格式版本号，当前为 7
├── SubVersion: int                       // 子版本号，当前为 1
├── MinecraftDataVersion: int             // Minecraft 数据版本（如 3700 = 1.20.4）
├── Metadata: CompoundTag                 // 原理图元数据
└── Regions: CompoundTag                  // 所有子区域（每个子区域为一个键值对）
    ├── <regionName1>: CompoundTag
    ├── <regionName2>: CompoundTag
    └── ...
```

源码 (`LitematicaSchematic.java:1227-1238`)：

```java
public CompoundTag writeToNBT()
{
    CompoundTag nbt = new CompoundTag();
    nbt.putInt("MinecraftDataVersion", MINECRAFT_DATA_VERSION);
    nbt.putInt("Version", SCHEMATIC_VERSION);
    nbt.putInt("SubVersion", SCHEMATIC_VERSION_SUB);
    nbt.put("Metadata", this.metadata.writeToNBT());
    nbt.put("Regions", this.writeSubRegionsToNBT());
    return nbt;
}
```

### 版本号历史

| Version | 说明 |
|---------|------|
| 1 | 早期版本 |
| 2 | 添加 Entities + TileEntities |
| 3 | 添加 PendingBlockTicks |
| 5 | MC 1.13.2 扁平化（`SCHEMATIC_VERSION_1_13_2 = 5`） |
| 6 | MC 1.20.4 |
| 7 | 当前版本（MC 1.21+） |

---

## 3. Metadata（元数据）

```
Metadata: CompoundTag
├── Name: string                         // 原理图名称
├── Author: string                       // 作者名
├── Description: string                  // 描述文本
├── RegionCount: int                     // 子区域数量
├── TotalVolume: int                     // 总体积（包含空气）
├── TotalBlocks: int                     // 非空气方块数量
├── TimeCreated: long                    // 创建时间戳（毫秒）
├── TimeModified: long                   // 最后修改时间戳（毫秒）
├── EnclosingSize: CompoundTag           // 包围盒尺寸
│   ├── X: int
│   ├── Y: int
│   └── Z: int
└── PreviewImageData: int[] (可选)       // 缩略图 ARGB 像素数据
```

Java 字段（`SchematicMetadata.java`）：

```java
private String name;          // 默认 "?"
private String author;        // 默认 "?"
private String description;   // 默认 ""
private Vec3i enclosingSize;  // 默认 Vec3i.ZERO
private long timeCreated;
private long timeModified;
private int minecraftDataVersion;
private int schematicVersion;
private int regionCount;
private int totalVolume;      // 默认 -1
private int totalBlocks;      // 默认 -1
@Nullable private IntStream thumbnailPixelData;
```

---

## 4. Region（子区域）

每个子区域代表蓝图中的一个独立部分。一个 `.litematic` 文件可以包含**多个命名子区域**。

### 4.1 Region NBT 结构

```
<regionName>: CompoundTag
├── Position: CompoundTag                // 子区域在总包围盒中的相对位置（偏移量）
│   ├── X: int
│   ├── Y: int
│   └── Z: int
├── Size: CompoundTag                    // 子区域的尺寸
│   ├── X: int
│   ├── Y: int
│   └── Z: int
├── BlockStatePalette: ListTag<CompoundTag>  // 方块状态调色板
├── BlockStates: LongArrayTag           // 位打包的方块数据
├── TileEntities: ListTag<CompoundTag>  // 方块实体（如箱子、熔炉等）
├── Entities: ListTag<CompoundTag>      // 实体数据
├── PendingBlockTicks: ListTag<CompoundTag>  // 计划方块刻（v3+）
└── PendingFluidTicks: ListTag<CompoundTag>  // 计划流体刻（v5+）
```

源码出处在 `LitematicaSchematic.java:1253-1300` 的 `writeSubRegionsToNBT()` 方法。

---

## 5. 方块存储系统（核心）

Litematica 采用与 Minecraft 区块完全相同的 **调色板（Palette）+ 位打包数组（Packed Bit Array）** 方案。

### 5.1 数据结构全景

```
LitematicaSchematic
├── metadata: SchematicMetadata
│   └── 见第3节
├── blockContainers: Map<String, LitematicaBlockStateContainer>
│   └── LitematicaBlockStateContainer       // 每个子区域一个
│       ├── palette: ILitematicaBlockStatePalette
│       │   ├── LitematicaBlockStatePaletteLinear   (bits ≤ 4, 最多16种方块)
│       │   └── LitematicaBlockStatePaletteHashMap  (bits > 4, 更多方块类型)
│       ├── storage: LitematicaBitArray
│       │   ├── bitsPerEntry: int             // 每个方块占用的比特数
│       │   ├── arraySize: long               // 方块总数量（体积）
│       │   ├── maxEntryValue: long           // 掩码 (1 << bits) - 1
│       │   └── longArray: long[]             // 打包的位数据
│       ├── size: Vec3i (X, Y, Z)              // 三维尺寸
│       ├── sizeLayer: int (= X * Z)           // 一层有多少方块
│       └── totalVolume: long                  // 总体积
├── tileEntities: Map<String, Map<BlockPos, CompoundTag>>
├── entities: Map<String, List<EntityInfo>>
└── ... (其余映射表)
```

### 5.2 调色板（Palette）

调色板是一个 **BlockState → 整数 ID** 的映射表，**索引 0 始终映射到空气（AIR）**。

存储为 `ListTag<CompoundTag>`，每个元素是一个 BlockState，例如：

```json
[
  {"Name": "minecraft:air"},                              // ID 0
  {"Name": "minecraft:stone"},                            // ID 1
  {"Name": "minecraft:oak_stairs", "Properties": {        // ID 2
    "facing": "north",
    "half": "bottom",
    "shape": "straight",
    "waterlogged": "false"
  }},
  ...
]
```

#### 两种调色板实现

| 类型 | 条件 | 数据结构 | 容量 |
|------|------|---------|------|
| `LitematicaBlockStatePaletteLinear` | `bits ≤ 4` | `BlockState[]` 数组 + 线性搜索 | 最多 16 种 |
| `LitematicaBlockStatePaletteHashMap` | `bits > 4` | `CrudeIncrementalIntIdentityHashBiMap` 双向哈希表 | 理论上 $2^{bits}$ 种 |

#### bits 的计算公式

```java
int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
```

等价于：`bits = max(2, ceil(log2(paletteSize)))`

| 方块种类数 | bits |
|-----------|------|
| 1-2 | 2（最小值） |
| 3-4 | 2 |
| 5-8 | 3 |
| 9-16 | 4（使用 Linear 调色板） |
| 17-32 | 5（切换为 HashMap 调色板） |
| ... | ... |

### 5.3 LitematicaBitArray（位打包数组）

这是最重要的数据结构。将每个方块的调色板 ID 用 `bits` 个比特位表示，紧密打包到 `long[]` 数组中。

#### 概念图示

```
方块索引:    [0]     [1]     [2]     [3]     [4]     [5]     [6]     [7]     [8] ...
调色板 ID:    0       1       0       2       0       0       1       3       0  ...

long[] 数组（假设 bits=2，每个 long 占 64 位，能存储 32 个方块的值）:
long[0] = |00|11|00|10|00|00|01|00|... (32 个 2-bit 值打包到 1 个 long 中)
           ───────────────────────────── 共 64 位
long[1] = |...| (接下来 32 个方块)
```

#### 关键参数

- **`bitsPerEntry`**: 每个方块的比特宽度（2 ~ 32）
- **`arraySize`**: 方块总数 = `sizeX * sizeY * sizeZ`
- **`maxEntryValue`**: 掩码 = `(1 << bitsPerEntry) - 1`
- **`longArray` 长度**: `ceil(arraySize * bitsPerEntry / 64)`

#### 索引公式

三维坐标 `(x, y, z)` 映射到线性索引（`LitematicaBlockStateContainer.java:187-189`）：

```java
protected int getIndex(int x, int y, int z)
{
    return (y * this.sizeLayer) + z * this.sizeX + x;
    // sizeLayer = sizeX * sizeZ（一层有多少个方块）
}
```

内存布局：**X 优先（连续），然后 Z，Y 最后**。

```
+----> X
|
| y=0 层: (0,0,0) (1,0,0) (2,0,0) ... (sx-1,0,0) (0,0,1) ... (sx-1,0,sz-1)
| y=1 层: (0,1,0) (1,1,0) (2,1,0) ...
v Z

等价于：先遍历所有 X，然后步进 Z，最后步进 Y
```

#### getAt 算法（核心）

从打包的 `long[]` 中读取第 `index` 个值（`LitematicaBitArray.java:103-119`）：

```java
public int getAt(long index)
{
    // 起始位偏移量
    long startOffset = index * (long) this.bitsPerEntry;

    // 位于第几个 long（除以 64）
    int startArrIndex = (int) (startOffset >> 6);

    // 结束位所在 long 的索引
    int endArrIndex = (int) (((index + 1L) * (long) this.bitsPerEntry - 1L) >> 6);

    // 在 long 内部的位偏移（模 64）
    int startBitOffset = (int) (startOffset & 0x3F);

    if (startArrIndex == endArrIndex)
    {
        // 情况 1：值完全在一个 long 内
        return (int) (this.longArray[startArrIndex] >>> startBitOffset & this.maxEntryValue);
    }
    else
    {
        // 情况 2：值跨越两个 long 的边界
        int endOffset = 64 - startBitOffset;
        return (int) ((this.longArray[startArrIndex] >>> startBitOffset
                    | this.longArray[endArrIndex] << endOffset) & this.maxEntryValue);
    }
}
```

**图示说明：**

```
情况 1（不跨边界，如 bits=4, index=3，startOffset=12）：
  long[0]: |63  ...  16|15 14 13 12|11 ...   0|
                          └─值─┘
  直接右移 12 位后 & mask 即可

情况 2（跨边界，如 bits=20, startOffset=50）：
  long[0]: |  63 ... 50 | 49 ... 0 |
             └──高位──┘
  long[1]: | 63 ... 0 |
             └─低位──┘
  高位部分：long[0] >>> 50
  低位部分：long[1] << (64-50) = long[1] << 14
  结果：((高位) | (低位)) & mask
```

#### setAt 算法

```java
public void setAt(long index, int value)
{
    long startOffset = index * (long) this.bitsPerEntry;
    int startArrIndex = (int) (startOffset >> 6);
    int endArrIndex = (int) (((index + 1L) * (long) this.bitsPerEntry - 1L) >> 6);
    int startBitOffset = (int) (startOffset & 0x3F);

    // 先清除旧值、再写入新值
    this.longArray[startArrIndex] =
        this.longArray[startArrIndex] & ~(this.maxEntryValue << startBitOffset)   // 清除
        | ((long) value & this.maxEntryValue) << startBitOffset;                  // 写入

    if (startArrIndex != endArrIndex)
    {
        // 跨边界：写入低位部分到下一个 long
        int endOffset = 64 - startBitOffset;
        int j1 = this.bitsPerEntry - endOffset;
        this.longArray[endArrIndex] =
            this.longArray[endArrIndex] >>> j1 << j1                               // 清除
            | ((long) value & this.maxEntryValue) >> endOffset;                    // 写入
    }
}
```

---

## 6. TileEntities（方块实体）

存储需要额外数据的方块，如箱子、熔炉、告示牌等。

```
TileEntities: ListTag<CompoundTag>
  └── 每个元素是一个方块实体的完整 NBT 数据：
      ├── x: int (相对坐标)
      ├── y: int
      ├── z: int
      ├── id: string (如 "minecraft:chest")
      ├── Items: ListTag<CompoundTag> (容器内物品)
      ├── ... (其余方块实体特定数据)
```

---

## 7. Entities（实体）

```
Entities: ListTag<CompoundTag>
  └── 每个元素是一个实体的完整 NBT 数据：
      ├── id: string (如 "minecraft:item_frame")
      ├── Pos: CompoundTag 或 ListTag (相对区域的坐标)
      ├── ...
      └── 特殊处理：
          ├── HangingEntity (悬挂实体)：TileX, TileY, TileZ
          └── BlockAttachedEntity (附着实体)：block_pos
```

`EntityInfo` 结构：

```java
// LitematicaSchematic.java 内部类
class EntityInfo {
    Vec3 posVec;          // 相对于子区域原点的偏移
    CompoundTag nbt;      // 实体的完整 NBT 数据
}
```

---

## 8. PendingTicks（计划刻）

记录方块和流体的计划刻更新信息。

```
PendingBlockTicks / PendingFluidTicks: ListTag<CompoundTag>
  └── 每个元素：
      ├── Block / Fluid: string (方块或流体的 ID)
      ├── Priority: int (优先级，0-3 对应 TickPriority)
      ├── SubTick: long (子刻序号)
      ├── Time: int (多少刻后触发，相对时间)
      ├── x: int (相对坐标)
      ├── y: int
      └── z: int
```

---

## 9. 读取流程

### 9.1 整体流程（来自 `readFromNBT()`）

```
1. 打开文件 → GZIP 解压 → 解析 CompoundTag
       ↓
2. 读取 Version 字段 → 验证版本兼容性 (1 ≤ version ≤ 7)
       ↓
3. 读取 MinecraftDataVersion 字段 → 检查新旧兼容性
       ↓
4. 读取 Metadata CompoundTag → 解析元数据
       ↓
5. 读取 Regions CompoundTag → 遍历每个子区域
       ↓
6. 对每个子区域：
   ├── 读取 Position / Size
   ├── 读取 BlockStatePalette → 构建 LitematicaBlockStatePalette
   ├── 读取 BlockStates → 构建 LitematicaBitArray
   ├── 读取 TileEntities → Map<BlockPos, CompoundTag>
   ├── 读取 Entities → List<EntityInfo>
   ├── 读取 PendingBlockTicks → Map<BlockPos, ScheduledTick<Block>>
   ├── 读取 PendingFluidTicks → Map<BlockPos, ScheduledTick<Fluid>>
   └── 组装为 LitematicaBlockStateContainer
       ↓
7. 如果 MC 数据版本不一致 → 执行 DataFixer 转换
```

### 9.2 调色板版本转换

```java
// LitematicaSchematic.java:1650-1651
palette = this.convertBlockStatePalette_1_12_to_1_13_2(palette, version, minecraftDataVersion);
palette = this.convertBlockStatePalette_to_1_20_5(palette, minecraftDataVersion);
```

- **1.12 → 1.13.2**: 数字 ID + metadata → 字符串 ID + Properties
- **旧版 → 1.20.5+**: 方块名称更新、属性名变更
- TileEntities 和 Entities 同理有对应的 `convert*to_1_20_5()` 方法

---

## 10. 读取器伪代码实现

```python
import gzip
import math

def read_litematic(filepath):
    """
    独立读取 .litematic 文件（需 NBT 解析库如 nbtlib）。
    """
    # 1. 读取并 GZIP 解压
    with gzip.open(filepath, 'rb') as f:
        root = nbtlib.load(f)  # 返回 CompoundTag

    # 2. 版本信息
    version = root['Version']
    mc_data_version = root['MinecraftDataVersion']
    sub_version = root.get('SubVersion', 0)

    # 3. 元数据
    meta = root['Metadata']
    name = meta['Name']
    author = meta['Author']
    description = meta.get('Description', '')
    total_blocks = meta.get('TotalBlocks', 0)
    enclosing_size = (
        meta['EnclosingSize']['X'],
        meta['EnclosingSize']['Y'],
        meta['EnclosingSize']['Z']
    )

    # 4. 子区域
    regions = root['Regions']
    result = {}

    for region_name, region in regions.items():
        # 4a. 位置和尺寸
        pos = (region['Position']['X'], region['Position']['Y'], region['Position']['Z'])
        size_x = region['Size']['X']
        size_y = region['Size']['Y']
        size_z = region['Size']['Z']

        # 4b. 调色板
        palette = []
        for block_tag in region['BlockStatePalette']:
            block_name = block_tag['Name']  # 如 "minecraft:stone"
            properties = {}
            if 'Properties' in block_tag:
                properties = {k: v for k, v in block_tag['Properties'].items()}
            palette.append((block_name, properties))

        # 4c. 计算比特宽度
        bits = max(2, math.ceil(math.log2(len(palette)))) if len(palette) > 1 else 2

        # 4d. 读取打包数据
        long_array = region['BlockStates']  # LongArrayTag → int64 列表
        total_entries = size_x * size_y * size_z

        # 4e. 解包并重建 3D 数组
        size_layer = size_x * size_z
        blocks = {}  # {(x, y, z): palette_id}

        for y in range(size_y):
            for z in range(size_z):
                for x in range(size_x):
                    idx = y * size_layer + z * size_x + x
                    palette_id = _get_at(long_array, idx, bits)
                    blocks[(x, y, z)] = palette[palette_id]

        # 4f. Tile Entities
        tile_entities = {}
        for te_tag in region.get('TileEntities', []):
            te_x = te_tag['x']
            te_y = te_tag['y']
            te_z = te_tag['z']
            tile_entities[(te_x, te_y, te_z)] = te_tag

        result[region_name] = {
            'position': pos,
            'size': (size_x, size_y, size_z),
            'palette': palette,
            'blocks': blocks,
            'tile_entities': tile_entities,
        }

    return {
        'name': name,
        'author': author,
        'description': description,
        'total_blocks': total_blocks,
        'enclosing_size': enclosing_size,
        'regions': result,
    }


def _get_at(long_array, index, bits_per_entry):
    """
    从打包的 long[] 中读取第 index 个调色板 ID。

    long_array: int64 列表（Java long 适配 Python int）
    index: 方块线性索引
    bits_per_entry: 每个方块占用的比特数
    """
    start_offset = index * bits_per_entry
    start_arr_idx = start_offset >> 6       # // 64
    end_arr_idx = ((index + 1) * bits_per_entry - 1) >> 6
    start_bit_offset = start_offset & 0x3F  # % 64
    max_value = (1 << bits_per_entry) - 1

    if start_arr_idx == end_arr_idx:
        # 值完全在一个 long 内
        return (long_array[start_arr_idx] >> start_bit_offset) & max_value
    else:
        # 值跨越两个 long 的边界
        end_offset = 64 - start_bit_offset
        return ((long_array[start_arr_idx] >> start_bit_offset)
              | (long_array[end_arr_idx] << end_offset)) & max_value
```

---

## 11. BlockState NBT 格式

每个 BlockState 以 CompoundTag 形式存储：

```json
{
  "Name": "minecraft:stone_brick_stairs",
  "Properties": {
    "facing": "east",
    "half": "bottom",
    "shape": "straight",
    "waterlogged": "false"
  }
}
```

- `Name`: 方块的注册名（命名空间ID）
- `Properties`: 可选，方块状态属性键值对（所有值均为字符串）

空方块**不**写入调色板。如果调色板中不存在某 ID 对应的 BlockState，返回 `Blocks.AIR.defaultBlockState()`。

---

## 12. 兼容性矩阵

| 情景 | 处理方式 |
|------|---------|
| 加载较新 MC 版本的文件 | 显示警告（dataVersion 差距 > 100） |
| 加载 1.12 之前的调色板 | `convertBlockStatePalette_1_12_to_1_13_2()` 数字ID→字符串 |
| 加载旧版调色板 | `convertBlockStatePalette_to_1_20_5()` 更新名称 |
| 加载旧版 TileEntity | `convertTileEntities_to_1_20_5()` |
| 加载旧版 Entity | `convertEntities_to_1_20_5()` |
| 降级 v7 → v6 | `downgradeV7toV6Schematic()` 完整复制所有区域 |
| 从 Schematica 格式导入 | `SchematicaSchematic` 类处理 `.schematic` 文件 |
| 从 Sponge 格式导入 | `readFromSpongeSchematic()` 处理 `.schem` 文件 |
| 从原版结构导入 | `readFromVanillaStructure()` 处理 `.nbt` 文件 |

---

## 13. 文件保存

保存（`writeToNBT()`→ 序列化 CompoundTag → GZIP 压缩 → 写入 `.litematic` 文件）的同时还会设置元数据：

```java
schematic.metadata.setSchematicVersion(SCHEMATIC_VERSION);     // 7
schematic.metadata.setMinecraftDataVersion(MINECRAFT_DATA_VERSION);
schematic.metadata.setFileType(FileType.LITEMATICA_SCHEMATIC);
schematic.metadata.setModifiedSinceSaved();   // 标记已保存
```

文件写入完成后，可通过 `schematic.getFile()` 获取 `Path` 对象。

---

## 14. 总结

| 特性 | 描述 |
|------|------|
| **容器格式** | GZIP 压缩的 NBT CompoundTag |
| **格式版本** | Schema Version 7, SubVersion 1 |
| **多区域** | 支持多个命名子区域，各有独立位置和尺寸 |
| **方块编码** | 调色板 + 位打包 LongArray（与 MC 区块格式一致） |
| **调色板类型** | Linear（≤16 种方块，`bits ≤ 4`）和 HashMap（>16 种方块，`bits > 4`） |
| **最小比特数** | 2 bits（强制最小值） |
| **索引公式** | `index = y * (sx * sz) + z * sx + x` |
| **版本兼容** | 自动方块名转换（1.12→1.13.2→1.20.5），向前兼容 v1-v7 |
| **数据完整性** | 包含方块实体、实体、计划刻数据 |
| **元数据** | 作者、时间戳、缩略图、包围盒尺寸等 |

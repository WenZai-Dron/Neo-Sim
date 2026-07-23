# Sim-U-Kraft 自动化建筑系统 —— 基本流程与架构分析

> Sim-U-Kraft 是由 Scott Hather (Satscape) 开发的 Minecraft 模拟经营 Mod（v0.12.1 Beta），本文档聚焦其**自动化建筑系统**的实现原理。

---

## 一、整体架构概览

自动化建筑系统由 **5 个核心组件** 协同工作：

| 组件 | 文件 | 职责 |
|------|------|------|
| 建筑蓝图 | `Simukraft/buildings/**/*.txt` | 以自定义文本格式存储 3D 建筑结构 |
| 建筑数据模型 | `Building.java` | 解析蓝图、管理建筑元数据、计算材料需求 |
| 构造函数块 | `BlockConstructorBox.java` | 世界中放置的方块，作为建筑工地的起点 |
| 建筑工 AI | `JobBuilder.java` | 状态机驱动的 NPC 行为，逐块执行建造 |
| 构造函数实体 | `EntityConBox.java` | 悬浮在工地上方的 3D 视觉指示器 |

---

## 二、建筑蓝图格式

蓝图文件存储在 `Simukraft/buildings/{residential,commercial,industrial,other}/` 四个分类目录下，采用 **自定义三层文本格式**：

```
第1行: 尺寸声明     →  6x14x4            (宽 x 深 x 高)
第2行: 字符映射表   →  A=0:0;C=126:0;D=53:3;E=85:0;...
第3-N行: 方块数据   →  CCAAAD$EFFFFF...  (每层一行，每个字符代表一个方块)
```

### 字符映射规则

- 大写字母 `A` → 空气（不计入 blocksInBuilding）
- 普通字符 → 通过 key-value 表映射到 `blockID:meta`
- **特殊字符**：
  - `!` → `999:999`，即 **living block**——NPC 在此建筑中生活/工作的坐标
  - `$` → **Control Box**（控制方块），建筑完成后替换构造函数块
  - `*` → **Sim-U-LightBox**（模组光照方块）
  - 数字 `0-9` → `999:0` 至 `999:9`，表示**特殊用途空气块**（如农田、标记点），实际放置空气但记录在 `blockSpecial` 列表中
- 特殊标记 `AU=` → 作者信息

### 解析流程（`Building.loadStructure()`）

1. 读取第 1 行，拆分 `x` 符号获得 `ltrCount`（宽）、`ftbCount`（深）、`layerCount`（高）
2. 读取第 2 行，按 `;` 分割后按 `=` 拆分为 HashMap（字符 → `blockID:meta`）
3. 分配 `structure[]` 字符串数组，大小为 宽×深×高
4. 三重循环：逐层（高）→ 逐行（深）→ 逐列（宽），逐字符解析并填充 structure 数组
5. 同时累计 `requirements` HashMap（材料需求清单）、`blocksInBuilding`（总方块数）、`rent`（租金 = 方块数 × 0.01）

---

## 三、三种游戏模式详解

Sim-U-Kraft 提供 4 种运行模式（`GameMode` 枚举），玩家在首次运行时通过 `GuiRunMod` 选择：

```
Do NOT run Sim-U-Kraft   (-1)  → 停用整个 Mod
Normal Mode              (0)   → 标准玩法
Creative Mode            (1)   → 创造玩法
Hardcore Mode            (2)   → 硬核玩法
```

三种活跃模式在**两个关键位置**有截然不同的行为——材料需求计算和实际建造消耗。以下逐一分析。

---

### 3.1 NORMAL 模式（模式编号 0）

> GUI 描述：*"Ideal for beginners and experts. Not too challenging."*

**设计理念**：仅**基础通用建材**需要玩家提供，装饰性/功能性方块自动"免费"——减少玩家的微管理负担，同时保持资源收集的基本挑战。

#### A. 材料需求计算 — `Building.addToRequirements()`

只将满足以下条件的方块纳入 `requirements` 清单（即仅这些方块会显示在 GUI 的需求页和箱子检查中）：

```java
// Building.java:310-318
name.contains("planks")        // 木板
name.contentEquals("cobblestone")  // 圆石
name.contentEquals("glass")        // 玻璃
name.contains("wool")              // 羊毛
name.contentEquals("bricks")       // 砖块
name.contentEquals("dirt")         // 泥土
name.contentEquals("stone bricks") // 石砖
name.contentEquals("fence")        // 栅栏
name.contentEquals("stone")        // 石头
name.contains("wood")              // 原木
    && !name.contains("slab")      // （排除木半砖）
    && !name.contains("door")      // （排除木门）
    && !name.contains("stairs")    // （排除木楼梯）
    && !name.contains("grass")     // （排除草方块和砂土）
```

**不纳入的白名单以外的方块**（实际建造时免费放置，不从箱子扣除）：
所有不在上述列表中的方块——如玻璃板、火把、书架、床、工作台、熔炉、楼梯、半砖、门、栅栏门、蛋糕、红石、画框、花盆……

#### B. 实际建造消耗 — `JobBuilder.stageInProgress()`

```java
// JobBuilder.java:538-547
boolean requiredBlocks = blockId == Block.planks.blockID
    || blockId == Block.cobblestone.blockID
    || blockId == Block.glass.blockID
    || blockId == Block.cloth.blockID      // 羊毛
    || blockId == Block.brick.blockID
    || blockId == Block.dirt.blockID
    || blockId == Block.stoneBrick.blockID
    || blockId == Block.fence.blockID
    || blockId == Block.stone.blockID
    || blockId == Block.wood.blockID;
```

- 方块在 `requiredBlocks` 列表中 → **必须从箱子取出物品**，取不出则退回 WAITINGFORRESOURCES
- 方块不在 `requiredBlocks` 列表中 → `gotBlock = true`（直接放置，不消耗任何材料）

#### C. 信用点消耗

每放置一个实心方块：`credits -= 0.02`（`JobBuilder.java:710`）

#### D. 租金收集

每日结算时收取所有有人居住的住宅建筑的租金（`ModSimukraft.java:1041-1058`）。租金 = `blocksInBuilding × 0.01`。

---

### 3.2 CREATIVE 模式（模式编号 1）

> GUI 描述：*"No money needed, everything free, no blocks required, be creative!"*

**设计理念**：完全免资源免费用，让玩家专注于布局和设计，不受任何经济或材料限制。

#### A. 材料需求计算 — `Building.addToRequirements()`

```java
// Building.java:344-347
else if (ModSimukraft.gameMode == GameMode.CREATIVE)
{
    return;  // 直接返回，不向 requirements 添加任何物品
}
```

**结果**：所有建筑的 `requirements` 始终为空 HashMap。GUI 需求页不会显示任何材料条目。

#### B. 实际建造消耗 — `JobBuilder.stageInProgress()`

```java
// JobBuilder.java:570-573
else if (ModSimukraft.gameMode == GameMode.CREATIVE)
{
    gotBlock = true;  // 无条件通过，不碰任何箱子
}
```

- 每块都 `gotBlock = true`，不搜索箱子，不取出任何物品
- 建造延迟 `runDelay = 0`（瞬间放置，`JobBuilder.java:788-790`）

#### C. 信用点

- 建造不扣除信用点
- 不收取租金（`ModSimukraft.java:1041` 跳过）
- NPC 不发工资（`ModSimukraft.java:1123` 跳过）

#### D. NPC 管理

不支付工资，NPC 直接工作，不触发欠薪解雇逻辑。

---

### 3.3 HARDCORE 模式（模式编号 2）

> GUI 描述：*"Builders require ALL blocks, harder gameplay"*

**设计理念**：几乎每一个方块都需要玩家提供对应物品，建造门槛最高，适合追求真实生存体验的玩家。

#### A. 材料需求计算 — `Building.addToRequirements()`

```java
// Building.java:348-384
else if (ModSimukraft.gameMode == GameMode.HARDCORE)
{
    String name = theBlock.getDisplayName().toLowerCase();
    if (!name.contains("grass") && !name.contains("bed")) {
        // 将方块加入 requirements（与 NORMAL 相同的累加逻辑）
    }
}
```

**唯一排除的方块**：
- 名称含 `"grass"` → 草方块、砂土等
- 名称含 `"bed"` → 床

除此之外**所有方块**都纳入需求清单（包括 NORMAL 模式下免费的楼梯、半砖、门等）。

#### B. 实际建造消耗 — `JobBuilder.stageInProgress()`

```java
// JobBuilder.java:574-615
if (blockId > 0) {
    // 免费提供的方块（不消耗）：
    if (blockId == Block.grass.blockID           // 草方块
        || blockId == Block.waterMoving.blockID   // 流动水
        || blockId == Block.waterStill.blockID    // 静态水
        || blockId == Block.lavaMoving.blockID    // 流动岩浆
        || blockId == Block.lavaStill.blockID     // 静态岩浆
        || blockId == 68                          // 告示牌
        || blockId == Block.cake.blockID          // 蛋糕
        || blockId == Block.stoneSingleSlab.blockID   // 石半砖
        || blockId == Block.woodSingleSlab.blockID    // 木半砖
        || blockId == Block.woodDoubleSlab.blockID    // 木双半砖
        || blockId == Block.stoneDoubleSlab.blockID   // 石双半砖
        || blockId == Block.tilledField.blockID       // 耕地
        || blockId == Block.doorWood.blockID          // 木门
        || blockId == Block.doorIron.blockID          // 铁门
        || blockId == Block.bed.blockID)              // 床
    {
        gotBlock = true;  // 免费放置
    }
    else
    {
        // 其他所有方块 → 必须从箱子取材料
        gotBlock = inventoriesGet(...) != null;
        // 控制方块例外，始终免费
        if (blockId == ModSimukraft.controlBlockId) gotBlock = true;
    }
}
```

**免费方块清单**（17 种）：水、岩浆、草方块、告示牌、蛋糕、石/木半砖、石/木双半砖、耕地、木门、铁门、床、控制方块。

**代码注释透露的已知问题**：
```java
//// TODO: WHEN I RE-WRITE - problem here is it needs to translate blocks to items
```
作者指出半砖方块（如 `stoneSingleSlab`）的 block ID 对应的 item ID 在实际物品系统中不同，免费列表是一个临时解决方案。（他说这是一个bug）

#### C. 信用点消耗

与 NORMAL 完全相同：每放置一个实心方块扣除 `0.02` 信用点。

#### D. 租金收集

与 NORMAL 完全相同：每日结算有人居住住宅的租金。

---

### 3.4 三种模式对比总表

| 维度 | NORMAL | CREATIVE | HARDCORE |
|------|--------|----------|----------|
| **材料需求清单** | 仅基础建材（10 种） | 空（无需求） | 除草和床外的全部方块 |
| **建造材料消耗** | 仅基础建材消耗物品 | 不消耗任何物品 | 除 17 种免费方块外全部消耗 |
| **建造速度延迟** | `2000 / levelBuilder` | `0`（瞬间） | `2000 / levelBuilder` |
| **信用点扣除/块** | 0.02 | 不扣除 | 0.02 |
| **每日租金** | 收取 | 不收取 | 收取 |
| **NPC 工资** | 支付 | 不支付 | 支付 |
| **免费方块范围** | 楼梯、半砖、门、玻璃板、火把等装饰性方块 | 全部方块 | 仅水、岩浆、草、告示牌、蛋糕、半砖、耕地、门、床 |
| **设计定位** | 标准模拟经营 | 自由建造沙盒 | 真实生存挑战 |

---

## 四、Building 类 —— 建筑数据模型

### 核心字段

```java
public String displayName;           // 建筑名称（含可能的 PKID 前缀）
public String type;                  // 分类: residential / commercial / industrial / other
public String[] structure;           // 3D 结构数组 [ltr × ftb × layer]，每个元素为 "blockID:meta"
public int layerCount, ftbCount, ltrCount;  // 三维尺寸
public V3 primaryXYZ;               // 控制方块的世界坐标
public V3 livingXYZ;                // NPC 生活/工作位置
public boolean buildingComplete;     // 是否建造完成
public String buildDirection;       // 建筑朝向: +x / -x / +z / -z
public ArrayList<V3> blockLocations; // 已放置的方块坐标列表
public ArrayList<V3> blockSpecial;   // 特殊用途方块坐标（含 meta 标记）
public HashMap<ItemStack, Integer> requirements; // 所需材料清单
public V3 conBoxLocation;           // 构造函数块的位置
```

### 关键方法

| 方法 | 功能 |
|------|------|
| `loadStructure()` | 从 .txt 蓝图文件解析结构 |
| `clone()` | 深拷贝建筑对象（含重新加载结构） |
| `getBuildingBlueprints(type, search)` | 按分类和搜索条件获取可用蓝图列表（支持 w:/d:/h: 尺寸过滤） |
| `getBuilding(primaryXYZ)` | 按世界坐标查找建筑 |
| `getBuildingByConBox(conBoxLoc)` | 按构造函数块位置查找建筑 |
| `getFromAllBuildings(name, type)` | 按名称从缓存池中查找并克隆蓝图 |
| `saveThisBuilding()` / `saveAllBuildings()` | 持久化到 .sk2 文件 |
| `loadAllBuildings()` | 从存档加载所有建筑 |
| `initialiseAllBuildings()` | 后台线程预加载全部蓝图模板到内存 |

### 持久化格式（.sk2）

存档目录为 `saves/Buildings/bX_Y_Z.sk2`，以 `|` 分隔的键值对格式存储：

```
displayname|Bakery
type|commercial
primaryxyz|100.0,64.0,200.0,0
livingxyz|105.0,66.0,203.0,0
buildingcomplete|true
capacity|-1
builddir|-x
...
```

---

## 五、BlockConstructorBox —— 建筑工地入口

### 放置与交互

1. 玩家在世界中放置 `BlockConstructorBox`
2. 播放激活音效（`constructoractivated`）
3. 右键点击时：
   - 根据**玩家站位**判定建筑朝向（+x/-x/+z/-z）
   - 打开 `GuiBuildingConstructor` GUI

### 破坏处理

- 播放断电音效（`powerdown`）
- 自动解雇在此工地工作的 NPC（`selfFire()`）

---

## 六、GuiBuildingConstructor —— 建筑选择 GUI

### 多页交互流程

```
Page 0 — 主菜单
  ├─ "Choose building"  →  Page 1 — 选择建筑类型
  ├─ "Hire builder"     →  GuiEmployFolk（雇佣 Builder 职业的 NPC）
  ├─ "Fire worker"      →  解雇当前工人
  ├─ "Show Employees"   →  GuiShowEmployees（员工列表）
  ├─ "Terraform area"   →  GuiTerraform（地形改造）
  └─ "Hire terraformer" →  GuiEmployFolk（雇佣 Terraformer）

Page 1 — 建筑类型选择
  ├─ Residential  →  Page 2
  ├─ Commercial   →  Page 5
  ├─ Industrial   →  Page 6
  └─ Other        →  Page 7

Page 2/5/6/7 — 建筑蓝图列表
  ├─ 带搜索框（支持 w:5 / d:5 / h:5 按尺寸筛选）
  ├─ 显示每个建筑的尺寸（宽×深×高）、费用、作者
  └─ 分页浏览

Page 8 — 确认建造
  ├─ 显示所需材料清单及堆叠数量
  ├─ "Build it!" → 绑定建筑到工人 → 发送数据包 → 开始自动化建造
  └─ "Go Back"   → 返回蓝图列表
```

---

## 七、JobBuilder —— 自动化建造状态机（核心）

`JobBuilder` 是自动化建造的**执行引擎**，实现了一个 **6 阶段有限状态机**：

```
  ┌──────┐     ┌────────────────┐     ┌──────────┐     ┌───────────────────┐     ┌─────────────┐     ┌──────────┐
  │ IDLE │ ──→ │ WORKERASSIGNED │ ──→ │ BLUEPRINT│ ──→ │ WAITINGFORRESOURCES│ ──→ │ INPROGRESS  │ ──→ │ COMPLETE │
  └──────┘     └────────────────┘     └──────────┘     └───────────────────┘     └─────────────┘     └──────────┘
       ↑                                                                                 │                  │
       └────────────────────────────────────────────────── (材料不足时退回) ──────────────┘                  │
                                                                                                            │
                                                                       NPC 自动辞职 ←─────────────────────┘
```

### 各阶段详解

#### 1. IDLE（空闲）
- 每天开始时或工作完成后回到此状态
- 非白天时间保持 IDLE

#### 2. WORKERASSIGNED（已分配工人）
- 触发条件：白天 + 工人已雇佣
- NPC 走向工地（`gotoXYZ(employedAt)`）
- 与工地距离 ≤ 3 或 < 10（无目的地时）→ 进入 BLUEPRINT

#### 3. BLUEPRINT（查看蓝图）
- NPC 从 `theFolk.theBuilding` 获取建筑对象
- 播放"准备就绪"语音（男女不同音效）
- **生成 EntityConBox**：在工地上方 +2 的 X 位置生成旋转的视觉指示器实体
- 进入 WAITINGFORRESOURCES

#### 4. WAITINGFORRESOURCES（等待材料）
- 搜索工地周围 5 格范围内的箱子/储物方块（`inventoriesFindClosest()`）
- 无箱子 → 提示玩家放置至少一个箱子
- 有箱子 → 打开检查，进入 INPROGRESS
- 建造中途材料不足时也会回到此状态（`step = 3`），定时通知聊天栏（默认 `configMaterialReminderInterval` 分钟一次）

#### 5. INPROGRESS（建造中）—— 核心循环

**三步嵌套遍历 3D 结构数组：**

```
for layer (l: 0 → layerCount)           // 高度（Y 轴）
  for front-to-back (ftb: 0 → ftbCount) // 深度（Z 轴）
    for left-to-right (ltr: 0 → ltrCount)// 宽度（X 轴）
      acount → structure[acount]        // 一维索引进 3D 数组
```

**坐标转换**（根据 buildDirection 将蓝图坐标映射到世界坐标）：

| 方向 | X 偏移 | Z 偏移 |
|------|--------|--------|
| `+z` | `ltr` | `-ftb` |
| `-z` | `-ltr` | `ftb` |
| `+x` | `-ftb` | `-ltr` |
| `-x` | `ftb` | `ltr` |

**每块放置逻辑：**

1. 解析 `structure[acount]` 获得 `blockID:subtype`
2. 检查当前位置是否已有该方块 → 跳过（`alreadyPlaced = true`）
3. 处理特殊方块：
   - `999:999` → 记录为 `livingXYZ`，实际放空气
   - `999:0~9` → 记录为 `blockSpecial`（含 meta），实际放空气
   - 控制方块 ID → 记录 `primaryXYZ` 并保存建筑
4. **先清除**：如果当前方块不是空气，调用 `mineBlockIntoChests()` 挖除并放入箱子
5. **材料检查**：根据游戏模式决定是否从箱子取材料
   - 材料充足 → 从箱子取出 1 个物品（`inventoriesGet()`）
   - 材料不足 → 倒退到 WAITINGFORRESOURCES，聊天栏通知玩家
6. **放置方块**：`jobWorld.setBlock(x, y, z, blockId, subtype, 0x03)`
7. **音效 + 粒子**：每 2 秒播放建筑音效 + 爆炸粒子
8. **技能成长**：`levelBuilder += 0.001 / level`，升级时全服公告
9. **速度控制**：空气/已存在块 `runDelay = 0`（瞬间）；实心块 `runDelay = 2000 / levelBuilder`
10. 内层用 `do-while` 跳过空气块（连续处理直到遇到实心块）

#### 6. COMPLETE（完成）
- 设置 `buildingComplete = true`
- 全服公告 + 播放收银音效
- 保存建筑数据
- 清除工人的 `theBuilding` 引用
- NPC 自动辞职（`selfFire()`）
- 若所有 Builder 都已完成工作，批量标记所有建筑为完成

---

## 八、EntityConBox —— 视觉指示器

- 无碰撞体积（`noClip=true`）、无视锥剔除（`ignoreFrustumCheck=true`）
- 每帧旋转 `boxYaw += 1f`，通过自定义 Render 渲染
- **自检逻辑**（每 10 秒）：
  - 如果关联的 Building 已为 null → 自毁（生成爆炸粒子）
  - 如果周围 5 格内无构造函数块 → 自毁

---

## 九、Job 基类 —— 通用工具集

所有职业（Builder、Miner、Lumberjack 等 22 种）共享的基础设施：

### 箱子管理

| 方法 | 功能 |
|------|------|
| `inventoriesFindClosest(V3, dist)` | 3D 螺旋搜索 IInventory（排除熔炉和风车） |
| `inventoriesGet(chests, item, ...)` | 从多个箱子按需取出物品 |
| `inventoriesPut(chests, stack, ...)` | 将物品存入箱子的空槽或已有堆叠 |
| `inventoriesTransferFromFolk(...)` | 将 NPC 背包内容转移到箱子 |
| `inventoriesTransferToFolk(...)` | 从箱子取物到 NPC 背包 |
| `inventoriesTransferLimitedToFolk(...)` | 限量转移特定物品到 NPC 背包 |
| `getItemCountInChests(chests, is)` | 统计箱子中某物品的总数量 |

### 方块搜索

| 方法 | 功能 |
|------|------|
| `findClosestBlockType(V3, Block, dist)` | 同 Y 层螺旋搜索特定方块 |
| `findClosestBlockType(V3, Block, dist, sky)` | 可指定是否需要见天空 |
| `findClosestBlocks(V3, Block, dist)` | 返回按距离排序的方块位置列表 |
| `setClosestBlocksOfType(...)` | 后台线程异步搜索（支持见天/向下扫描/单层） |
| `findAdjacentSpace(V3, World)` | 搜索相邻空气方块 |
| `getAnimalCountInPen(V3, Class)` | 统计围栏内动物数量 |

### 其他工具

| 方法 | 功能 |
|------|------|
| `mineBlockIntoChests(V3)` | 挖方块并自动翻译矿方块为其掉落物（如煤矿石→煤炭） |
| `translateBlockWhenMined(World, V3)` | 获取方块的挖掘掉落物列表 |
| `findFurnace(V3)` | 搜索最近的熔炉（闲燃或燃烧中） |
| `openCloseChest(chest, msDelay)` | 延迟关闭箱子（模拟 NPC 操作） |
| `getInventoryCount(folk, itemId)` | 统计 NPC 背包中某物品数量 |

### 通勤逻辑

- `onUpdateGoingToWork(FolkData)`：统一处理 NPC 走路上班
  - 怀孕女性自动休产假（`pregnancyStage > 0`）
  - 距离 = 1 → 到达，调用 `onArrivedAtWork()`
  - 距离 1-3 → 瞬移（`GotoMethod.SHIFT`）
  - 白天但未上路 → 开始走向工地

---

## 十、完整工作流程图

```
┌──────────────────────────────────────────────────────────────┐
│ 1. 玩家操作                                                  │
│    放置 BlockConstructorBox                                   │
│    右键 → GuiBuildingConstructor                             │
│    选择建筑类型 → 选择蓝图 → "Build it!"                      │
│    雇佣 Builder 职业的 NPC                                    │
└───────────────────────────┬──────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────┐
│ 2. NPC 上班                                                  │
│    白天 → 走向工地 (gotoXYZ → employedAt)                    │
│    到达 → BLUEPRINT 阶段                                     │
│    生成 EntityConBox 悬浮体                                   │
└───────────────────────────┬──────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────┐
│ 3. 材料准备                                                  │
│    WAITINGFORRESOURCES 阶段                                   │
│    扫描工地周围 5 格内箱子 (inventoriesFindClosest)           │
│    ├─ 无箱子 → 提示玩家放置                                   │
│    └─ 有箱子 → 准备建造                                       │
└───────────────────────────┬──────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────┐
│ 4. 逐块建造循环 (INPROGRESS)                                 │
│                                                              │
│    for 层 (0 → layerCount)                                   │
│      for 深度 (0 → ftbCount)                                 │
│        for 宽度 (0 → ltrCount)                               │
│          │                                                   │
│          ├─ 解析 structure[acount] → blockID:meta            │
│          ├─ 坐标转换 (buildDirection)                         │
│          ├─ 跳过已存在方块                                     │
│          ├─ 挖除现有方块 → 掉落物入箱                          │
│          ├─ 从箱子取材料                                       │
│          │   ├─ 充足 → 放置方块                                │
│          │   │   ├─ world.setBlock()                          │
│          │   │   ├─ 音效 + 粒子 + 技能升级                     │
│          │   │   └─ 扣除信用点 (-0.02)                        │
│          │   └─ 不足 → 退回 WAITINGFORRESOURCES               │
│          │       └─ 通知玩家缺什么材料                         │
│          └─ 空气块 → 跳过 (runDelay=0)                        │
│                                                              │
│    建造速度 = 2000 / levelBuilder (ms/块)                     │
└───────────────────────────┬──────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────┐
│ 5. 建造完成                                                  │
│    buildingComplete = true                                    │
│    全服公告 + 收银音效                                        │
│    保存建筑数据 (.sk2)                                        │
│    NPC 自动辞职                                               │
│    EntityConBox 自毁                                          │
└──────────────────────────────────────────────────────────────┘
```

---

## 十一、架构亮点

1. **压缩字符蓝图格式**：单字符映射方块类型，每层一行文本，人可读写且紧凑
2. **后台异步加载**：`initialiseAllBuildings()` 以 `Thread.sleep(30)` 间隔在独立线程中加载所有模板，避免主线程卡顿
3. **状态机驱动**：清晰的 6 阶段分离，材料不足时优雅暂停等待而非报错失败
4. **朝向感知**：通过玩家站位自动判定建筑方向，支持 4 种朝向的坐标映射
5. **三模式物料体系**：NORMAL（简化材料）/ CREATIVE（免费）/ HARDCORE（全面材料），灵活适配不同玩法
6. **技能成长系统**：Builder 每放一块经验微增，升级后建造速度翻倍提升
7. **增量消耗策略**：NORMAL 模式下仅基础方块（木板、圆石等）需要材料，门/楼梯/栅栏等装饰性方块免费，大幅降低资源管理负担
8. **NPC 全生命周期管理**：从雇佣到通勤到工作到完工自动辞职，完整的 NPC 就业循环
9. **持久化容错**：保存时检查控制方块/构造函数块是否存在，不存在的建筑自动清理存档文件
10. **优雅的空块跳过**：`do-while` 循环连续跳过空气块和已存在方块，只在遇到新实心块时才受速度延迟影响

---

## 十二、关键代码路径索引

| 文件 | 路径 |
|------|------|
| 建筑数据模型 | `info/satscape/simukraft/common/Building.java` |
| 构造函数块 | `info/satscape/simukraft/common/BlockConstructorBox.java` |
| 构造函数实体 | `info/satscape/simukraft/common/EntityConBox.java` |
| 建筑工 AI | `info/satscape/simukraft/common/jobs/JobBuilder.java` |
| 职业基类 | `info/satscape/simukraft/common/jobs/Job.java` |
| 建筑选择 GUI | `info/satscape/simukraft/client/Gui/GuiBuildingConstructor.java` |
| 主 Mod 类 | `info/satscape/simukraft/common/ModSimukraft.java` |
| 标记点 | `info/satscape/simukraft/common/Marker.java` |
| 建筑蓝图 | `Simukraft/buildings/{residential,commercial,industrial,other}/*.txt` |

# 关于兼容 Sable（物理化结构上的自动化建造）

> 适用范围：NeoForge 1.21.1 + Java 21；Sable 2.0.5（dev.ryanhcode.sable）/ Create 6.0.10 / Create-Aeronautics 1.3.1
> 状态：**已停用**（见「当前状态」）。本文记录目标、实现、调研结论、保存卡死根因与恢复方法。

---

## 1. 目标

- 本模组**不依赖任何模组**，也能在"被物理化的方块"上放置方块（自动化建筑）。
- 物理化 = 方块被组装进 Sable 的 **SubLevel（子世界）**（航空学飞船 / Simulated 物理组装器）。
- 无硬依赖：Sable 仅作为 compileOnly 编译期引用，运行时按需守卫加载；玩家不装 Sable 完全不受影响。

## 2. Sable 架构要点（源码调研结论）

| 概念 | 说明 |
|---|---|
| SubLevel / ServerSubLevel | 物理化结构的载体：一个独立 chunk 网格（LevelPlot）+ 一个位姿（Pose3d，局部↔世界映射） |
| plotyard（20.48M） | plot 网格位于主世界外约 20,480,000 坐标处（DEFAULT_ORIGIN=10000 plots x 2048 blocks） |
| 局部坐标 | 玩家站在结构上时，**服务端实体/方块坐标变成局部坐标（20.48M）**；客户端经位姿渲染回世界位置 |
| plot 区块注册 | ServerLevelPlot.addChunkHolder 把 plot chunk 注册进**父级 ServerChunkCache**（20.48M 坐标） |
| 保存跳过 | ChunkMapMixin.sable$saveChunkIfNeeded 只跳过**周期性保存**的 PlotChunkHolder；全量保存（退出/存档）会序列化脏 plot 区块 |
| 客户端同步 | 客户端只在开始跟踪时收到 plot 区块；**运行中新增的 plot 区块不补发** |
| 实体跟踪 | 实体碰撞/吸附在世界坐标工作；实体类型须在 sable:retain_in_sub_level 实体标签内才不被踢出子世界 |

关键 API（均为 Sable 公共面）：
- Sable.HELPER.getContaining(level, pos) —— 局部坐标 -> 所在子世界（O(1) chunk 查找）
- Sable.HELPER.getAllIntersecting(level, aabb) —— 世界坐标空间查询
- SubLevel.logicalPose() —— 局部<->世界变换（实测**服务端位姿不可靠**：局部 20,481,034 -> 世界 (2, -59, 0)，与客户端渲染位姿不一致）
- LevelPlot.getChunk / toLocal / newEmptyChunk / expandIfNecessary / onBlockChange

## 3. 已实现（代码均在 src/main/java/com/wenzai/neosim/compat/）

- IPhysicsAdapter —— 纯 Minecraft 类型接口（getBlockState / setBlock / destroyBlock / isAvailable）
- SablePhysicsAdapter —— 双坐标空间适配器（局部 getContaining / 世界 getAllIntersecting+变换）；
  含 plot 直接写路径（plot chunk + setUnsaved(false) + sendBlockUpdated）、NPC 跟踪（attachToSubLevel）、toWorld 投影
- PhysicsWorld —— 门面：Class.forName + isLoaded("sable") 守卫加载，全程 try/catch 降级
- build.gradle —— compileOnly files("libs/sable-neoforge-1.21.1-2.0.5.jar", "libs/sable-companion-common-1.21.1-1.6.0.jar")
- 附带安全修复：onPlayerLogout 无限 AABB（Sable 会拦截并中止无限范围实体查询）、
  模盒消失自动取消任务、NPC 世界坐标生成、data/sable/tags/entity_type/retain_in_sub_level.json

## 4. 保存卡死根因（线程转储确诊）

**症状**：退出保存时一直停在"保存世界中"。

**线程转储（两份，间隔 33s，桌面 dump1.txt / dump2.txt）**：

    "Server thread" RUNNABLE
      at java.util.concurrent.CompletableFuture.postComplete / postFire
      at net.minecraft.server.level.ChunkMap.processUnloads(ChunkMap.java:490-492)
      at net.minecraft.server.level.ChunkMap.tick
      at net.minecraft.server.MinecraftServer.stopServer

Server 线程单核满载自旋（33 秒内 CPU 143s->199s），卸载队列（Sable 用 mixin 替换的
ConcurrentLinkedDeque）在 ConcurrentLinkedDeque.skipDeletedSuccessors 死循环。

**归因**：
- Sable 的 plot 区块注册在父级 chunkmap（20.48M），且"不通过 vanilla 方式卸载"
  （其 ChunkMapMixin.sable$hasWork 注释自述）。
- 服务器停止时 vanilla processUnloads 处理卸载队列 -> 与 Sable 物理线程对同一
  ConcurrentLinkedDeque 并发操作 -> 节点链表病态 -> 无限自旋。
- **控制测试**（移除 neo_sim 后同一世界保存）不卡 -> 触发与模组对子世界的访问/写入相关；
  即使不写 20.48M 区块（世界坐标放置模式）仍卡 -> 任何对子世界的触碰（含查询）都会激化。
- **结论：这是 Sable 物理线程与卸载队列的并发问题，从模组内部无法安全绕过。**

## 5. 当前状态（稳定版）

| 项 | 状态 |
|---|---|
| 普通地面建造/雇佣/存档 | 完全正常（原版路径） |
| 物理化结构上的建造 | 拒绝创建任务（ConstructionEngine.createBuilding 检查 PhysicsWorld.isInSubLevel，日志 reject - constructor on physicized structure (unsupported)） |
| Sable 适配器 | 停用（PhysicsWorld.load() 返回 null，代码保留可恢复） |
| onPlayerLogout 无限 AABB | 修复 |
| 模盒消失自动取消任务 | 保留 |
| NPC 世界坐标生成 + retain 标签 | 保留 |
| 保存卡死 | 已消除（不再触碰子世界） |

## 6. 恢复"物理化结构上建造"的路径

1. **前提**：Sable 修复 ChunkMap.processUnloads 自旋（转储 dump1/dump2.txt 可直接作为
   ryanhcode/sable 的 bug 报告材料）。
2. 恢复 PhysicsWorld.load() 的 Class.forName 加载逻辑。
3. 移除 ConstructionEngine.createBuilding 中的 isInSubLevel 拒绝。
4. 重新评估位置投影：logicalPose 服务端位姿与客户端渲染位姿不一致（见第 2 节），
   世界坐标放置会偏位；需确认甲板真实世界坐标（站甲板上按 F3）后校准。

## 7. 相关文件

| 文件 | 说明 |
|---|---|
| compat/PhysicsWorld.java | 门面；load() 已停用 |
| compat/SablePhysicsAdapter.java | 适配器（双空间、plot 直写、NPC 跟踪、toWorld） |
| compat/IPhysicsAdapter.java | 接口 |
| libs/sable-*.jar | compileOnly 依赖（Modrinth 下载 + jarJar 内解出的 companion） |
| data/sable/tags/entity_type/retain_in_sub_level.json | NPC 保留标签 |
| 桌面/dump1.txt, dump2.txt | 保存卡死线程转储（Sable bug 报告材料） |

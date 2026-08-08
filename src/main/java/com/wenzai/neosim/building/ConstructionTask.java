package com.wenzai.neosim.building;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.NpcGoals;
import com.wenzai.neosim.schematic.CoordTransform;
import com.wenzai.neosim.schematic.LightweightBlockContainer;
import com.wenzai.neosim.schematic.MaterialCalculator;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SpecialMarker;
import com.wenzai.neosim.storage.FileCreater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConstructionTask
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int BASE_DELAY = 2000;

    // 建造者等级上限
    private static final float MAX_LEVEL = 10.0f;

    // 等待材料时3秒检查一次
    private static final int WAITING_CHECK_DELAY = 3000;
    private static final int SEARCH_RADIUS = 5;

    // 抬手时长
    private static final int RAISE_ANIM_MS = 400;

    // 放手时长
    private static final int LOWER_ANIM_MS = 400;

    private final BuildingInstance building;
    private final ServerLevel level;
    private final SchematicData schematic;

    private BuildingInstance.BuildState currentState = BuildingInstance.BuildState.IDLE;
    private int resumeIndex;
    private long lastTickTime;
    private float builderLevel = 1.0f;
    private int buildDelay = BASE_DELAY;
    private boolean paused;

    // 两轮建造
    private boolean phaseTwo;

    // 抬手
    private long animStartTime;
    private Entity builderNpc;

    private long lastWaitCheck;
    
    // 最近一次已通知的缺料
    private Item lastNotifiedMissingItem;

    // 当前缺料
    private Item lastMissingMaterial;

    private List<ChestBlockEntity> nearbyChests;

    public ConstructionTask(BuildingInstance building, ServerLevel level)
    {
        this.building = building;
        this.level = level;
        this.schematic = building.getSchematic();
        this.resumeIndex = building.getBuildProgress();
        this.phaseTwo = building.isPhaseTwo();
        this.paused = building.isPaused();
        updateBuildSpeed(builderLevel);
    }
    
    // 分配NPC后调用
    public void assignWorker()
    {
        if (currentState == BuildingInstance.BuildState.IDLE)
        {
            currentState = BuildingInstance.BuildState.WORKER_ASSIGNED;
            building.setState(currentState);
            LOGGER.info("NeoSim-ConstructionTask: worker assigned — {}", building.getSchematicName());
        }
    }

    // NPC到达后调用
    public void onWorkerArrived()
    {
        if (currentState == BuildingInstance.BuildState.WORKER_ASSIGNED
                || currentState == BuildingInstance.BuildState.WAITING_FOR_WORKER)
        {
            currentState = BuildingInstance.BuildState.LOADING_BLUEPRINT;
            building.setState(currentState);
            nearbyChests = findNearbyChestsNow();
            LOGGER.info("NeoSim-ConstructionTask: worker arrived, loading blueprint — {}",
                    building.getSchematicName());
        }
    }

    // 每tick调用
    public void tick()
    {
        if (paused) return;
        if (currentState == BuildingInstance.BuildState.COMPLETE)
        {
            return;
        }

        // 夜晚
        if (isNightTime())
        {
            if (isWorkerOnShift())
            {
                goOffWork();
            }
            else
            {
                restNewWorker();
            }
            return;
        }
        ensureWorkerAtSite();

        if (!hasWorker())
        {
            if (currentState != BuildingInstance.BuildState.WAITING_FOR_WORKER)
            {
                currentState = BuildingInstance.BuildState.WAITING_FOR_WORKER;
                building.setState(currentState);
                setBuilderAnim(0.0F);
                clearBuilderHand();
                LOGGER.info("NeoSim-ConstructionTask: waiting for worker — {}",
                        building.getSchematicName());
            }
            return;
        }
        if (currentState == BuildingInstance.BuildState.WAITING_FOR_WORKER)
        {
            // 工人已分配：等待NPC
            currentState = BuildingInstance.BuildState.WORKER_ASSIGNED;
            building.setState(currentState);
            return;
        }
        if (currentState == BuildingInstance.BuildState.WORKER_ASSIGNED)
        {
            // 等NPC到达模盒正上方
            resolveBuilderNpc();
            BlockPos box = building.getConstructorPos();
            if (box == null) box = building.getControlBoxPos();
            if (builderNpc != null && NpcGoals.MoveToSiteGoal.isAboveSite(builderNpc, box))
            {
                onWorkerArrived();
            }
            return;
        }
        if (currentState == BuildingInstance.BuildState.IDLE)
        {
            return;
        }

        // 加载蓝图
        if (currentState == BuildingInstance.BuildState.LOADING_BLUEPRINT)
        {
            currentState = BuildingInstance.BuildState.BUILDING;
            building.setState(currentState);
            LOGGER.info("NeoSim-ConstructionTask: building started — {}", building.getSchematicName());
        }

        // 等待材料
        if (currentState == BuildingInstance.BuildState.WAITING_FOR_RESOURCES)
        {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastWaitCheck < WAITING_CHECK_DELAY) return;
            lastWaitCheck = nowMs;

            Item needed = getNextBlockItem();
            if (needed == null || hasMaterial(needed))
            {
                currentState = BuildingInstance.BuildState.BUILDING;
                building.setState(currentState);
                lastNotifiedMissingItem = null;
                lastMissingMaterial = null;
                
                // 材料补足后抬手
                animStartTime = System.currentTimeMillis();
                LOGGER.info("NeoSim-ConstructionTask: resources replenished, resuming");
            }
            else
            {
                // 继续等待，缺料变化时聊天栏提醒
                
                // 手臂放下
                setBuilderAnim(0.0F);
                clearBuilderHand();
                notifyMissingMaterial(needed);
                return;
            }
        }

        // 速度控制+抬手
        long now = System.currentTimeMillis();
        long elapsed = now - animStartTime;
        if (!isCreativeMode() && elapsed < buildDelay)
        {
            if (elapsed < LOWER_ANIM_MS)
            {
                setBuilderAnim(1.0F - elapsed / (float) LOWER_ANIM_MS);
            }
            else
            {
                long raiseStart = buildDelay - RAISE_ANIM_MS;
                if (elapsed < raiseStart)
                {
                    // 手放下
                    setBuilderAnim(0.0F);
                }
                else
                {
                    // 抬手
                    setBuilderAnim((elapsed - raiseStart) / (float) RAISE_ANIM_MS);
                }
            }
            return;
        }
        animStartTime = now;
        setBuilderAnim(1.0F);

        // 建造循环
        LightweightBlockContainer container = schematic.getBlockContainer();
        int sx = container.getSizeX();
        int sz = container.getSizeZ();
        int totalVolume = container.getTotalVolume();
        Map<BlockPos, SpecialMarker> specialMarkers = schematic.getSpecialMarkers();
        BlockPos.MutableBlockPos markerScanPos = new BlockPos.MutableBlockPos();

        while (resumeIndex < totalVolume)
        {
            int layer = resumeIndex / (sx * sz);
            int depth = (resumeIndex / sx) % sz;
            int width = resumeIndex % sx;

            BlockState desired = container.get(width, layer, depth);

            // 特殊标记
            SpecialMarker marker = specialMarkers != null
                    ? specialMarkers.get(markerScanPos.set(width, layer, depth)) : null;
            if (marker != null)
            {
                if (!phaseTwo)
                {
                    placeMarkerBlock(marker, width, layer, depth);
                }
                resumeIndex++;
                building.setBuildProgress(resumeIndex);
                continue;
            }

            if (desired.isAir())
            {
                resumeIndex++;
                continue;
            }

            if (MaterialCalculator.isAttachedBlock(desired) != phaseTwo)
            {
                resumeIndex++;
                continue;
            }

            BlockPos worldPos = building.blueprintToWorld(width, layer, depth);
            BlockState current = level.getBlockState(worldPos);

            // 应用镜像/旋转
            BlockState toPlace = CoordTransform.transformState(desired, building.getFacing());
            if (building.getMirror() != net.minecraft.world.level.block.Mirror.NONE)
            {
                toPlace = toPlace.mirror(building.getMirror());
            }
            if (building.getRotation() != net.minecraft.world.level.block.Rotation.NONE)
            {
                toPlace = toPlace.rotate(building.getRotation());
            }

            // 依附性方块朝向贴着实际支撑
            if (MaterialCalculator.isAttachedBlock(desired))
            {
                toPlace = fixAttachedFacing(worldPos, toPlace);
                if (toPlace == null)
                {
                    LOGGER.warn("NeoSim-ConstructionTask: skip '{}' at {} — no support",
                            desired.getBlock().getDescriptionId(), worldPos);
                    resumeIndex++;
                    continue;
                }
            }

            // 连接性方块：放置时按实际相邻方块重算连接
            if (isConnectiveBlock(toPlace))
            {
                toPlace = fixConnectiveConnections(worldPos, toPlace);
            }

            if (current.equals(toPlace))
            {
                resumeIndex++;
                continue;
            }

            // 双方块：目标位置已有同类型方块，跳过避免覆盖
            if ((toPlace.getBlock() instanceof DoorBlock || toPlace.getBlock() instanceof BedBlock)
                    && current.getBlock() == toPlace.getBlock())
            {
                resumeIndex++;
                continue;
            }

            // 清除现有方块
            if (!current.isAir())
            {
                level.destroyBlock(worldPos, true);
            }

            // 材料检查与消耗
            if (MaterialCalculator.requiresMaterial(desired) && !isCreativeMode())
            {
                Item item = desired.getBlock().asItem();
                if (!hasMaterial(item))
                {
                    currentState = BuildingInstance.BuildState.WAITING_FOR_RESOURCES;
                    building.setState(currentState);
                    notifyMissingMaterial(item);
                    LOGGER.info("NeoSim-ConstructionTask: waiting for {}", item.getDescription().getString());

                    return;
                }
                extractMaterial(item);
            }

            // 渲染NPC手持要放置的方块
            resolveBuilderNpc();
            if (builderNpc != null)
            {
                builderNpc.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.item.ItemStack(toPlace.getBlock().asItem()));
            }

            // 放置方块
            level.setBlock(worldPos, toPlace, Block.UPDATE_ALL);

            // 双方块补齐：自动补另一半
            if (toPlace.getBlock() instanceof DoorBlock)
            {
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf half =
                        toPlace.getValue(DoorBlock.HALF);
                if (half == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
                {
                    // 放下半格，补上半格
                    BlockState upper = toPlace.setValue(DoorBlock.HALF,
                            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                    if (level.getBlockState(worldPos.above()).isAir())
                    {
                        level.setBlock(worldPos.above(), upper, Block.UPDATE_ALL);
                    }
                }
                else if (level.getBlockState(worldPos.below()).isAir())
                {
                    // 上半格先：补下半格
                    BlockState lower = toPlace.setValue(DoorBlock.HALF,
                            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
                    level.setBlock(worldPos.below(), lower, Block.UPDATE_ALL);
                }

                // 双开门：与相邻同朝向门组成对开（铰链取反），复刻原版放置时的自动配对
                fixDoubleDoor(worldPos);
            }
            else if (toPlace.getBlock() instanceof BedBlock)
            {
                net.minecraft.world.level.block.state.properties.BedPart part =
                        toPlace.getValue(BedBlock.PART);

                // 用容器相邻的床格定位另一半
                BlockPos other = findAdjacentBedCellWorld(width, layer, depth, container, sx, sz);
                if (other == null)
                {
                    Direction bedFacing = toPlace.getValue(BedBlock.FACING);
                    other = part == net.minecraft.world.level.block.state.properties.BedPart.HEAD
                            ? worldPos.relative(bedFacing.getOpposite())
                            : worldPos.relative(bedFacing);
                }
                BlockState otherState = toPlace.setValue(BedBlock.PART,
                        part == net.minecraft.world.level.block.state.properties.BedPart.HEAD
                                ? net.minecraft.world.level.block.state.properties.BedPart.FOOT
                                : net.minecraft.world.level.block.state.properties.BedPart.HEAD);
                if (!level.getBlockState(other).equals(otherState))
                {
                    level.setBlock(other, otherState, Block.UPDATE_ALL);
                }
            }

            // 放置音效（随机音高防单调）
            level.playSound(null, worldPos,
                    desired.getSoundType().getPlaceSound(),
                    SoundSource.BLOCKS, 1.0F, 0.8F + level.random.nextFloat() * 0.4F);
            resumeIndex++;
            building.setBuildProgress(resumeIndex);

            // 技能成长与信用点扣除
            int b4 = (int) Math.floor(builderLevel);
            if (builderLevel < MAX_LEVEL)
            {
                builderLevel += 0.001f / b4;
            }
            int aft = (int) Math.floor(builderLevel);
            if (aft > b4)
            {
                resolveBuilderNpc();
                if (builderNpc != null)
                {
                    builderNpc.setJobArchitect((byte) Math.min(aft, (int) MAX_LEVEL));
                    String city = builderNpc.getCityName();
                    if (!city.isEmpty() && level.getServer() != null)
                    {
                        if (level.getServer().isDedicatedServer())
                        {
                            com.wenzai.neosim.storage.NpcData.save(builderNpc, city);
                        }
                        else
                        {
                            com.wenzai.neosim.storage.NpcData.save(builderNpc, city,
                                    level.getServer().getWorldData().getLevelName());
                        }
                    }
                }
            }
            
            updateBuildSpeed(builderLevel);

            // 一次tick只放一个实心方块
            break;
        }

        if (resumeIndex >= totalVolume && !phaseTwo)
        {
            phaseTwo = true;
            resumeIndex = 0;
            building.setPhaseTwo(true);
            building.setBuildProgress(0);
            setBuilderAnim(0.0F);
            LOGGER.info("NeoSim-ConstructionTask: phase 1 done, building attached blocks — {}",
                    building.getSchematicName());
        }

        // 完工
        if (resumeIndex >= totalVolume && phaseTwo)
        {
            currentState = BuildingInstance.BuildState.COMPLETE;
            building.setState(currentState);
            building.setBuildingComplete(true);
            building.setAssignedBuilder(null);
            building.setBuilderName(null);
            setBuilderAnim(0.0F);
            clearBuilderHand();
            announceComplete();

            // 生活点注册：建造者优先入住，剩余空位分给城市无家NPC
            com.wenzai.neosim.npc.CityLivingManager.onBuildingCompleted(level, building);

            LOGGER.info("NeoSim-ConstructionTask: Complete {} (Lv.{}, builder auto-resigned)",
                    building.getSchematicName(), (int) builderLevel);
        }
    }

    // 特殊标记
    private void placeMarkerBlock(SpecialMarker marker, int width, int layer, int depth)
    {
        BlockState markerState = marker.toBlockState();
        if (markerState == null) return;

        BlockPos worldPos = building.blueprintToWorld(width, layer, depth);
        if (!level.getBlockState(worldPos).equals(markerState))
        {
            level.setBlock(worldPos, markerState, Block.UPDATE_ALL);
        }

        // 控制箱：登记文件
        if (marker == SpecialMarker.CONTROL_BOX)
        {
            recordControlBox(worldPos);
        }
    }

    // 写入控制箱记录，放置者未入城时跳过（与任务持久化一致）
    private void recordControlBox(BlockPos boxPos)
    {
        String city = cityOf(building, level);
        if (city == null || city.isEmpty())
        {
            LOGGER.warn("NeoSim-ConstructionTask: skip control box record '{}' at {} — placer has no city",
                    building.getSchematicName(), boxPos);
            return;
        }
        ControlBoxPersistence.ControlBoxRecord rec = ControlBoxPersistence.ControlBoxRecord.of(
                boxPos, building.getControlBoxPos(), building.getSchematicName(),
                building.getPlacerName(), building.getAuthor(),
                livingPointsOf(building));
        
        // 定价
        if (building.getSchematic() != null)
        {
            rec = rec.withRent(building.getSchematic().getTotalSolidBlocks() * Config.LIFE_RENT_PER_BLOCK.get());
        }
        ControlBoxPersistence.addOrUpdate(level, city, rec);
    }

    // 建筑全部生活点的世界坐标
    private static List<BlockPos> livingPointsOf(BuildingInstance building)
    {
        List<BlockPos> out = new ArrayList<>();
        Map<BlockPos, SpecialMarker> markers = building.getSchematic().getSpecialMarkers();
        if (markers != null)
        {
            for (Map.Entry<BlockPos, SpecialMarker> e : markers.entrySet())
            {
                if (e.getValue() == SpecialMarker.LIVING_POINT)
                {
                    BlockPos l = e.getKey();
                    out.add(building.blueprintToWorld(l.getX(), l.getY(), l.getZ()));
                }
            }
        }
        return out;
    }

    // 在容器局部坐标中查找相邻的床格，返回其世界坐标
    private BlockPos findAdjacentBedCellWorld(int width, int layer, int depth,
                                               LightweightBlockContainer container, int sx, int sz)
    {
        int[][] dirs = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int[] d : dirs)
        {
            int nx = width + d[0];
            int nz = depth + d[1];
            if (nx >= 0 && nx < sx && nz >= 0 && nz < sz)
            {
                if (container.get(nx, layer, nz).getBlock() instanceof BedBlock)
                {
                    return building.blueprintToWorld(nx, layer, nz);
                }
            }
        }
        return null;
    }

    // 继续等待
    private boolean hasMaterial(Item item)
    {
        nearbyChests = findNearbyChestsNow();
        return InventoryManager.countItems(nearbyChests, item) > 0;
    }

    private List<ChestBlockEntity> findNearbyChestsNow()
    {
        List<ChestBlockEntity> chests = new ArrayList<>(
                InventoryManager.findNearbyChests(level, building.getControlBoxPos()));
        BlockPos box = building.getConstructorPos();
        if (box != null && !box.equals(building.getControlBoxPos()))
        {
            for (ChestBlockEntity chest : InventoryManager.findNearbyChests(level, box))
            {
                if (!chests.contains(chest))
                {
                    chests.add(chest);
                }
            }
        }
        return chests;
    }

    private void extractMaterial(Item item)
    {
        InventoryManager.extractItem(nearbyChests, item, 1);
    }

    // 依附性方块朝向修正：找不到支撑跳过
    private BlockState fixAttachedFacing(BlockPos worldPos, BlockState state)
    {
        Block block = state.getBlock();

        // 贴墙类
        if (block instanceof LadderBlock
                || block instanceof WallTorchBlock
                || block instanceof RedstoneWallTorchBlock
                || block instanceof WallSignBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof TripWireHookBlock
                || block instanceof CocoaBlock
                || block instanceof VineBlock)
        {
            if (block instanceof VineBlock)
            {
                for (Direction d : Direction.Plane.HORIZONTAL)
                {
                    if (state.getValue(vineProperty(d)) && hasSupport(worldPos, d))
                    {
                        // 蓝图方向已贴墙
                        return state;
                    }
                }
                for (Direction d : Direction.Plane.HORIZONTAL)
                {
                    if (hasSupport(worldPos, d))
                    {
                        return state.setValue(vineProperty(d), true);
                    }
                }
                return null;
            }

            net.minecraft.world.level.block.state.properties.Property<Direction> facingProp =
                    block instanceof CocoaBlock
                            ? CocoaBlock.FACING
                            : BlockStateProperties.HORIZONTAL_FACING;
            if (!state.hasProperty(facingProp)) return state;
            Direction facing = state.getValue(facingProp);

            if (hasSupport(worldPos, facing.getOpposite())) return state;

            for (Direction d : Direction.Plane.HORIZONTAL)
            {
                if (d != facing && hasSupport(worldPos, d.getOpposite()))
                {
                    return state.setValue(facingProp, d);
                }
            }
            return null;
        }

        // 地面类
        if (block instanceof BushBlock
                || block instanceof CropBlock
                || block instanceof StemBlock
                || block instanceof AttachedStemBlock
                || block instanceof SaplingBlock
                || block instanceof SugarCaneBlock
                || block instanceof FlowerPotBlock
                || block instanceof StandingSignBlock
                || block instanceof TorchBlock
                || block instanceof PressurePlateBlock
                || block instanceof BedBlock
                || block instanceof SnowLayerBlock
                || block instanceof AnvilBlock
                || block instanceof DoorBlock
                || block instanceof BaseRailBlock
                || block instanceof RedStoneWireBlock
                || block instanceof TripWireBlock
                || block instanceof BannerBlock
                || block instanceof CarpetBlock)
        {
            return level.getBlockState(worldPos.below()).isAir() ? null : state;
        }

        return state;
    }

    // 连接性方块
    private static boolean isConnectiveBlock(BlockState state)
    {
        return state.getBlock() instanceof CrossCollisionBlock
                || state.getBlock() instanceof WallBlock;
    }

    // 逐个方向重算连接，使新放的连接块自动相连
    private BlockState fixConnectiveConnections(BlockPos pos, BlockState state)
    {
        for (Direction dir : Direction.values())
        {
            BlockPos neighborPos = pos.relative(dir);
            state = state.updateShape(dir, level.getBlockState(neighborPos), level, pos, neighborPos);
        }
        return state;
    }

    // 双开门：放置门后检查水平相邻的同朝向门，铰链取反组成对开
    // 直接 setBlock 绕过了原版 DoorBlock 放置时的自动配对，蓝图里两扇门铰链相同时会变成两扇相同的门
    private void fixDoubleDoor(BlockPos doorPos)
    {
        // 一律以下半格为准
        BlockPos lowerPos = doorPos;
        BlockState lower = level.getBlockState(lowerPos);
        if (!(lower.getBlock() instanceof DoorBlock)) return;
        if (lower.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
        {
            lowerPos = lowerPos.below();
            lower = level.getBlockState(lowerPos);
            if (!(lower.getBlock() instanceof DoorBlock)) return;
        }

        Direction facing = lower.getValue(BlockStateProperties.HORIZONTAL_FACING);
        // 先查逆时针侧，再查顺时针侧，与原版 DoorBlock 判定顺序一致
        for (Direction side : new Direction[] { facing.getCounterClockWise(), facing.getClockWise() })
        {
            BlockState neighbor = level.getBlockState(lowerPos.relative(side));
            if (neighbor.getBlock() instanceof DoorBlock
                    && neighbor.getValue(BlockStateProperties.HORIZONTAL_FACING) == facing
                    && neighbor.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
            {
                net.minecraft.world.level.block.state.properties.DoorHingeSide neighborHinge =
                        neighbor.getValue(BlockStateProperties.DOOR_HINGE);
                // 取反铰链，1.21 的 DoorHingeSide 没有 getOpposite()
                net.minecraft.world.level.block.state.properties.DoorHingeSide opposite =
                        neighborHinge == net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT
                                ? net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT
                                : net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT;
                if (lower.getValue(BlockStateProperties.DOOR_HINGE) == opposite)
                {
                    return; // 已配对
                }
                // 本门两格一起改铰链，保持上下一致
                level.setBlock(lowerPos, lower.setValue(BlockStateProperties.DOOR_HINGE, opposite), Block.UPDATE_ALL);
                BlockState upper = level.getBlockState(lowerPos.above());
                if (upper.getBlock() instanceof DoorBlock)
                {
                    level.setBlock(lowerPos.above(),
                            upper.setValue(BlockStateProperties.DOOR_HINGE, opposite), Block.UPDATE_ALL);
                }
                return;
            }
        }
    }

    private boolean hasSupport(BlockPos pos, Direction dir)
    {
        return !level.getBlockState(pos.relative(dir)).isAir();
    }

    // 方向属性
    private static net.minecraft.world.level.block.state.properties.BooleanProperty vineProperty(Direction d)
    {
        return switch (d)
        {
            case NORTH -> VineBlock.NORTH;
            case SOUTH -> VineBlock.SOUTH;
            case EAST -> VineBlock.EAST;
            default -> VineBlock.WEST;
        };
    }

    // 下一个要放的方块所需材料
    public Item getNextBlockItem()
    {
        LightweightBlockContainer container = schematic.getBlockContainer();
        int sx = container.getSizeX();
        int sz = container.getSizeZ();
        for (int i = resumeIndex; i < container.getTotalVolume(); i++)
        {
            int layer = i / (sx * sz);
            int depth = (i / sx) % sz;
            int width = i % sx;
            BlockState desired = container.get(width, layer, depth);
            if (desired.isAir()) continue;

            // 检查当前轮次的方块
            if (MaterialCalculator.isAttachedBlock(desired) != phaseTwo) continue;
            BlockPos worldPos = building.blueprintToWorld(width, layer, depth);
            BlockState current = level.getBlockState(worldPos);
            if (current.equals(CoordTransform.transformState(desired, building.getFacing()))) continue;
            if (MaterialCalculator.requiresMaterial(desired))
            {
                return desired.getBlock().asItem();
            }
            return null;
        }
        return null;
    }

    // 聊天栏提醒缺料
    private void notifyMissingMaterial(Item item)
    {
        if (item == null) return;

        // 无论是否已通知过，都刷新缓存供GUI状态页读取
        this.lastMissingMaterial = item;
        if (item == lastNotifiedMissingItem) return;
        lastNotifiedMissingItem = item;

        if (level.getServer() == null) return;
        sendPacketToCityPlayers(new com.wenzai.neosim.network.ServerToClientPayloads.ResourceShortagePacket(
                building.getSchematicName(),
                item.getDescription().getString(),
                countMissing(item),
                building.getConstructorPos() != null
                        ? building.getConstructorPos() : building.getControlBoxPos()));
    }

    // 只发给该建筑所属城市的在线玩家(放置者未入城时发给所有玩家)
    private void sendPacketToCityPlayers(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload)
    {
        String cityName = cityOf(building, level);
        boolean dedicated = level.getServer().isDedicatedServer();
        String saveName = dedicated ? null : level.getServer().getWorldData().getLevelName();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers())
        {
            if (cityName != null && !cityName.isEmpty())
            {
                String pname = player.getName().getString();
                boolean inCity = dedicated
                        ? FileCreater.isPlayerInCity(cityName, pname)
                        : FileCreater.isPlayerInCity(cityName, saveName, pname);
                if (!inCity) continue;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload);
        }
    }

    // 统计剩余蓝图中该物品的需求量与箱子存量的差额（至少为1）
    private int countMissing(Item item)
    {
        if (nearbyChests == null || nearbyChests.isEmpty())
        {
            nearbyChests = findNearbyChestsNow();
        }
        int need = 0;
        LightweightBlockContainer container = schematic.getBlockContainer();
        int sx = container.getSizeX();
        int sz = container.getSizeZ();
        for (int i = resumeIndex; i < container.getTotalVolume(); i++)
        {
            int layer = i / (sx * sz);
            int depth = (i / sx) % sz;
            int width = i % sx;
            BlockState st = container.get(width, layer, depth);
            if (st.getBlock().asItem() == item) need++;
        }
        int have = InventoryManager.countItems(nearbyChests, item);
        return Math.max(1, need - have);
    }

    // 完工公告
    private void announceComplete()
    {
        sendPacketToCityPlayers(new com.wenzai.neosim.network.ServerToClientPayloads.BuildingCompletePacket(
                building.getSchematicName(), building.getControlBoxPos()));
        LOGGER.info("NeoSim-ConstructionTask: announce complete — {}", building.getSchematicName());
    }

    // 从建筑所属城市的资金中扣除费用
    public static void deductCredits(ServerLevel level, BuildingInstance building, double amount)
    {
        if (amount <= 0 || level.getServer() == null) return;
        String city = cityOf(building, level);
        if (city == null || city.isEmpty())
        {
            LOGGER.warn("NeoSim-ConstructionTask: skip credit deduction — placer has no city");
            return;
        }
        com.wenzai.neosim.storage.SimData.CityData data = com.wenzai.neosim.storage.SimData.CityData.read(level, city);
        double now = data.credit() - amount;
        if (now < 0) now = 0;
        com.wenzai.neosim.storage.SimData.CityData.write(level, city, data.withCredit(now));
        com.wenzai.neosim.storage.ModSavedData.get(level).syncCityToClients(level, city);
        LOGGER.info("NeoSim-ConstructionTask: deducted {} credits from city '{}'",
                Math.round(amount * 100.0) / 100.0, city);
    }

    // 建筑所属城市=放置者所属城市（放置者未入城时返回空）
    public static String cityOf(BuildingInstance building, ServerLevel level)
    {
        String placer = building.getPlacerName();
        if (placer == null || placer.isEmpty()) return "";
        if (level.getServer() == null) return "";
        boolean dedicated = level.getServer().isDedicatedServer();
        return dedicated
                ? FileCreater.findPlayerCity(placer)
                : FileCreater.findPlayerCity(level.getServer().getWorldData().getLevelName(), placer);
    }

    // 清空NPC手持
    private void clearBuilderHand()
    {
        resolveBuilderNpc();
        if (builderNpc != null)
        {
            builderNpc.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    net.minecraft.world.item.ItemStack.EMPTY);
        }
    }

    // 是否已雇佣工人
    private boolean hasWorker()
    {
        BlockPos box = building.getConstructorPos();
        if (box == null) return false;
        String name = NeoSim.WORKER_MAP.get(box);
        return name != null && !name.isEmpty();
    }

    // 工人是否已到岗
    private boolean isWorkerOnShift()
    {
        return currentState == BuildingInstance.BuildState.LOADING_BLUEPRINT
                || currentState == BuildingInstance.BuildState.BUILDING
                || currentState == BuildingInstance.BuildState.WAITING_FOR_RESOURCES;
    }

    // 是否夜晚
    private boolean isNightTime()
    {
        return level.getDayTime() % 24000 >= 12000;
    }

    // 下班：回生活点
    private void goOffWork()
    {
        resolveBuilderNpc();
        if (builderNpc != null)
        {
            BlockPos home = homePosition();
            if (home != null) builderNpc.setMoveTarget(home);
        }
    }

    // 夜晚入职的工人：当晚不前往工地
    private void restNewWorker()
    {
        String workerName = NeoSim.WORKER_MAP.get(building.getConstructorPos());
        if (workerName == null || workerName.isEmpty()) return;
        for (net.minecraft.world.entity.Entity e : level.getAllEntities())
        {
            if (e instanceof Entity npc && workerName.equals(npc.getNpcName()))
            {
                BlockPos home = npc.getHomePos();
                if (home != null)
                {
                    npc.setMoveTarget(home);
                }
                else
                {
                    npc.clearMoveTarget();
                }
                return;
            }
        }
    }

    // 白天
    private void ensureWorkerAtSite()
    {
        resolveBuilderNpc();
        if (builderNpc != null)
        {
            // 产假：孕期NPC白天不返工
            if (builderNpc.getPregnancyStage() > 0.0F) return;
            BlockPos box = building.getConstructorPos();
            if (box == null) box = building.getControlBoxPos();
            if (box != null) builderNpc.setMoveTarget(box);
        }
    }

    // 建筑生活点
    private BlockPos homePosition()
    {
        // 生活点系统：工人已有生活点则回生活点
        if (builderNpc != null && builderNpc.getHomePos() != null)
        {
            return builderNpc.getHomePos();
        }

        // 建筑生活点
        List<BlockPos> living = livingPointsOf(building);
        if (!living.isEmpty())
        {
            return living.get(0);
        }

        // 兜底：控制箱
        return building.getControlBoxPos();
    }

    // 驱动雇佣的建造者NPC的抬手
    private void setBuilderAnim(float value)
    {
        resolveBuilderNpc();
        if (builderNpc != null)
        {
            builderNpc.setBuildAnim(value);
        }
    }

    // 按模盒坐标找雇佣的NPC实体
    private void resolveBuilderNpc()
    {
        if (builderNpc != null && builderNpc.isAlive()) return;
        builderNpc = null;
        BlockPos box = building.getConstructorPos();
        if (box == null) return;
        String workerName = NeoSim.WORKER_MAP.get(box);
        if (workerName == null || workerName.isEmpty()) return;

        for (Entity npc : level.getEntitiesOfClass(Entity.class,
                new AABB(box).inflate(64.0D)))
        {
            if (workerName.equals(npc.getNpcName()))
            {
                builderNpc = npc;

                // 从NPC读取建造者等级，建造速度随等级
                float lvl = Math.max(1.0F, (float) npc.getJobArchitect());
                if (lvl != builderLevel)
                {
                    updateBuildSpeed(lvl);
                }
                return;
            }
        }
    }

    public void updateBuildSpeed(float levelBuilder)
    {
        this.builderLevel = levelBuilder;
        this.buildDelay = Math.max(1, (int) (BASE_DELAY / levelBuilder));
    }
    private boolean isCreativeMode()
    {
        return com.wenzai.neosim.storage.ModSavedData.get(level).getMode() == 2;
    }

    public void pause()
    {
        this.paused = true;

        // 同步到实例，随文件持久化
        building.setPaused(true);
        setBuilderAnim(0.0F);
        clearBuilderHand();
        building.setState(currentState);
        LOGGER.info("NeoSim-ConstructionTask: paused at {}/{}", resumeIndex, getTotal());
    }

    public void resume()
    {
        this.paused = false;
        building.setPaused(false);
        if (currentState == BuildingInstance.BuildState.WAITING_FOR_RESOURCES)
        {
            currentState = BuildingInstance.BuildState.BUILDING;
        }
        animStartTime = System.currentTimeMillis();
        building.setState(currentState);
        LOGGER.info("NeoSim-ConstructionTask: resumed from {}/{}", resumeIndex, getTotal());
    }

    public BuildingInstance.BuildState getState() { return currentState; }
    public void setState(BuildingInstance.BuildState state) { this.currentState = state; building.setState(state); }

    // GUI进度：两轮显示
    public int getProgress()
    {
        return phaseTwo ? schematic.getTotalVolume() + resumeIndex : resumeIndex;
    }
    public int getTotal() { return schematic.getTotalVolume() * 2; }
    public BuildingInstance getBuilding() { return building; }
    public boolean isPaused() { return paused; }
    public float getBuilderLevel() { return builderLevel; }

    // GUI状态页读取：当前阻塞建造的缺料
    public Item getLastMissingMaterial() { return lastMissingMaterial; }
}

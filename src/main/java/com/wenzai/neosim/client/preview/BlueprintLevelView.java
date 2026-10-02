package com.wenzai.neosim.client.preview;

import com.wenzai.neosim.schematic.LightweightBlockContainer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.TickPriority;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import javax.annotation.Nullable;

// 蓝图方块视图：把 LightweightBlockContainer 当成只读的 LevelAccessor
// 原版连接性方块的连接臂是 updateShape 按相邻方块算出来的（栅栏/墙/玻璃板/铁栏杆及其模组子类），
// 蓝图里只有方块状态、没有"世界"，所以预览要用这份视图把蓝图自己当世界，才能借用原版算法
public final class BlueprintLevelView implements LevelAccessor
{
	private final LightweightBlockContainer container;
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final RandomSource random = RandomSource.create();

	// 帧原点与帧外取方块器：世界幽灵预览用它把蓝图边界接到既有方块上（GUI 缩略图两个都传 null = 帧外都是空气）
	@Nullable
	private final BlockPos frameOrigin;
	@Nullable
	private final BlockAndTintGetter outsideWorld;

	// 预览自己造的方块实体（帧内坐标 → BE）：BE 渲染与 ModelData 都从这里取
	@Nullable
	private Map<BlockPos, BlockEntity> blockEntities;

	// 渲染视图专用：帧外一律当空气。面剔除（Block.shouldRenderFace）与流体只看蓝图自己的邻居。
	// 不能拿真实世界参与剔除——落点周围本来就有地形，尤其低于地表的那几层，
	// 真实方块的邻居会把幽灵的面整片剔掉，看起来就是"低于地面/低于玩家的方块透明了"。
	// 连通性判定（PreviewFrame 自己那份视图）与染色/光照不走这个开关。
	private boolean outsideIsAir;

	public BlueprintLevelView(LightweightBlockContainer container)
	{
		this(container, null, null);
	}

	public BlueprintLevelView(LightweightBlockContainer container, @Nullable BlockPos frameOrigin,
							  @Nullable BlockAndTintGetter outsideWorld)
	{
		this.container = container;
		this.sizeX = container.getSizeX();
		this.sizeY = container.getSizeY();
		this.sizeZ = container.getSizeZ();
		this.frameOrigin = frameOrigin;
		this.outsideWorld = outsideWorld;
	}

	public void setOutsideIsAir(boolean outsideIsAir)
	{
		this.outsideIsAir = outsideIsAir;
	}

	// ---- 方块与流体：唯一的真实数据源，蓝图内是空气的格子与帧外都交给真实世界（没有真实世界就是空气） ----

	@Override
	public BlockState getBlockState(BlockPos pos)
	{
		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();
		BlockState inside = Blocks.AIR.defaultBlockState();
		if (x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ)
		{
			inside = container.get(x, y, z);
		}
		if (!inside.isAir() || outsideWorld == null || frameOrigin == null || outsideIsAir)
		{
			return inside;
		}

		// 实际建造跳过蓝图空气格、更不清场，那一格留着的既有方块照样会被旁边的栅栏连上
		return outsideWorld.getBlockState(frameOrigin.offset(x, y, z));
	}

	@Override
	public FluidState getFluidState(BlockPos pos)
	{
		return getBlockState(pos).getFluidState();
	}

	// 预览自己造的方块实体：位置就是帧内坐标，BER 读到的 getBlockPos/getBlockState 与蓝图一致
	public void setBlockEntities(@Nullable Map<BlockPos, BlockEntity> blockEntities)
	{
		this.blockEntities = blockEntities;
	}

	@Nullable
	@Override
	public BlockEntity getBlockEntity(BlockPos pos)
	{
		return blockEntities == null ? null : blockEntities.get(pos);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> blockEntityType)
	{
		BlockEntity be = getBlockEntity(pos);
		if (be != null && be.getType() == blockEntityType)
		{
			return Optional.of((T) be);
		}
		return Optional.empty();
	}

	// ModelData：NeoForge 的 BlockGetter 扩展默认给 EMPTY，这里把方块实体自己那份带上
	@Override
	public ModelData getModelData(BlockPos pos)
	{
		BlockEntity be = getBlockEntity(pos);
		return be == null ? ModelData.EMPTY : be.getModelData();
	}

	// 高度只描述蓝图容器自身，isOutsideBuildHeight 才不会把蓝图内的格子判到世界之外
	@Override
	public int getMinBuildHeight()
	{
		return 0;
	}

	@Override
	public int getHeight()
	{
		return sizeY;
	}

	// ---- 只读查询：其余能力（光照/区块/实体/事件）对连接性判定没有意义，统一给安全空值 ----

	@Override
	public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate)
	{
		return predicate.test(getBlockState(pos));
	}

	@Override
	public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate)
	{
		return predicate.test(getFluidState(pos));
	}

	@Override
	public BlockPos getHeightmapPos(Heightmap.Types heightmapType, BlockPos pos)
	{
		return pos;
	}

	@Nullable
	@Override
	public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus chunkStatus, boolean requireChunk)
	{
		return null;
	}

	@Override
	public boolean hasChunk(int chunkX, int chunkZ)
	{
		return false;
	}

	@Override
	public int getHeight(Heightmap.Types heightmapType, int x, int z)
	{
		return 0;
	}

	@Override
	public int getSkyDarken()
	{
		return 0;
	}

	@Nullable
	@Override
	public BiomeManager getBiomeManager()
	{
		return null;
	}

	@Nullable
	@Override
	public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z)
	{
		return null;
	}

	@Override
	public boolean isClientSide()
	{
		return true;
	}

	@Override
	public int getSeaLevel()
	{
		return 0;
	}

	@Nullable
	@Override
	public DimensionType dimensionType()
	{
		return null;
	}

	@Override
	public RegistryAccess registryAccess()
	{
		return RegistryAccess.EMPTY;
	}

	@Override
	public FeatureFlagSet enabledFeatures()
	{
		return FeatureFlags.DEFAULT_FLAGS;
	}

	@Override
	public float getShade(Direction direction, boolean shade)
	{
		return 1.0F;
	}

	@Nullable
	@Override
	public LevelLightEngine getLightEngine()
	{
		return null;
	}

	// 光照：BlockAndTintGetter 的默认实现会走 getLightEngine()（预览没有光照引擎，返回 null → NPE）。
	// 预览不需要真实光照：AO 与顶点光照贴图统一按满亮算，否则 renderBatched 第一步就会炸。
	@Override
	public int getBrightness(LightLayer lightLayer, BlockPos pos)
	{
		return 15;
	}

	@Override
	public int getRawBrightness(BlockPos pos, int amount)
	{
		return 15;
	}

	// 生物群系染色：把帧内坐标映射回真实世界坐标，问有色方块要颜色（草/树叶跟着建设地点的群系走）。
	// 没有真实世界（GUI 缩略图）或取不到时返回 -1，交给 BlockColors 的缺省处理。
	@Override
	public int getBlockTint(BlockPos pos, ColorResolver colorResolver)
	{
		if (outsideWorld != null && frameOrigin != null)
		{
			try
			{
				return outsideWorld.getBlockTint(frameOrigin.offset(pos), colorResolver);
			}
			catch (Throwable ignored)
			{
				// 区块未加载等异常：染色失败不该拖垮整个预览重建
			}
		}

		// 没有真实世界（GUI 缩略图）：给原版默认色。
		// 不能直接 -1 —— 方块模型（ModelBlockRenderer）会拿这个 level 去问群系色，
		// 返回 -1 会让草/树叶在缩略图里变成纯白
		if (colorResolver == BiomeColors.GRASS_COLOR_RESOLVER) return GrassColor.getDefaultColor();
		if (colorResolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) return FoliageColor.getDefaultColor();

		// 原版默认水体色 0x3F76E4（液体渲染器问的是 WATER_COLOR_RESOLVER）
		if (colorResolver == BiomeColors.WATER_COLOR_RESOLVER) return 0x3F76E4;
		return -1;
	}

	@Nullable
	@Override
	public WorldBorder getWorldBorder()
	{
		return null;
	}

	@Nullable
	@Override
	public BlockGetter getChunkForCollisions(int chunkX, int chunkZ)
	{
		return null;
	}

	@Override
	public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB collisionBox)
	{
		return List.of();
	}

	@Override
	public List<Entity> getEntities(@Nullable Entity entity, AABB area, Predicate<? super Entity> predicate)
	{
		return List.of();
	}

	@Override
	public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> entityTypeTest, AABB area,
												  Predicate<? super T> predicate)
	{
		return List.of();
	}

	@Override
	public List<? extends Player> players()
	{
		return List.of();
	}

	@Override
	public long dayTime()
	{
		return 0L;
	}

	@Override
	public long nextSubTickCount()
	{
		return 0L;
	}

	@Nullable
	@Override
	public LevelTickAccess<Block> getBlockTicks()
	{
		return null;
	}

	@Nullable
	@Override
	public LevelTickAccess<Fluid> getFluidTicks()
	{
		return null;
	}

	@Nullable
	@Override
	public LevelData getLevelData()
	{
		return null;
	}

	@Override
	public DifficultyInstance getCurrentDifficultyAt(BlockPos pos)
	{
		return new DifficultyInstance(Difficulty.PEACEFUL, 0L, 0L, 0.0F);
	}

	@Nullable
	@Override
	public MinecraftServer getServer()
	{
		return null;
	}

	@Nullable
	@Override
	public ChunkSource getChunkSource()
	{
		return null;
	}

	@Override
	public RandomSource getRandom()
	{
		return random;
	}

	// ---- 写操作与事件：预览视图绝不能把任何东西写回真实世界或触发方块更新 ----

	@Override
	public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft)
	{
		return false;
	}

	@Override
	public boolean removeBlock(BlockPos pos, boolean isMoving)
	{
		return false;
	}

	@Override
	public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft)
	{
		return false;
	}

	@Override
	public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority)
	{
	}

	@Override
	public void scheduleTick(BlockPos pos, Block block, int delay)
	{
	}

	@Override
	public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority)
	{
	}

	@Override
	public void scheduleTick(BlockPos pos, Fluid fluid, int delay)
	{
	}

	@Override
	public void neighborShapeChanged(Direction direction, BlockState queried, BlockPos pos, BlockPos offsetPos,
									 int flags, int recursionLevel)
	{
	}

	@Override
	public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source,
						  float volume, float pitch)
	{
	}

	@Override
	public void addParticle(ParticleOptions particleData, double x, double y, double z,
							double xSpeed, double ySpeed, double zSpeed)
	{
	}

	@Override
	public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data)
	{
	}

	@Override
	public void gameEvent(Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context)
	{
	}
}

package com.wenzai.neosim.client.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

// 预览专用「假世界」：给预览自造的方块实体当 Level 用。
//
// 用途：Create 6 这类模组的方块实体渲染器开头就是
//     if (VisualizationManager.supportsVisualization(be.getLevel())) return;
// Flywheel 1.0 的判定是「后端开启 且 (level 是 VisualizationLevel 且它自己支持可视化 或 level == Minecraft.level)」。
// 把真实客户端世界当 Level 传进去正好命中最后一条，渲染器直接 return，
// 齿轮 / 传动轴这些由渲染器画的部分整块不画（外壳是静态模型所以还在）。
//
// 这个类是 Level 的普通子类：既不实现 Flywheel 的 VisualizationLevel，也不是 Minecraft.level，
// 于是 supportsVisualization 返回 false，模组渲染器会走「降级绘制」那条路，把几何真正写进缓冲。
// 只读、不注册任何东西、不写回世界；方块查询交给预览自己的 BlueprintLevelView，避免读到真实地形。
public class PreviewLevel extends Level
{
	// 帧内方块视图（蓝图 + 帧外规则）：BE 渲染器查方块/流体时拿到的是预览自己那套
	private final BlockAndTintGetter view;

	// 真实世界：凡是"返回对象"的查询（tickRateManager / chunkSource / biomeManager / 计分板…）一律转发给它。
	// 不能返回 null —— 模组渲染器会直接拿返回值调方法（例如 tickRateManager().isFrozen()），
	// 拿不到就 NPE 砸穿整个预览渲染。这些查询都是只读的，转发不会把预览写进真实世界
	private final Level real;

	// 预览沿用真实世界的时间：Create 的动画相位按 getGameTime() 算，给 0 会让某些姿态退化成看不见
	private final long gameTime;
	private final long dayTime;

	private final RegistryAccess registryAccess;

	private final WritableLevelData levelData;

	private float dayTimeFraction;
	private float dayTimePerTick = 1.0F;

	public PreviewLevel(Level real, BlockAndTintGetter view)
	{
		this(real, view, new PreviewLevelData(real.getLevelData()));
	}

	private PreviewLevel(Level real, BlockAndTintGetter view, PreviewLevelData levelData)
	{
		super(levelData,
				real.dimension(),
				real.registryAccess(),
				real.dimensionTypeRegistration(),
				real.getProfilerSupplier(),
				true,
				false,
				0L,
				0);

		this.real = real;
		this.view = view;
		this.gameTime = real.getGameTime();
		this.dayTime = real.getDayTime();
		this.registryAccess = real.registryAccess();
		this.levelData = levelData;
	}

	// ---- 方块与流体：全部走预览视图（帧内 = 蓝图，帧外按视图自己的规则） ----

	@Override
	public BlockState getBlockState(BlockPos pos)
	{
		return view.getBlockState(pos);
	}

	@Override
	public FluidState getFluidState(BlockPos pos)
	{
		return view.getFluidState(pos);
	}

	// 方块实体一律为 null：预览的 BE 由 GhostMeshCache / BuildingPreviewRenderer 自己保管，
	// 渲染器通常直接用传进去的实例；这里返回 null 不会让它们查到真实世界的机器
	@Nullable
	@Override
	public BlockEntity getBlockEntity(BlockPos pos)
	{
		return null;
	}

	@Override
	public int getBlockTint(BlockPos pos, ColorResolver colorResolver)
	{
		return view.getBlockTint(pos, colorResolver);
	}

	// ---- 光照：预览统一满亮（与 BlueprintLevelView 同一口径），否则 renderBatched 第一步就炸 ----

	@Override
	public float getShade(net.minecraft.core.Direction direction, boolean shade)
	{
		return 1.0F;
	}

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

	@Nullable
	@Override
	public LevelLightEngine getLightEngine()
	{
		return null;
	}

	// ---- 读数与只读查询：预览没有区块/实体/光照引擎，给安全空值 ----

	@Override
	public long getGameTime()
	{
		return gameTime;
	}

	@Override
	public long getDayTime()
	{
		return dayTime;
	}

	@Override
	public RegistryAccess registryAccess()
	{
		return registryAccess;
	}

	@Override
	public LevelData getLevelData()
	{
		return levelData;
	}

	@Override
	public RandomSource getRandom()
	{
		return RandomSource.create();
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

	// 不能给 null：调用方会直接拿返回值调方法（Level.tickRateManager().isFrozen()），
	// 真实世界的这个对象是只读查询语义，转发即可
	@Override
	public TickRateManager tickRateManager()
	{
		return real.tickRateManager();
	}

	@Override
	public net.minecraft.world.scores.Scoreboard getScoreboard()
	{
		return real.getScoreboard();
	}

	@Nullable
	@Override
	public net.minecraft.world.item.crafting.RecipeManager getRecipeManager()
	{
		return real.getRecipeManager();
	}

	@Nullable
	@Override
	public net.minecraft.world.item.alchemy.PotionBrewing potionBrewing()
	{
		return real.potionBrewing();
	}

	@Override
	public int getHeight(Heightmap.Types heightmapType, int x, int z)
	{
		return 0;
	}

	@Override
	public int getSkyDarken()
	{
		return real.getSkyDarken();
	}

	@Nullable
	@Override
	public BiomeManager getBiomeManager()
	{
		return null;
	}

	@Nullable
	@Override
	public Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z)
	{
		return null;
	}

	@Override
	public int getSeaLevel()
	{
		return real.getSeaLevel();
	}

	@Nullable
	@Override
	public DimensionType dimensionType()
	{
		return null;
	}

	@Override
	public FeatureFlagSet enabledFeatures()
	{
		return FeatureFlags.DEFAULT_FLAGS;
	}

	// 世界边界 / 海平面 / 天空变暗 / 玩家列表：都是纯读数，转发真实世界，
	// 免得某个模组渲染器拿返回值直接调方法又变成一次 NPE 崩溃
	@Override
	public WorldBorder getWorldBorder()
	{
		return real.getWorldBorder();
	}

	@Nullable
	@Override
	public ChunkSource getChunkSource()
	{
		return null;
	}

	@Override
	public String gatherChunkSourceStats()
	{
		return "neo_sim_preview";
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
	public MinecraftServer getServer()
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
	protected LevelEntityGetter<Entity> getEntities()
	{
		return null;
	}

	@Nullable
	@Override
	public Entity getEntity(int id)
	{
		return null;
	}

	@Override
	public List<? extends Player> players()
	{
		return real.players();
	}

	@Nullable
	@Override
	public MapItemSavedData getMapData(MapId mapId)
	{
		return null;
	}

	@Override
	public void setMapData(MapId mapId, MapItemSavedData mapItemSavedData)
	{
	}

	@Nullable
	@Override
	public MapId getFreeMapId()
	{
		return null;
	}

	@Override
	public void destroyBlockProgress(int id, BlockPos pos, int progress)
	{
	}

	// ---- 写操作与事件：预览绝不写回真实世界、绝不触发任何副作用 ----

	@Override
	public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags)
	{
	}

	@Override
	public void playSeededSound(@Nullable Player player, double x, double y, double z,
								Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed)
	{
	}

	@Override
	public void playSeededSound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound,
								SoundSource source, float volume, float pitch, long seed)
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

	@Override
	public void setDayTimeFraction(float value)
	{
		this.dayTimeFraction = value;
	}

	@Override
	public float getDayTimeFraction()
	{
		return dayTimeFraction;
	}

	@Override
	public float getDayTimePerTick()
	{
		return dayTimePerTick;
	}

	@Override
	public void setDayTimePerTick(float value)
	{
		this.dayTimePerTick = value;
	}

	// 预览用的关卡数据：真实世界的种子 / 时间 / 天气原样保留，写操作只落在本对象上
	private static final class PreviewLevelData implements WritableLevelData
	{
		private final LevelData source;
		private final long gameTime;
		private final long dayTime;

		private PreviewLevelData(LevelData source)
		{
			this.source = source;
			this.gameTime = source.getGameTime();
			this.dayTime = source.getDayTime();
		}

		@Override
		public BlockPos getSpawnPos()
		{
			return source.getSpawnPos();
		}

		@Override
		public float getSpawnAngle()
		{
			return source.getSpawnAngle();
		}

		@Override
		public long getGameTime()
		{
			return gameTime;
		}

		@Override
		public long getDayTime()
		{
			return dayTime;
		}

		@Override
		public boolean isThundering()
		{
			return source.isThundering();
		}

		@Override
		public boolean isRaining()
		{
			return source.isRaining();
		}

		@Override
		public void setRaining(boolean raining)
		{
		}

		@Override
		public boolean isHardcore()
		{
			return source.isHardcore();
		}

		@Override
		public net.minecraft.world.level.GameRules getGameRules()
		{
			return source.getGameRules();
		}

		@Override
		public Difficulty getDifficulty()
		{
			return source.getDifficulty();
		}

		@Override
		public boolean isDifficultyLocked()
		{
			return source.isDifficultyLocked();
		}

		@Override
		public void setSpawn(BlockPos pos, float angle)
		{
		}
	}
}

package com.wenzai.neosim.building;

import com.wenzai.neosim.schematic.CoordTransform;
import com.wenzai.neosim.schematic.SchematicData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BuildingInstance
{
	// 蓝图引用
	private String schematicName;
	private SchematicData schematic;

	// 世界定位
	private BlockPos controlBoxPos;
	private Rotation rotation = Rotation.NONE;
	private Mirror mirror = Mirror.NONE;

	// 玩家面朝方向
	private Direction facing;

	// 建造进度
	private int buildProgress;
	private boolean buildingComplete;
	private BuildState state = BuildState.IDLE;

	private boolean phaseTwo;

	// 暂停状态持久化
	private boolean paused;

	// NPC
	private UUID assignedBuilder;
	private String builderName;

	// 雇佣的工人名
	private String workerName;

	// 作者持久化
	private String author;

	// 放置者
	private String placerName;

	// 模盒位置
	private BlockPos constructorPos;

	// 强制加载的区块（不持久化，重启后由恢复流程重新注册）
	private final List<ChunkPos> loadedChunks = new ArrayList<>();

	// C9：所属城市缓存（放置者城市不变；首次查询后不再目录遍历）
	private String cachedCity;
	private boolean cityComputed;

	// 所属城市（懒计算并缓存；无城市返回 ""）
	public String getCachedCity(net.minecraft.server.level.ServerLevel level)
	{
		if (!cityComputed)
		{
			cachedCity = ConstructionTask.cityOf(this, level);
			cityComputed = true;
		}
		return cachedCity != null ? cachedCity : "";
	}

	// 蓝图局部坐标同步到世界坐标
	public BlockPos blueprintToWorld(int bx, int by, int bz)
	{
		BlockPos base = facing != null
				? CoordTransform.simukraftPos(bx, by, bz, facing)
				: new BlockPos(bx, by, bz);
		BlockPos transformed = CoordTransform.transformPos(base, mirror, rotation);
		return controlBoxPos.offset(transformed);
	}

	// 访问
	public String getSchematicName()
	{
		return schematicName;
	}

	public void setSchematicName(String v)
	{
		this.schematicName = v;
	}

	public SchematicData getSchematic()
	{
		return schematic;
	}

	public void setSchematic(SchematicData v)
	{
		this.schematic = v;
	}

	public BlockPos getControlBoxPos()
	{
		return controlBoxPos;
	}

	public void setControlBoxPos(BlockPos v)
	{
		this.controlBoxPos = v;
	}

	public Rotation getRotation()
	{
		return rotation;
	}

	public void setRotation(Rotation v)
	{
		this.rotation = v;
	}

	public Mirror getMirror()
	{
		return mirror;
	}

	public void setMirror(Mirror v)
	{
		this.mirror = v;
	}

	public Direction getFacing()
	{
		return facing;
	}

	public void setFacing(Direction v)
	{
		this.facing = v;
	}

	public int getBuildProgress()
	{
		return buildProgress;
	}

	public void setBuildProgress(int v)
	{
		this.buildProgress = v;
	}

	public boolean isPhaseTwo()
	{
		return phaseTwo;
	}

	public void setPhaseTwo(boolean v)
	{
		this.phaseTwo = v;
	}

	public boolean isPaused()
	{
		return paused;
	}

	public void setPaused(boolean v)
	{
		this.paused = v;
	}

	public boolean isBuildingComplete()
	{
		return buildingComplete;
	}

	public void setBuildingComplete(boolean v)
	{
		this.buildingComplete = v;
	}

	public BuildState getState()
	{
		return state;
	}

	public void setState(BuildState v)
	{
		this.state = v;
	}

	public UUID getAssignedBuilder()
	{
		return assignedBuilder;
	}

	public void setAssignedBuilder(UUID v)
	{
		this.assignedBuilder = v;
	}

	public String getBuilderName()
	{
		return builderName;
	}

	public void setBuilderName(String v)
	{
		this.builderName = v;
	}

	public String getWorkerName()
	{
		return workerName;
	}

	public void setWorkerName(String v)
	{
		this.workerName = v;
	}

	public String getAuthor()
	{
		return author;
	}

	public void setAuthor(String v)
	{
		this.author = v;
	}

	public String getPlacerName()
	{
		return placerName;
	}

	public void setPlacerName(String v)
	{
		this.placerName = v;
	}

	public BlockPos getConstructorPos()
	{
		return constructorPos;
	}

	public void setConstructorPos(BlockPos v)
	{
		this.constructorPos = v;
	}

	public List<ChunkPos> getLoadedChunks()
	{
		return loadedChunks;
	}

	public void addLoadedChunk(ChunkPos cp)
	{
		if (!loadedChunks.contains(cp)) loadedChunks.add(cp);
	}

	public void clearLoadedChunks()
	{
		loadedChunks.clear();
	}

	public boolean containsLoadedChunk(long l)
	{
		for (ChunkPos cp : loadedChunks)
		{
			if (cp.toLong() == l) return true;
		}
		return false;
	}

	public int getTotalBlocks()
	{
		return schematic != null ? schematic.getTotalVolume() : 0;
	}

	public enum BuildState
	{
		IDLE,
		WAITING_FOR_WORKER,
		WORKER_ASSIGNED,
		LOADING_BLUEPRINT,
		WAITING_FOR_RESOURCES,
		BUILDING,
		COMPLETE
	}
}

package com.wenzai.neosim.block;

import com.wenzai.neosim.network.ServerToClientPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

import javax.annotation.Nullable;

// 标记棒全局管理器（Task 4.2：按维度存储，切维度不丢内存态）
public class MarkerManager
{
	// 标记间最大连接距离
	public static final int MAX_SPAN = 64;

	// 维度 → 该维度已加载的标记棒位置（切维度不再清空，各维度独立）
	private static final Map<ResourceKey<Level>, List<BlockPos>> markersByDim = new HashMap<>();
	private static ResourceKey<Level> currentDim = null;

	// 定时对账计数器（每 40 tick 对账一次）
	private static int reconcileCounter = 0;

	// L11：活动矩形结果缓存 + 脏标记（标记增删/对账变化时失效，避免 getActiveRects 每调用 O(n⁴) 重算）
	private static List<MarkerRect> cachedRects;
	private static boolean rectsDirty = true;

	private MarkerManager()
	{
	}

	// L11：标记集合变化 → 矩形缓存失效
	private static void invalidateRects()
	{
		cachedRects = null;
		rectsDirty = true;
	}

	// 当前维度标记列表（不存在则建）
	private static List<BlockPos> currentMarkers()
	{
		if (currentDim == null) return new ArrayList<>();
		return markersByDim.computeIfAbsent(currentDim, k -> new ArrayList<>());
	}

	// 玩家放置
	public static void onPlaced(Level level, BlockPos pos)
	{
		if (level.isClientSide) return;
		ResourceKey<Level> dim = level.dimension();
		currentDim = dim;
		List<BlockPos> list = currentMarkers();
		list.add(pos.immutable());
		invalidateRects();
		pruneStale(level, list);
		if (level instanceof ServerLevel serverLevel)
		{
			MarkerPersistence.save(MarkerPersistence.saveNameOf(serverLevel),
					dim.location().toString(), list);
		}
		broadcast(level);
	}

	// 玩家破坏：只移除被拆的
	public static void onRemoved(Level level, BlockPos pos)
	{
		if (level.isClientSide) return;
		if (currentDim == null) return;
		List<BlockPos> list = markersByDim.get(currentDim);
		if (list == null) return;
		if (list.removeIf(p -> p.equals(pos)))
		{
			invalidateRects();
			pruneStale(level, list);
			if (level instanceof ServerLevel serverLevel)
			{
				MarkerPersistence.save(MarkerPersistence.saveNameOf(serverLevel),
						currentDim.location().toString(), list);
			}
			broadcast(level);
		}
	}

	// 玩家加入：同步当前维度活动矩形
	public static void syncTo(ServerPlayer player)
	{
		if (currentDim == null || !currentDim.equals(player.level().dimension())) return;
		List<List<BlockPos>> rects = computeActiveRects(markersByDim.getOrDefault(currentDim, List.of()));
		if (rects.isEmpty()) return;
		PacketDistributor.sendToPlayer(player, buildPayload(rects));
	}

	// 服务端停止时清空，防止下个存档读到残留
	public static void clear()
	{
		markersByDim.clear();
		currentDim = null;
		reconcileCounter = 0;
		invalidateRects();
	}

	// 服务端世界加载：从存档恢复本维度标记位置，重进游戏后光幕继续显示（不清其他维度）
	public static void loadFrom(ServerLevel level)
	{
		List<BlockPos> saved = MarkerPersistence.load(MarkerPersistence.saveNameOf(level),
				level.dimension().location().toString());
		if (saved.isEmpty()) return;
		markersByDim.put(level.dimension(), new ArrayList<>(saved));
		currentDim = level.dimension();
		invalidateRects();
		pruneStale(level, markersByDim.get(level.dimension()));
	}

	// 服务端定时对账：兜住爆炸/岩浆/活塞/其他玩家等非主动拆除，光幕不残留
	public static void tick(ServerLevel level)
	{
		if (currentDim == null || !currentDim.equals(level.dimension())) return;
		List<BlockPos> list = markersByDim.get(currentDim);
		if (list == null || list.isEmpty()) return;
		if (++reconcileCounter % 40 != 0) return;
		if (pruneStale(level, list))
		{
			invalidateRects();
			MarkerPersistence.save(MarkerPersistence.saveNameOf(level),
					currentDim.location().toString(), list);
			broadcast(level);
		}
	}

	// 已加载区块里不再是标记棒的旧标记剔除（防爆炸/其他玩家破坏残留）；返回是否有变化
	private static boolean pruneStale(Level level, List<BlockPos> list)
	{
		boolean changed = false;
		for (java.util.Iterator<BlockPos> it = list.iterator(); it.hasNext(); )
		{
			BlockPos p = it.next();
			if (level.isLoaded(p) && !(level.getBlockState(p).getBlock() instanceof Marker))
			{
				it.remove();
				changed = true;
			}
		}
		return changed;
	}

	private static void broadcast(Level level)
	{
		if (currentDim == null || !(level instanceof ServerLevel serverLevel)) return;
		List<List<BlockPos>> rects = computeActiveRects(currentMarkers());
		ServerToClientPayloads.MarkerSyncPayload payload = buildPayload(rects);
		// 只发给当前维度内的玩家（切维度不串台）
		for (ServerPlayer player : serverLevel.players())
		{
			if (player.level().dimension().equals(currentDim))
			{
				PacketDistributor.sendToPlayer(player, payload);
			}
		}
	}

	private static ServerToClientPayloads.MarkerSyncPayload buildPayload(List<List<BlockPos>> rects)
	{
		return new ServerToClientPayloads.MarkerSyncPayload(currentDim, rects);
	}

	// 活动矩形
	public record MarkerRect(int minX, int minY, int minZ, int maxX, int maxZ, List<BlockPos> corners) {}

	// 指定维度活动矩形列表（该维度无标记/未加载返回空；L11：结果缓存 + 脏标记懒重算）
	public static List<MarkerRect> getActiveRects(Level level)
	{
		List<BlockPos> list = markersByDim.get(level.dimension());
		if (list == null) return List.of();
		// 入口轻量对账：服务端先剔除已加载区块内不再是标记棒的旧标记（持久化由 tick() 周期负责）
		if (!level.isClientSide)
		{
			if (pruneStale(level, list))
			{
				invalidateRects();
			}
		}
		// L11：缓存命中直接返回（标记未变化时避免每调用 O(n⁴) 枚举）
		if (!rectsDirty && cachedRects != null)
		{
			return cachedRects;
		}
		rectsDirty = false;
		List<List<BlockPos>> rects = computeActiveRects(list);
		if (rects.isEmpty())
		{
			cachedRects = List.of();
			return cachedRects;
		}
		List<MarkerRect> out = new ArrayList<>();
		for (List<BlockPos> corners : rects)
		{
			if (corners.size() < 4) continue;
			int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
			int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
			for (BlockPos p : corners)
			{
				minX = Math.min(minX, p.getX());
				maxX = Math.max(maxX, p.getX());
				minZ = Math.min(minZ, p.getZ());
				maxZ = Math.max(maxZ, p.getZ());
			}
			out.add(new MarkerRect(minX, corners.get(0).getY(), minZ, maxX, maxZ, corners));
		}
		cachedRects = out;
		return cachedRects;
	}

	// 半径内取中心距盒子最近的矩形
	@Nullable
	public static MarkerRect findRectNear(Level level, BlockPos box, int radius)
	{
		List<MarkerRect> rects = getActiveRects(level);
		if (rects.isEmpty()) return null;
		MarkerRect best = null;
		double bestDist = Double.MAX_VALUE;
		for (MarkerRect r : rects)
		{
			double cx = (r.minX + r.maxX) / 2.0;
			double cz = (r.minZ + r.maxZ) / 2.0;
			double dx = Math.max(Math.max(r.minX - box.getX(), 0), box.getX() - r.maxX);
			double dz = Math.max(Math.max(r.minZ - box.getZ(), 0), box.getZ() - r.maxZ);
			if (radius > 0 && Math.sqrt(dx * dx + dz * dz) > radius) continue;
			double dist = box.distToCenterSqr(cx, r.minY + 0.5, cz);
			if (best == null || dist < bestDist || (dist == bestDist && lexLess(r, best)))
			{
				best = r;
				bestDist = dist;
			}
		}
		return best;
	}

	// 盒子紧邻标记棒，无相邻标记/标记不属于任何矩形则返回null
	@Nullable
	public static MarkerRect findRectAdjacentToMarker(Level level, BlockPos box)
	{
		List<MarkerRect> rects = getActiveRects(level);
		if (rects.isEmpty()) return null;
		BlockPos[] neighbors = {
				box.above(), box.below(),
				box.north(), box.south(), box.east(), box.west()
		};
		for (BlockPos n : neighbors)
		{
			if (!(level.getBlockState(n).getBlock() instanceof Marker)) continue;
			for (MarkerRect r : rects)
			{
				if (r.corners().contains(n)) return r;
			}
		}
		return null;
	}

	private static boolean lexLess(MarkerRect a, MarkerRect b)
	{
		if (a.minX != b.minX) return a.minX < b.minX;
		if (a.minZ != b.minZ) return a.minZ < b.minZ;
		if (a.maxX != b.maxX) return a.maxX < b.maxX;
		return a.maxZ < b.maxZ;
	}

	// 每个高度的活动矩形：面积大者优先，同一Marker只属1个且互不重叠
	private static List<List<BlockPos>> computeActiveRects(List<BlockPos> markers)
	{
		if (markers.size() < 4)
		{
			return List.of();
		}
		Map<Integer, List<BlockPos>> byY = new HashMap<>();
		for (BlockPos p : markers)
		{
			byY.computeIfAbsent(p.getY(), k -> new ArrayList<>()).add(p);
		}
		List<List<BlockPos>> rects = new ArrayList<>();
		for (List<BlockPos> group : byY.values())
		{
			rects.addAll(packRects(group));
		}
		return rects;
	}

	// 同一高度：面积大者优先，互不共享角点、互不重叠
	private static List<List<BlockPos>> packRects(List<BlockPos> group)
	{
		// 去重、固定角点顺序
		Set<Long> seen = new HashSet<>();
		List<Long> keys = new ArrayList<>();
		for (BlockPos p : group)
		{
			if (seen.add(key(p.getX(), p.getZ())))
			{
				keys.add(key(p.getX(), p.getZ()));
			}
		}
		Collections.sort(keys);
		if (keys.size() < 4) return List.of();

		List<Rect> cands = enumerateRects(group, keys);
		if (cands.isEmpty()) return List.of();

		// 面积大者优先；同面积按坐标固定顺序，保证结果确定
		cands.sort((a, b) ->
		{
			int c = Integer.compare(b.area, a.area);
			if (c != 0) return c;
			c = Integer.compare(a.x1, b.x1);
			if (c != 0) return c;
			c = Integer.compare(a.z1, b.z1);
			if (c != 0) return c;
			c = Integer.compare(a.x2, b.x2);
			if (c != 0) return c;
			return Integer.compare(a.z2, b.z2);
		});

		boolean[] used = new boolean[keys.size()];
		List<Rect> chosen = new ArrayList<>();
		for (Rect r : cands)
		{
			if (!r.free(used)) continue;
			boolean ok = true;
			for (Rect c : chosen)
			{
				if (r.overlaps(c))
				{
					ok = false;
					break;
				}
			}
			if (!ok) continue;
			r.mark(used);
			chosen.add(r);
		}
		List<List<BlockPos>> rects = new ArrayList<>();
		for (Rect r : chosen)
		{
			rects.add(r.corners);
		}
		return rects;
	}

	// 同一高度枚举所有四角均在的轴对齐矩形
	private static List<Rect> enumerateRects(List<BlockPos> group, List<Long> keys)
	{
		int y = group.get(0).getY();
		Map<Long, Integer> idx = new HashMap<>();
		for (int i = 0; i < keys.size(); i++)
		{
			idx.put(keys.get(i), i);
		}
		Set<Long> present = new HashSet<>();
		for (BlockPos p : group)
		{
			present.add(key(p.getX(), p.getZ()));
		}
		List<Integer> xs = new ArrayList<>();
		List<Integer> zs = new ArrayList<>();
		for (BlockPos p : group)
		{
			if (!xs.contains(p.getX())) xs.add(p.getX());
			if (!zs.contains(p.getZ())) zs.add(p.getZ());
		}
		Collections.sort(xs);
		Collections.sort(zs);

		List<Rect> rects = new ArrayList<>();
		for (int i = 0; i < xs.size(); i++)
		{
			for (int j = i + 1; j < xs.size(); j++)
			{
				int x1 = xs.get(i), x2 = xs.get(j);
				if (x2 - x1 > MAX_SPAN) continue;
				for (int k = 0; k < zs.size(); k++)
				{
					for (int l = k + 1; l < zs.size(); l++)
					{
						int z1 = zs.get(k), z2 = zs.get(l);
						if (z2 - z1 > MAX_SPAN) continue;
						long k1 = key(x1, z1), k2 = key(x1, z2), k3 = key(x2, z1), k4 = key(x2, z2);
						if (present.contains(k1) && present.contains(k2)
						 && present.contains(k3) && present.contains(k4))
						{
							rects.add(new Rect(x1, x2, z1, z2, y, idx.get(k1), idx.get(k2), idx.get(k3), idx.get(k4)));
						}
					}
				}
			}
		}
		return rects;
	}

	// 候选矩形（同一高度的四角）
	private static final class Rect
	{
		final int x1, x2, z1, z2;
		final int area;
		final int[] cornerIdx;
		final List<BlockPos> corners;

		Rect(int x1, int x2, int z1, int z2, int y, int c1, int c2, int c3, int c4)
		{
			this.x1 = x1; this.x2 = x2;
			this.z1 = z1; this.z2 = z2;
			this.area = (x2 - x1) * (z2 - z1);
			this.cornerIdx = new int[] { c1, c2, c3, c4 };
			this.corners = List.of(
					new BlockPos(x1, y, z1), new BlockPos(x2, y, z1),
					new BlockPos(x2, y, z2), new BlockPos(x1, y, z2));
		}

		// 是否重叠
		boolean overlaps(Rect other)
		{
			// 内部重叠
			if (this.x1 < other.x2 && other.x1 < this.x2
				&& this.z1 < other.z2 && other.z1 < this.z2) return true;

			// 外框重叠
			if ((this.x1 == other.x2 || this.x2 == other.x1)
				&& Math.max(this.z1, other.z1) < Math.min(this.z2, other.z2)) return true;

			if ((this.z1 == other.z2 || this.z2 == other.z1)
				&& Math.max(this.x1, other.x1) < Math.min(this.x2, other.x2)) return true;
			return false;
		}

		// 四角是否都未被占用
		boolean free(boolean[] used)
		{
			for (int ci : cornerIdx) if (used[ci]) return false;
			return true;
		}

		// 占用四角
		void mark(boolean[] used)
		{
			for (int ci : cornerIdx) used[ci] = true;
		}
	}

	// (x,z)打包成唯一long key
	private static long key(int x, int z)
	{
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}
}

package com.wenzai.neosim.block;

import com.wenzai.neosim.network.ServerToClientPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 标记棒全局管理器
public class MarkerManager
{
    // 标记间最大连接距离
    private static final int MAX_SPAN = 64;

    private static final List<BlockPos> markers = new ArrayList<>();
    private static ResourceKey<Level> currentDim = null;

    private MarkerManager() {}

    // 玩家放置
    public static void onPlaced(Level level, BlockPos pos)
    {
        if (level.isClientSide) return;
        ResourceKey<Level> dim = level.dimension();
        if (currentDim == null || !currentDim.equals(dim))
        {
            markers.clear();
            currentDim = dim;
        }
        markers.add(pos.immutable());
        pruneStale(level);
        if (level instanceof ServerLevel serverLevel)
        {
            MarkerSavedData.get(serverLevel).setMarkers(markers);
        }
        broadcast(level);
    }

    // 玩家破坏：只移除被拆的
    public static void onRemoved(Level level, BlockPos pos)
    {
        if (level.isClientSide) return;
        if (markers.removeIf(p -> p.equals(pos)))
        {
            pruneStale(level);
            if (level instanceof ServerLevel serverLevel)
            {
                MarkerSavedData.get(serverLevel).setMarkers(markers);
            }
            broadcast(level);
        }
    }

    // 玩家加入：同步活动矩形
    public static void syncTo(ServerPlayer player)
    {
        if (currentDim == null || !currentDim.equals(player.level().dimension())) return;
        List<List<BlockPos>> rects = computeActiveRects();
        if (rects.isEmpty()) return;
        PacketDistributor.sendToPlayer(player, buildPayload(rects));
    }

    // 服务端停止时清空，防止下个存档读到残留
    public static void clear()
    {
        markers.clear();
        currentDim = null;
    }

    // 服务端世界加载：从存档恢复标记位置，重进游戏后光幕继续显示
    public static void loadFrom(ServerLevel level)
    {
        MarkerSavedData data = MarkerSavedData.get(level);
        if (data.getMarkers().isEmpty()) return;
        markers.clear();
        markers.addAll(data.getMarkers());
        currentDim = level.dimension();
        pruneStale(level);
    }

    // 已加载区块里不再是标记棒的旧标记剔除（防爆炸/其他玩家破坏残留）
    private static void pruneStale(Level level)
    {
        markers.removeIf(p -> level.isLoaded(p) && !(level.getBlockState(p).getBlock() instanceof Marker));
    }

    private static void broadcast(Level level)
    {
        if (currentDim == null || !(level instanceof ServerLevel serverLevel)) return;
        List<List<BlockPos>> rects = computeActiveRects();
        ServerToClientPayloads.MarkerSyncPayload payload = buildPayload(rects);
        serverLevel.players().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    private static ServerToClientPayloads.MarkerSyncPayload buildPayload(List<List<BlockPos>> rects)
    {
        return new ServerToClientPayloads.MarkerSyncPayload(currentDim, rects);
    }

    // 每个高度的活动矩形：面积大者优先，同一Marker只属1个且互不重叠
    private static List<List<BlockPos>> computeActiveRects()
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

// 族谱

package com.wenzai.neosim.life;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Genealogy
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private Genealogy() {}

    // 一名NPC的族谱数据
    public record FamilyData(List<String> parents, List<String> children)
    {
        public static FamilyData empty()
        {
            return new FamilyData(List.of(), List.of());
        }
    }

    // 解析某名字的族谱：优先已加载实体NBT，未加载则读NpcData文件
    public static FamilyData getFamily(ServerLevel level, String city, String name)
    {
        if (name == null || name.isEmpty() || level.getServer() == null) return FamilyData.empty();

        for (net.minecraft.world.entity.Entity e : level.getAllEntities())
        {
            if (e instanceof Entity npc && name.equals(npc.getNpcName()))
            {
                return new FamilyData(npc.getParentNames(), npc.getChildren());
            }
        }

        JsonObject json = NpcData.load(level, city, name);
        if (json == null) return FamilyData.empty();

        List<String> parents = new ArrayList<>();
        List<String> children = new ArrayList<>();
        if (json.has("parents"))
        {
            JsonArray arr = json.getAsJsonArray("parents");
            for (int i = 0; i < arr.size(); i++)
            {
                parents.add(arr.get(i).getAsString());
            }
        }
        if (json.has("children"))
        {
            JsonArray arr = json.getAsJsonArray("children");
            for (int i = 0; i < arr.size(); i++)
            {
                children.add(arr.get(i).getAsString());
            }
        }
        return new FamilyData(parents, children);
    }

    // 向上回溯，返回祖先集合（不含自身）
    private static Set<String> collectAncestors(ServerLevel level, String city, String start, int depth)
    {
        Set<String> ancestors = new HashSet<>();
        Set<String> frontier = new HashSet<>();
        frontier.add(start);
        for (int d = 0; d < depth; d++)
        {
            Set<String> next = new HashSet<>();
            for (String name : frontier)
            {
                FamilyData fam = getFamily(level, city, name);
                for (String p : fam.parents())
                {
                    if (!p.isEmpty())
                    {
                        ancestors.add(p);
                        next.add(p);
                    }
                }
            }
            frontier = next;
            if (frontier.isEmpty()) break;
        }
        return ancestors;
    }

    // 血亲判定
    public static boolean isBloodRelated(ServerLevel level, String city, String a, String b)
    {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.equals(b)) return false;

        FamilyData fa = getFamily(level, city, a);
        if (fa.parents().contains(b)) return true; // b 是 a 的父母
        FamilyData fb = getFamily(level, city, b);
        if (fb.parents().contains(a)) return true; // a 是 b 的父母

        int depth = 3;
        try
        {
            depth = Config.LIFE_GENEALOGY_DEPTH.get();
        }
        catch (IllegalStateException ignored)
        {
            // 配置尚未加载，使用默认值
        }

        Set<String> ancestorsA = collectAncestors(level, city, a, depth);
        Set<String> ancestorsB = collectAncestors(level, city, b, depth);
        ancestorsA.retainAll(ancestorsB);
        return !ancestorsA.isEmpty();
    }

    // 出生：建立血缘
    public static void onBirth(ServerLevel level, Entity child, String parent1, String parent2)
    {
        String city = child.getCityName();
        child.setParents(parent1, parent2);
        child.syncToJson();
        addChildToParent(level, city, parent1, child.getNpcName());
        addChildToParent(level, city, parent2, child.getNpcName());
    }

    // 死亡摘除：移除死者+删死者全部关系文件
    public static void onDeath(ServerLevel level, Entity dead)
    {
        String name = dead.getNpcName();
        String city = dead.getCityName();
        if (name == null || name.isEmpty() || city == null || city.isEmpty()) return;

        // 伴侣丧偶：清理另一方的登记，允许再婚
        String partnerName = dead.getPartner();
        if (!partnerName.isEmpty())
        {
            Entity partner = findLoaded(level, partnerName);
            if (partner != null)
            {
                if (partner.getPartner().equals(name))
                {
                    partner.setPartner("");
                    partner.syncToJson();
                }
            }
            else
            {
                // 伴侣未加载：直接改写其文件
                NpcData.patchPartner(level, city, partnerName, "");
            }
        }

        for (String parent : dead.getParentNames())
        {
            FamilyData fam = getFamily(level, city, parent);
            List<String> children = new ArrayList<>(fam.children());
            if (children.remove(name))
            {
                applyFamily(level, city, parent, fam.parents(), children);
            }
        }

        for (String child : dead.getChildren())
        {
            FamilyData fam = getFamily(level, city, child);
            List<String> parents = new ArrayList<>();
            for (String p : fam.parents())
            {
                if (!p.equals(name)) parents.add(p);
            }
            applyFamily(level, city, child, parents, fam.children());
        }

        RelationshipPersistence.removeAllFor(level, city, name);
        LOGGER.info("NeoSim-Genealogy: '{}' removed from family tree & relationships", name);
    }

    // 死亡摘除：实体未加载期间寿终，死者数据读文件
    public static void onDeath(ServerLevel level, String city, String name)
    {
        if (name == null || name.isEmpty() || city == null || city.isEmpty()) return;

        JsonObject json = NpcData.load(level, city, name);
        if (json == null) return;

        // 伴侣丧偶：清理另一方的登记，允许再婚
        if (json.has("partner"))
        {
            String partnerName = json.get("partner").getAsString();
            if (!partnerName.isEmpty())
            {
                NpcData.patchPartner(level, city, partnerName, "");
            }
        }

        // 从死者档案取父母/子女
        FamilyData fam = getFamily(level, city, name);

        for (String parent : fam.parents())
        {
            FamilyData pf = getFamily(level, city, parent);
            List<String> children = new ArrayList<>(pf.children());
            if (children.remove(name))
            {
                applyFamily(level, city, parent, pf.parents(), children);
            }
        }

        for (String child : fam.children())
        {
            FamilyData cf = getFamily(level, city, child);
            List<String> parents = new ArrayList<>();
            for (String p : cf.parents())
            {
                if (!p.equals(name)) parents.add(p);
            }
            applyFamily(level, city, child, parents, cf.children());
        }

        RelationshipPersistence.removeAllFor(level, city, name);
        LOGGER.info("NeoSim-Genealogy: '{}' removed from family tree & relationships (data-side)", name);
    }

    // 改名
    public static void onRename(ServerLevel level, String city, Entity renamed, String oldName)
    {
        String newName = renamed.getNpcName();
        if (oldName == null || oldName.isEmpty() || oldName.equals(newName)) return;

        // 父母列表里的旧名->新名
        for (String parent : renamed.getParentNames())
        {
            FamilyData pf = getFamily(level, city, parent);
            List<String> children = new ArrayList<>(pf.children());
            boolean changed = false;
            for (int i = 0; i < children.size(); i++)
            {
                if (children.get(i).equals(oldName))
                {
                    children.set(i, newName);
                    changed = true;
                }
            }
            if (changed) applyFamily(level, city, parent, pf.parents(), children);
        }

        // 子女列表里的旧名->新名
        for (String child : renamed.getChildren())
        {
            FamilyData cf = getFamily(level, city, child);
            List<String> parents = new ArrayList<>(cf.parents());
            boolean changed = false;
            for (int i = 0; i < parents.size(); i++)
            {
                if (parents.get(i).equals(oldName))
                {
                    parents.set(i, newName);
                    changed = true;
                }
            }
            if (changed) applyFamily(level, city, child, parents, cf.children());
        }
    }

    private static void addChildToParent(ServerLevel level, String city, String parent, String child)
    {
        if (parent == null || parent.isEmpty() || child == null || child.isEmpty()) return;

        Entity npc = findLoaded(level, parent);
        if (npc != null)
        {
            if (!npc.getChildren().contains(child))
            {
                npc.addChild(child);
                npc.syncToJson();
            }
        }
        else
        {
            FamilyData fam = getFamily(level, city, parent);
            List<String> children = new ArrayList<>(fam.children());
            if (!children.contains(child))
            {
                children.add(child);
                NpcData.patchGenealogy(level, city, parent, fam.parents(), children);
            }
        }
    }

    // 改写某NPC的族谱字段
    private static void applyFamily(ServerLevel level, String city, String npcName,
                                    List<String> parents, List<String> children)
    {
        if (npcName == null || npcName.isEmpty()) return;

        Entity npc = findLoaded(level, npcName);
        if (npc != null)
        {
            npc.setParents(parents.size() > 0 ? parents.get(0) : "", parents.size() > 1 ? parents.get(1) : "");
            npc.setChildren(children);
            npc.syncToJson();
        }
        else
        {
            NpcData.patchGenealogy(level, city, npcName, parents, children);
        }
    }

    private static Entity findLoaded(ServerLevel level, String name)
    {
        if (name == null || name.isEmpty()) return null;
        for (net.minecraft.world.entity.Entity e : level.getAllEntities())
        {
            if (e instanceof Entity npc && name.equals(npc.getNpcName()))
            {
                return npc;
            }
        }
        return null;
    }
}

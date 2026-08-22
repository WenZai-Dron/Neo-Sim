package com.wenzai.neosim.life;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.*;

public class Genealogy
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private Genealogy()
	{
	}

	// 一名NPC的族谱数据
	public record FamilyData(List<String> parents, List<String> children)
	{
		public static FamilyData empty()
		{
			return new FamilyData(List.of(), List.of());
		}
	}

	// 族谱节点（网络传输/渲染用）
	public record FamilyNode(String name, String sex, String partner,
							 List<String> parents, List<String> children)
	{
	}

	// 单棵树节点数上限（防异常数据撑爆 payload/渲染）
	private static final int MAX_FAMILY_NODES = 50;

	// 血亲判定固定回溯深度（替代已删除的配置项）
	private static final int BLOOD_RELATION_DEPTH = 3;

	// 解析某名字的族谱：优先已加载实体NBT，未加载则读NpcData文件
	public static FamilyData getFamily(ServerLevel level, String city, String name)
	{
		if (name == null || name.isEmpty() || level.getServer() == null) return FamilyData.empty();

		Entity npc = com.wenzai.neosim.npc.NpcRegistry.findByName(name);
		if (npc != null)
		{
			return new FamilyData(npc.getParentNames(), npc.getChildren());
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

	// 读取单个族谱节点：优先已加载实体，未加载读文件；档案缺失返回名字占位
	private static FamilyNode readNode(ServerLevel level, String city, String name)
	{
		if (name == null || name.isEmpty()) return null;

		Entity npc = com.wenzai.neosim.npc.NpcRegistry.findByName(name);
		if (npc != null)
		{
			return new FamilyNode(name, npc.getSex(), npc.getPartner(),
					npc.getParentNames(), npc.getChildren());
		}

		JsonObject json = NpcData.load(level, city, name);
		if (json == null) return new FamilyNode(name, "", "", List.of(), List.of());

		String sex = json.has("sex") ? json.get("sex").getAsString() : "";
		String partner = json.has("partner") ? json.get("partner").getAsString() : "";
		List<String> parents = new ArrayList<>();
		List<String> children = new ArrayList<>();
		if (json.has("parents"))
		{
			JsonArray arr = json.getAsJsonArray("parents");
			for (int i = 0; i < arr.size(); i++) parents.add(arr.get(i).getAsString());
		}
		if (json.has("children"))
		{
			JsonArray arr = json.getAsJsonArray("children");
			for (int i = 0; i < arr.size(); i++) children.add(arr.get(i).getAsString());
		}
		return new FamilyNode(name, sex, partner, parents, children);
	}

	// 收集某 NPC 的三代族谱节点：本人、父母、祖辈、配偶、姻亲（配偶父母）、兄弟姐妹、子女
	public static List<FamilyNode> collectFamily(ServerLevel level, String city, String rootName)
	{
		List<FamilyNode> nodes = new ArrayList<>();
		if (rootName == null || rootName.isEmpty() || city == null || city.isEmpty()) return nodes;

		Set<String> seen = new HashSet<>();
		ArrayDeque<String> queue = new ArrayDeque<>();
		queue.add(rootName);

		while (!queue.isEmpty() && nodes.size() < MAX_FAMILY_NODES)
		{
			String name = queue.poll();
			if (!seen.add(name)) continue;

			FamilyNode node = readNode(level, city, name);
			if (node == null) continue;
			nodes.add(node);

			if (name.equals(rootName))
			{
				// 父母、子女、配偶
				for (String p : node.parents()) queue.add(p);
				for (String c : node.children()) queue.add(c);
				if (!node.partner().isEmpty()) queue.add(node.partner());

				// 祖辈 + 兄弟姐妹：读父母的数据
				for (String p : node.parents())
				{
					FamilyNode pn = readNode(level, city, p);
					if (pn == null) continue;
					// 祖辈
					for (String gp : pn.parents()) queue.add(gp);
					// 兄弟姐妹
					for (String s : pn.children()) queue.add(s);
				}
				// 姻亲：配偶的父母
				if (!node.partner().isEmpty())
				{
					FamilyNode sp = readNode(level, city, node.partner());
					if (sp != null)
					{
						for (String pp : sp.parents()) queue.add(pp);
					}
				}
			}
			// 非根节点：不扩展（子女的子女=孙辈、兄弟姐妹的子女=侄辈，均不收录）
		}
		return nodes;
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

		int depth = BLOOD_RELATION_DEPTH;

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
		return com.wenzai.neosim.npc.NpcRegistry.findByName(name);
	}
}

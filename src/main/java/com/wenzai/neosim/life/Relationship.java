// 关系等级链与增减

package com.wenzai.neosim.life;

import com.wenzai.neosim.Config;
import net.minecraft.server.level.ServerLevel;

import java.util.Random;

public class Relationship
{
	// 无logger字段
	private static final Random RANDOM = new Random();

	private Relationship() {}

	public enum RelationshipLevel
	{
		ENEMY, DESPISE, HATE, DISLIKE, AQUAINTANCE, FRIEND, GOODFRIEND, BESTFRIENDS;
	}

	// 每对居民一条关系：字典序规范化
	public record RelationshipData(String folk1, String folk2, RelationshipLevel level, int subLevel)
	{
		public static RelationshipData fresh(String a, String b)
		{
			return new RelationshipData(a, b, RelationshipLevel.AQUAINTANCE, 0);
		}
	}

	private static RelationshipData upgrade(RelationshipData rel)
	{
		switch (rel.level())
		{
			case ENEMY -> { return withLevel(rel, RelationshipLevel.DESPISE, 50); }
			case DESPISE -> { return withLevel(rel, RelationshipLevel.HATE, 50); }
			case HATE -> { return withLevel(rel, RelationshipLevel.DISLIKE, 50); }
			case DISLIKE -> { return withLevel(rel, RelationshipLevel.FRIEND, 50); }
			case AQUAINTANCE -> { return withLevel(rel, RelationshipLevel.FRIEND, 0); }
			case FRIEND -> { return withLevel(rel, RelationshipLevel.GOODFRIEND, 50); }
			case GOODFRIEND -> { return withLevel(rel, RelationshipLevel.BESTFRIENDS, 50); }

			// 掷硬币
			case BESTFRIENDS -> { return new RelationshipData(rel.folk1(), rel.folk2(), RelationshipLevel.BESTFRIENDS, 100); }
			default -> { return rel; }
		}
	}

	private static RelationshipData downgrade(RelationshipData rel)
	{
		switch (rel.level())
		{
			case ENEMY -> { return new RelationshipData(rel.folk1(), rel.folk2(), RelationshipLevel.ENEMY, 0); }
			case DESPISE -> { return withLevel(rel, RelationshipLevel.ENEMY, 50); }
			case HATE -> { return withLevel(rel, RelationshipLevel.DESPISE, 50); }
			case DISLIKE -> { return withLevel(rel, RelationshipLevel.HATE, 50); }
			case AQUAINTANCE -> { return withLevel(rel, RelationshipLevel.DISLIKE, 50); }
			case FRIEND -> { return withLevel(rel, RelationshipLevel.DISLIKE, 50); }
			case GOODFRIEND -> { return withLevel(rel, RelationshipLevel.FRIEND, 50); }
			case BESTFRIENDS -> { return withLevel(rel, RelationshipLevel.GOODFRIEND, 50); }
			default -> { return rel; }
		}
	}

	private static RelationshipData withLevel(RelationshipData rel, RelationshipLevel level, int subLevel)
	{
		return new RelationshipData(rel.folk1(), rel.folk2(), level, subLevel);
	}

	public static RelationshipData increase(RelationshipData rel, int by)
	{
		if (rel == null || by <= 0) return rel;
		int sub = rel.subLevel() + by;
		if (sub > 100)
		{
			return upgrade(rel);
		}
		return new RelationshipData(rel.folk1(), rel.folk2(), rel.level(), sub);
	}

	public static RelationshipData decrease(RelationshipData rel, int by)
	{
		if (rel == null || by <= 0) return rel;
		int sub = rel.subLevel() - by;
		if (sub < 0)
		{
			return downgrade(rel);
		}
		return new RelationshipData(rel.folk1(), rel.folk2(), rel.level(), sub);
	}

	// 互动结算
	public static void meddle(ServerLevel level, String city, String a, String b)
	{
		if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.equals(b)) return;

		String f1 = a.compareTo(b) <= 0 ? a : b;
		String f2 = a.compareTo(b) <= 0 ? b : a;

		RelationshipData rel = RelationshipPersistence.loadPair(level, city, f1, f2);
		if (rel == null)
		{
			RelationshipPersistence.save(level, city, RelationshipData.fresh(f1, f2));
			return;
		}

		RelationshipData next;
		if (RANDOM.nextDouble() < downgradeChance())
		{
			next = decrease(rel, RANDOM.nextInt(changeMax()));
		}
		else
		{
			next = increase(rel, RANDOM.nextInt(changeMax()));
		}

		if (!next.equals(rel))
		{
			RelationshipPersistence.save(level, city, next);
		}

		// 满级关系降级->解除同居/婚姻
		if (rel.level() == RelationshipLevel.BESTFRIENDS && next.level() != RelationshipLevel.BESTFRIENDS)
		{
			MarriageSystem.dissolve(level, city, f1, f2);
		}
	}

	// 关系变差概率
	private static double downgradeChance()
	{
		try
		{
			return Config.LIFE_RELATIONSHIP_DOWNGRADE_CHANCE.get();
		}
		catch (IllegalStateException ignored)
		{
			return 0.2;
		}
	}

	// 单次关系增减量上限
	private static int changeMax()
	{
		try
		{
			return Config.LIFE_RELATIONSHIP_CHANGE_MAX.get();
		}
		catch (IllegalStateException ignored)
		{
			return 30;
		}
	}
}

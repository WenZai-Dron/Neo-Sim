package com.wenzai.neosim.npc;

import java.util.Random;

// NPC 名字池：中文为"姓+名"（含小概率复姓），英文为"名 空格 姓"，两套都取少见且雅致的取向
// 英文池限制：纯 ASCII、无空格与撇号、单个字段不超过 10 字符（对齐改名输入框上限，避免手动改名被截断）
public final class NpcNames
{
	private static final Random RANDOM = new Random();

	// 复姓出现概率（雅致取向：小概率双字姓，如"慕容""上官"）
	private static final double COMPOUND_SURNAME_CHANCE = 0.08;

	// 中文池：单姓
	private static final String[] ZH_SURNAMES = {
			"苏", "顾", "沈", "陆", "裴", "温", "江", "萧", "谢", "韩",
			"秦", "卫", "柳", "颜", "纪", "容", "商", "闵", "燕", "虞",
			"池", "凌", "宿", "简", "白", "云", "穆", "岑", "黎", "殷",
			"邵", "祁", "慕", "卓", "蔺", "傅", "崔", "薛", "罗", "唐",
			"宋", "郑", "许", "潘", "杜", "严", "华", "陶", "戚", "柏",
			"窦", "章", "葛", "奚", "韦", "昌", "苗", "俞", "袁", "鲍",
			"廉", "方", "向", "石", "钟", "汪", "田", "任", "姜", "范",
			"姚", "谭", "廖", "邹", "熊", "金", "晏", "靳", "席", "仲",
			"桑", "樊", "屈", "洪", "邢", "单", "宁", "谷", "巩", "全",
			"舒", "童", "阮", "翟", "芮", "柯", "褚", "邬", "荆", "仇",
			"荀", "盛", "路", "詹", "牟"
	};

	// 偏男字
	private static final String[] ZH_MALE_GIVEN = {
			"铮", "朔", "渊", "澈", "辰", "琅", "霄", "翊", "珩", "晏",
			"临", "峥", "恪", "洵", "灏", "珣", "璁", "岑", "靳", "砚",
			"肃", "衍", "霁", "鹤", "曜", "冕", "乾", "勋", "铎", "璟",
			"靖", "屿", "序", "徵", "述", "让", "谦", "淮", "泓", "湛",
			"沅", "湘", "泠", "洄", "濯", "燧", "怀", "溪", "舟", "晦",
			"韶", "韫", "琮", "珉", "玠", "松", "竹", "檀", "楠", "榆",
			"桐", "槐", "楷", "樟", "榕", "昀", "昭", "昱", "晗", "昕",
			"钧", "祯", "祺", "祎", "禧", "睿", "哲", "彦", "儒", "贤",
			"逸", "澄", "潇", "泊", "洛", "斐", "澜", "衡", "牧", "樾",
			"槿", "枫", "景", "泰", "宁", "康", "瑞", "祥", "德", "仁",
			"义", "文", "章", "学", "思", "明", "达", "通", "远", "承",
			"启"
	};

	// 偏女字
	private static final String[] ZH_FEMALE_GIVEN = {
			"瑜", "瑶", "璇", "琳", "玥", "珞", "瑟", "绮", "素", "蘅",
			"黛", "漪", "汐", "澜", "琬", "琼", "蕙", "芸", "芊", "霜",
			"鸾", "笙", "岚", "浅", "晚", "晴", "初", "微", "婉", "慧",
			"雅", "静", "滢", "溪", "润", "涵", "沐", "诗", "画", "琴",
			"棋", "书", "墨", "韵", "音", "歌", "语", "兰", "莲", "荷",
			"菊", "梅", "桂", "杏", "桃", "樱", "薇", "燕", "莺", "鹃",
			"凰", "雁", "鹤", "思", "念", "忆", "怀", "梦", "幻", "悦",
			"欣", "怡", "若", "如", "依", "曼", "柔", "芷", "苓", "苒",
			"菀", "荞", "琉", "瑄", "琤", "筱", "栀", "芜", "岫", "泠",
			"疏", "眠", "蕖", "檀", "暖", "簌"
	};

	// 复姓：小概率出现，增加雅致感（完整姓氏 = 双字）
	private static final String[] ZH_COMPOUND_SURNAMES = {
			"慕容", "欧阳", "上官", "司徒", "南宫", "东方", "西门", "独孤", "皇甫", "尉迟",
			"长孙", "宇文", "公孙", "夏侯", "轩辕", "令狐", "钟离", "诸葛", "闻人", "赫连",
			"澹台", "端木", "拓跋", "呼延", "百里", "第五", "亓官", "申屠", "公羊", "梁丘"

	};

	// 英文池：姓
	private static final String[] EN_SURNAMES = {
			"Ashford", "Ashby", "Ashcombe", "Ashgrove", "Ashworth", "Aldridge", "Bellamy", "Birchwood", "Blackwood", "Blackthorn",
			"Bramble", "Calloway", "Carrington", "Carlisle", "Cresswell", "Danforth", "Davenport", "Dunmore", "Eastwood", "Ellesmere",
			"Ellery", "Everly", "Fairfax", "Fenwick", "Frost", "Garland", "Greystone", "Grey", "Halloway", "Harlow",
			"Harrington", "Hartley", "Haven", "Heathcote", "Holloway", "Hollowell", "Ironwood", "Kensington", "Kingsbury", "Kingsley",
			"Langford", "Lark", "Leighton", "Lindquist", "Lockhart", "Lockwood", "Lyndon", "Marlowe", "Marwood", "Meridian",
			"Norcross", "Norwood", "Oakley", "Orchard", "Ormond", "Pemberton", "Penrose", "Prescott", "Primrose", "Quill",
			"Ravenswood", "Rayleigh", "Redmond", "Redwood", "Ridgeway", "Rowan", "Sable", "Selwyn", "Silverton", "Sinclair",
			"Skye", "Sterling", "Stroud", "Sutton", "Sylvan", "Thackeray", "Thornbury", "Thorne", "Thornfield", "Vane",
			"Vandermeer", "Vesper", "Wakefield", "Westbrook", "Westfall", "Whitfield", "Whitlock", "Whitmore", "Wilder", "Windermere",
			"Winslow", "Winter", "Wisteria", "Wycliffe", "Yarrow", "Zephyr", "Larkspur", "Wrenfield", "Hollis", "Ainsley"
	};

	// 男名
	private static final String[] EN_MALE_GIVEN = {
			"Adrian", "Alaric", "Alistair", "Ambrose", "Ansel", "Arden", "Augustine", "Aurelian", "Aurelius", "Bastian",
			"Blaise", "Aurelio", "Byron", "Caelan", "Caspian", "Casper", "Cassian", "Cedric", "Cillian", "Conall",
			"Corin", "Damian", "Darius", "Desmond", "Dorian", "Edmund", "Edric", "Elias", "Emilian", "Emrys",
			"Evander", "Everett", "Ezekiel", "Fabian", "Felix", "Florian", "Gabriel", "Gareth", "Gideon", "Grayson",
			"Hadrian", "Hugo", "Idris", "Ignatius", "Isidore", "Jasper", "Julian", "Kaelen", "Kellan", "Kieran",
			"Lachlan", "Leander", "Lorcan", "Lucian", "Lucius", "Lysander", "Matthias", "Merrick", "Nathaniel", "Nikolai",
			"Oberon", "Orson", "Osric", "Percival", "Phineas", "Quentin", "Rafferty", "Raphael", "Roderick", "Ronan",
			"Rowan", "Sebastian", "Soren", "Thaddeus", "Theodore", "Tobias", "Tristan", "Valen", "Valerian", "Victor",
			"Vincent", "Wesley", "Wilder", "Xavier", "Cormac", "Emeric", "Lorien", "Peregrine", "Silvan", "Peregrin"
	};

	// 女名
	private static final String[] EN_FEMALE_GIVEN = {
			"Adeline", "Alara", "Althea", "Amara", "Arabella", "Ariadne", "Aurelia", "Aurora", "Beatrix", "Brielle",
			"Calista", "Cassandra", "Celeste", "Cerys", "Clarissa", "Clara", "Cordelia", "Corinne", "Dahlia", "Delphine",
			"Eira", "Elara", "Elena", "Elin", "Elowen", "Eluned", "Emeline", "Evanthe", "Evelina", "Florence",
			"Freya", "Genevieve", "Guinevere", "Gwendolyn", "Helena", "Imogen", "Iris", "Isolde", "Juliet", "Juniper",
			"Lavinia", "Leandra", "Liana", "Liliana", "Linnea", "Liora", "Lucinda", "Lyra", "Maeve", "Marguerite",
			"Marisol", "Meredith", "Mirabel", "Nerissa", "Noelle", "Odette", "Ophelia", "Oriana", "Philippa", "Rhiannon",
			"Rosalind", "Rowena", "Sabine", "Selene", "Seraphina", "Seren", "Sierra", "Solene", "Sylvia", "Tamsin",
			"Thalia", "Thea", "Verity", "Vera", "Vivienne", "Wren", "Yvette", "Zaria", "Anwen", "Bronwen",
			"Catrin", "Elspeth", "Ffion", "Isla", "Maren", "Nerys", "Sian", "Rosamund", "Valeria", "Thisbe"
	};

	private NpcNames()
	{
	}

	public static String randomSurname(NameLocale locale)
	{
		if (locale != NameLocale.ZH)
		{
			return EN_SURNAMES[RANDOM.nextInt(EN_SURNAMES.length)];
		}
		if (RANDOM.nextDouble() < COMPOUND_SURNAME_CHANCE)
		{
			return ZH_COMPOUND_SURNAMES[RANDOM.nextInt(ZH_COMPOUND_SURNAMES.length)];
		}
		return ZH_SURNAMES[RANDOM.nextInt(ZH_SURNAMES.length)];
	}

	// 随机名：中文 30% 单字 / 70% 双字；英文取单个常用名
	public static String randomGiven(NameLocale locale, String sex)
	{
		boolean female = "female".equals(sex);
		if (locale == NameLocale.EN)
		{
			String[] pool = female ? EN_FEMALE_GIVEN : EN_MALE_GIVEN;
			return pool[RANDOM.nextInt(pool.length)];
		}

		String[] pool = female ? ZH_FEMALE_GIVEN : ZH_MALE_GIVEN;
		if (RANDOM.nextDouble() < 0.3)
		{
			return pool[RANDOM.nextInt(pool.length)];
		}
		int i = RANDOM.nextInt(pool.length);
		int j;
		do
		{
			j = RANDOM.nextInt(pool.length);
		} while (j == i);
		return pool[i] + pool[j];
	}

	// 按命名风格拼全名；缺任一部分时只返回另一部分
	public static String format(NameLocale locale, String surname, String givenName)
	{
		String s = surname == null ? "" : surname;
		String g = givenName == null ? "" : givenName;
		if (s.isEmpty()) return g;
		if (g.isEmpty()) return s;
		return locale == NameLocale.EN ? g + " " + s : s + g;
	}

	// 旧档（无 NameLocale 字段）与客户端未知情形：按名字内容推断
	public static NameLocale infer(String fullName)
	{
		return containsCjk(fullName) ? NameLocale.ZH : NameLocale.EN;
	}

	private static boolean containsCjk(String text)
	{
		if (text == null || text.isEmpty()) return true;
		for (int i = 0; i < text.length(); i++)
		{
			if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) return true;
		}
		return false;
	}
}

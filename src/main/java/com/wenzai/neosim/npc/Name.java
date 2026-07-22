package com.wenzai.neosim.npc;

import net.minecraft.nbt.CompoundTag;

import java.util.Random;

public class Name
{
    private static final String KEY_FULL_NAME = "nsnpc_name";
    private static final String KEY_SURNAME = "nsnpc_surname";
    private static final String KEY_GIVEN_NAME = "nsnpc_givenName";
    private static final String KEY_SEX = "nsnpc_sex";

    private static final Random RANDOM = new Random();

    // 应该以后会写英文版姓名

    // 姓
    private static final String[] SURNAMES = {
            "张", "李", "王", "刘", "陈", "杨", "赵", "黄", "周", "吴",
            "徐", "孙", "胡", "朱", "高", "林", "何", "郭", "马", "罗",
            "梁", "宋", "郑", "谢", "韩", "唐", "冯", "于", "董", "萧",
            "程", "曹", "袁", "邓", "许", "傅", "沈", "曾", "彭", "吕",
            "苏", "卢", "蒋", "蔡", "贾", "丁", "魏", "薛", "叶", "阎",
            "余", "潘", "杜", "戴", "夏", "钟", "汪", "田", "任", "姜",
            "范", "方", "石", "姚", "谭", "廖", "邹", "熊", "金", "陆",
            "郝", "孔", "白", "崔", "康", "毛", "邱", "秦", "江", "史",
            "顾", "侯", "邵", "孟", "龙", "万", "段", "雷", "钱", "汤"
    };

    // 偏男字
    private static final String[] MALE_NAME_CHARS = {
            "铮", "朔", "渊", "澈", "辰", "琅", "霄", "翊", "珩", "晏",
            "临", "峥", "恪", "洵", "灏", "珣", "璁", "岑", "靳", "砚",
            "肃", "衍", "霁", "鹤", "曜", "冕", "乾", "勋", "铎", "璟"
    };

    // 偏女字
    private static final String[] FEMALE_NAME_CHARS = {
            "瑜", "瑶", "璇", "琳", "玥", "珞", "瑟", "绮", "素", "蘅",
            "黛", "漪", "汐", "澜", "琬", "琼", "蕙", "芸", "芊", "霜",
            "鸾", "笙", "岚", "浅", "晚", "晴", "初", "舞", "胭", "微"
    };

    private final CompoundTag tag;

    private Name(CompoundTag tag)
    {
        this.tag = tag;
    }

    // 从NBT创建Name实例
    public static Name of(CompoundTag tag)
    {
        return new Name(tag);
    }

    public static String generateRandom()
    {
        String surname = SURNAMES[RANDOM.nextInt(SURNAMES.length)];
        String sex = RANDOM.nextBoolean() ? "male" : "female";
        String givenName = randomGivenName(sex);
        return surname + givenName;
    }

    // 生成姓
    public static String randomSurname()
    {
        return SURNAMES[RANDOM.nextInt(SURNAMES.length)];
    }

    // 生成名：30%单字，70%双字
    public static String randomGivenName(String sex)
    {
        String[] pool = "female".equals(sex) ? FEMALE_NAME_CHARS : MALE_NAME_CHARS;
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

    // 生成姓和名并写入当前NBT，在NPC生成时调用此方法即可完成命名
    public void generateAndSet()
    {
        String surname = randomSurname();
        String sex = randomSex();
        String givenName = randomGivenName(sex);
        tag.putString(KEY_SURNAME, surname);
        tag.putString(KEY_GIVEN_NAME, givenName);
        tag.putString(KEY_FULL_NAME, surname + givenName);
        tag.putString(KEY_SEX, sex);
    }

    // 设置姓名
    public void set(String value)
    {
        tag.putString(KEY_FULL_NAME, value);
    }

    // 获取姓名
    public String get()
    {
        return tag.contains(KEY_FULL_NAME) ? tag.getString(KEY_FULL_NAME) : "";
    }

    // 设置姓
    public void setSurname(String value)
    {
        tag.putString(KEY_SURNAME, value);
    }

    // 获取姓
    public String getSurname()
    {
        return tag.contains(KEY_SURNAME) ? tag.getString(KEY_SURNAME) : "";
    }

    // 设置名
    public void setGivenName(String value)
    {
        tag.putString(KEY_GIVEN_NAME, value);
    }

    // 获取名
    public String getGivenName()
    {
        return tag.contains(KEY_GIVEN_NAME) ? tag.getString(KEY_GIVEN_NAME) : "";
    }

    // 随机性别
    public static String randomSex()
    {
        return RANDOM.nextBoolean() ? "male" : "female";
    }

    // 设置性别
    public void setSex(String value)
    {
        tag.putString(KEY_SEX, value);
    }

    // 获取性别
    public String getSex()
    {
        return tag.contains(KEY_SEX) ? tag.getString(KEY_SEX) : "male";
    }
}

package com.wenzai.neosim.npc;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.block.BuildingConstructor;
import com.wenzai.neosim.block.DeliveryBox;
import com.wenzai.neosim.block.FarmingBox;
import com.wenzai.neosim.block.MiningBox;
import com.wenzai.neosim.life.Genealogy;
import com.wenzai.neosim.life.LifeSystem;
import com.wenzai.neosim.life.SocialGoal;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.portal.DimensionTransition;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.Supplier;

import javax.annotation.Nullable;

public class Entity extends PathfinderMob
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
			DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, NeoSim.MOD_ID);

	public static final Supplier<EntityType<Entity>> NPC =
			ENTITY_TYPES.register("nsnpc",
					() -> EntityType.Builder.of(Entity::new, MobCategory.CREATURE)
							.sized(0.6F, 1.8F)
							.eyeHeight(1.62F)
							.clientTrackingRange(10)
							.build("nsnpc"));

	public Entity(EntityType<? extends PathfinderMob> entityType, Level level)
	{
		super(entityType, level);

		// NPC持久化
		setPersistenceRequired();
		setCustomNameVisible(true);
		setAge(randomAge());

		// 寻路远离环境危害
		setPathfindingMalus(PathType.LAVA, -1.0F);
		setPathfindingMalus(PathType.DANGER_FIRE, -1.0F);
		setPathfindingMalus(PathType.DAMAGE_FIRE, -1.0F);
		setPathfindingMalus(PathType.DANGER_OTHER, -1.0F);
		setPathfindingMalus(PathType.DAMAGE_OTHER, -1.0F);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder)
	{
		super.defineSynchedData(builder);
		builder.define(DATA_SKIN, "");
		builder.define(DATA_FROZEN, false);
		builder.define(DATA_BUILD_ANIM, 0.0F);

		// -1 哨兵：真实年龄(0+)永远不会等于默认值，保证出生/读档时都会同步到客户端
		builder.define(DATA_AGE, -1);
	}

	// 获取姓名
	public String getNpcName()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_FULL_NAME) ? tag.getString(KEY_FULL_NAME) : "";
	}

	// 获取姓
	public String getNpcSurname()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_SURNAME) ? tag.getString(KEY_SURNAME) : "";
	}

	// 获取名
	public String getNpcGivenName()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_GIVEN_NAME) ? tag.getString(KEY_GIVEN_NAME) : "";
	}

	// 命名风格：优先读存档；旧档无该字段时按当前名字推断（中文名→ZH，英文名→EN）
	public NameLocale getNameLocale()
	{
		CompoundTag tag = getPersistentData();
		if (tag.contains(KEY_NAME_LOCALE))
		{
			NameLocale stored = NameLocale.fromKey(tag.getString(KEY_NAME_LOCALE));
			if (stored != null) return stored;
		}
		return NpcNames.infer(getNpcName());
	}

	public void setNameLocale(NameLocale locale)
	{
		getPersistentData().putString(KEY_NAME_LOCALE, locale.key());
	}

	// 设置姓名，同步文件
	public void setNpcName(String surname, String givenName)
	{
		String oldName = getNpcName();

		// 改名：索引重挂键（先摘旧键，避免旧名仍能查到本实体）
		if (isAddedToLevel()) NpcRegistry.unregister(this);
		CompoundTag tag = getPersistentData();
		tag.putString(KEY_SURNAME, surname);
		tag.putString(KEY_GIVEN_NAME, givenName);
		String fullName = NpcNames.format(getNameLocale(), surname, givenName);
		tag.putString(KEY_FULL_NAME, fullName);
		setCustomName(Component.literal(fullName));
		setCustomNameVisible(true);
		if (isAddedToLevel()) NpcRegistry.register(this);

		// 如果名字改变，删除旧文件并保存新文件
		if (!oldName.isEmpty() && !oldName.equals(fullName) && !fullName.isEmpty())
		{
			String cityName = getCityName();
			if (!cityName.isEmpty() && level().getServer() != null)
			{
				if (level().getServer().isDedicatedServer())
				{
					NpcData.delete(oldName, cityName);
					NpcData.save(this, cityName);
				}
				else
				{
					String saveName = level().getServer().getWorldData().getLevelName();
					NpcData.delete(oldName, cityName, saveName);
					NpcData.save(this, cityName, saveName);
				}
			}
		}
	}

	// 设置姓名，同时同步以在头顶渲染
	public void setNpcName(String name)
	{
		String oldName = getNpcName();

		// 改名：索引重挂键
		if (isAddedToLevel()) NpcRegistry.unregister(this);
		getPersistentData().putString(KEY_FULL_NAME, name);
		if (name.isEmpty())
		{
			setCustomName(null);
			setCustomNameVisible(false);
		}
		else
		{
			setCustomName(Component.literal(name));
			setCustomNameVisible(true);
		}
		if (isAddedToLevel()) NpcRegistry.register(this);

		// 如果名字改变，删除旧JSON并保存新JSON（仅服务端）
		if (!oldName.isEmpty() && !oldName.equals(name) && !name.isEmpty())
		{
			String cityName = getCityName();
			if (!cityName.isEmpty() && level().getServer() != null)
			{
				if (level().getServer().isDedicatedServer())
				{
					NpcData.delete(oldName, cityName);
					NpcData.save(this, cityName);
				}
				else
				{
					String saveName = level().getServer().getWorldData().getLevelName();
					NpcData.delete(oldName, cityName, saveName);
					NpcData.save(this, cityName, saveName);
				}
			}
		}
	}

	// 获取性别
	public String getSex()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_SEX) ? tag.getString(KEY_SEX) : "male";
	}

	// 设置性别
	public void setSex(String sex)
	{
		getPersistentData().putString(KEY_SEX, sex);
	}

	// 获取年龄
	public short getAge()
	{
		// 客户端用同步值（渲染缩放判断），服务端用持久化数据（权威）
		if (this.level() != null && this.level().isClientSide)
		{
			return (short) (int) this.entityData.get(DATA_AGE);
		}
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_AGE) ? tag.getShort(KEY_AGE) : (short) 0;
	}

	// 设置年龄
	public void setAge(short age)
	{
		getPersistentData().putShort(KEY_AGE, age);

		// 同步到客户端，供模型缩放渲染使用
		this.entityData.set(DATA_AGE, (int) age);
	}

	// 是否成年
	public boolean isAdult()
	{
		int adultAge = 15;
		try
		{
			adultAge = Config.LIFE_ADULT_AGE.get();
		}
		catch (IllegalStateException ignored)
		{
			// 配置尚未加载，使用默认值
		}
		return getAge() >= adultAge;
	}

	// 是否未成年（模型缩小渲染）
	public boolean isUnderage()
	{
		return !isAdult();
	}

	// 未成年模型缩放系数（0.75倍）
	public static final float UNDERAGE_SCALE = 0.75F;

	// 未成年NPC：模型/碰撞箱缩小
	@Override
	public float getScale()
	{
		return isUnderage() ? UNDERAGE_SCALE : super.getScale();
	}

	// 随机年龄，范围由配置文件决定
	public static short randomAge()
	{
		// 使用默认值以防配置尚未加载
		int min = 15;
		int max = 25;
		try
		{
			min = Config.NPC_MIN_AGE.get();
			max = Config.NPC_MAX_AGE.get();
		}
		catch (IllegalStateException ignored)
		{
			// 配置尚未加载，使用默认值
		}
		if (min >= max) return (short) min;
		return (short) (min + RANDOM.nextInt(max - min + 1));
	}

	// 获取建筑师职业等级
	public byte getJobArchitect()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_JOB_ARCHITECT) ? tag.getByte(KEY_JOB_ARCHITECT) : (byte) 1;
	}

	// 设置建筑师职业等级
	public void setJobArchitect(byte value)
	{
		getPersistentData().putByte(KEY_JOB_ARCHITECT, value);
	}

	// 获取农夫职业等级
	public byte getJobFarmer()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_JOB_FARMER) ? tag.getByte(KEY_JOB_FARMER) : (byte) 1;
	}

	// 设置农夫职业等级
	public void setJobFarmer(byte value)
	{
		getPersistentData().putByte(KEY_JOB_FARMER, value);
	}

	// 获取矿工职业等级
	public byte getJobMiner()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_JOB_MINER) ? tag.getByte(KEY_JOB_MINER) : (byte) 1;
	}

	// 设置矿工职业等级
	public void setJobMiner(byte value)
	{
		getPersistentData().putByte(KEY_JOB_MINER, value);
	}

	// 获取快递员职业等级
	public byte getJobCourier()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_JOB_COURIER) ? tag.getByte(KEY_JOB_COURIER) : (byte) 1;
	}

	// 设置快递员职业等级
	public void setJobCourier(byte value)
	{
		getPersistentData().putByte(KEY_JOB_COURIER, value);
	}

	private static final String KEY_CITY_NAME = "nsnpc_cityName";
	private static final String KEY_SKIN = "nsnpc_skin";
	private static final String KEY_AGE = "nsnpc_age";
	private static final String KEY_JOB_ARCHITECT = "nsnpc_job_architect";
	private static final String KEY_JOB_FARMER = "nsnpc_job_farmer";
	private static final String KEY_JOB_MINER = "nsnpc_job_miner";
	private static final String KEY_JOB_COURIER = "nsnpc_job_courier";

	static final String KEY_FULL_NAME = "nsnpc_name";
	static final String KEY_SURNAME = "nsnpc_surname";
	static final String KEY_GIVEN_NAME = "nsnpc_givenName";
	static final String KEY_SEX = "nsnpc_sex";

	// 命名风格（zh/en）：决定全名语序，出生继承、改名沿用
	static final String KEY_NAME_LOCALE = "nsnpc_nameLocale";

	// NPC位置持久化
	private static final String KEY_ASSIGNED_SITE_X = "nsnpc_site_x";
	private static final String KEY_ASSIGNED_SITE_Y = "nsnpc_site_y";
	private static final String KEY_ASSIGNED_SITE_Z = "nsnpc_site_z";

	// 生活点持久化
	private static final String KEY_HOME_X = "nsnpc_home_x";
	private static final String KEY_HOME_Y = "nsnpc_home_y";
	private static final String KEY_HOME_Z = "nsnpc_home_z";
	private static final String KEY_HOME_BUILDING = "nsnpc_home_building";

	// 今日在家休息
	private static final String KEY_REST_TODAY = "nsnpc_restToday";

	// 关系与族谱
	static final String KEY_PARTNER = "nsnpc_partner";
	static final String KEY_PARENT1 = "nsnpc_parent1";
	static final String KEY_PARENT2 = "nsnpc_parent2";
	static final String KEY_CHILDREN = "nsnpc_children";
	static final String KEY_PREGNANCY = "nsnpc_pregnancy";
	static final String KEY_MATING = "nsnpc_mating";

	// 串门社交状态（瞬态，不持久化：进度存于relationship文件，计数重启丢可接受）
	private String hangingWith = "";
	private int hangTicks = 0;

	private static final EntityDataAccessor<String> DATA_SKIN =
			SynchedEntityData.defineId(Entity.class, EntityDataSerializers.STRING);

	private static final EntityDataAccessor<Boolean> DATA_FROZEN =
			SynchedEntityData.defineId(Entity.class, EntityDataSerializers.BOOLEAN);

	// 服务端驱动，客户端渲染抬手动画
	private static final EntityDataAccessor<Float> DATA_BUILD_ANIM =
			SynchedEntityData.defineId(Entity.class, EntityDataSerializers.FLOAT);

	// 客户端上一帧同步值
	private float prevBuildAnim;

	private static final EntityDataAccessor<Integer> DATA_AGE =
			SynchedEntityData.defineId(Entity.class, EntityDataSerializers.INT);

	// 记录哪些玩家打开了该NPC的GUI，用于计数冻结
	private final Set<UUID> guiOpeners = new HashSet<>();

	// 走向工地的寻路目标
	private NpcGoals.MoveToSiteGoal moveToSiteGoal;

	// 安全AI：逃离敌对生物（顺带躲玩家）；最高优先级
	private AvoidEntityGoal<LivingEntity> fleeHostileGoal;

	// 工地待料：原地待命（只停住，不停安全AI）；休息时间由调用方解除
	private boolean stayPut;

	// 当前寻路目标，避免每tick重复设置重置卡住检测
	private BlockPos currentMoveTarget;

	// 家/工作点 NBT 缓存（getHomePos/getAssignedSite 每 tick 被高频调用，缓存避免每 tick 解析 CompoundTag + new BlockPos）
	private BlockPos cachedHomePos;
	private String cachedHomeBuilding;
	private boolean homeCacheValid;
	private BlockPos cachedAssignedSite;
	private boolean siteCacheValid;

	private static final Random RANDOM = new Random();

	private static final String[] MALE_SKINS = {
			"achr1d.png", "daycrime.png", "gohanssj.png", "kazvran.png", "nocqnameponer.png",
			"peaq.png", "poishii.png", "radwool.png", "theezku.png", "whuz.png"
	};

	private static final String[] FEMALE_SKINS = {
			"anya03.png", "b0mbies.png", "blazerhack.png", "fearlicia.png", "kajikasu.png",
			"khristinatina.png", "lunatique.png", "mewlee.png", "osukaari.png", "prueli.png"
	};

	// 姓名池已移至 NpcNames（中文/英文双语池 + 语序格式化，见 NpcNames.format）

	// 获取所属城市
	public String getCityName()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_CITY_NAME) ? tag.getString(KEY_CITY_NAME) : "";
	}

	// 设置所属城市
	public void setCityName(String cityName)
	{
		getPersistentData().putString(KEY_CITY_NAME, cityName);
	}

	// 获取家（生活点）位置，无家返回null（字段缓存）
	@Nullable
	public BlockPos getHomePos()
	{
		if (!homeCacheValid)
		{
			homeCacheValid = true;
			cachedHomePos = null;
			cachedHomeBuilding = "";
			CompoundTag tag = getPersistentData();
			if (tag.contains(KEY_HOME_X) && tag.contains(KEY_HOME_Y) && tag.contains(KEY_HOME_Z))
			{
				cachedHomePos = new BlockPos(tag.getInt(KEY_HOME_X), tag.getInt(KEY_HOME_Y), tag.getInt(KEY_HOME_Z));
				if (tag.contains(KEY_HOME_BUILDING)) cachedHomeBuilding = tag.getString(KEY_HOME_BUILDING);
			}
		}
		return cachedHomePos;
	}

	// 获取家所在建筑名（生活点入住时登记；L6：随 getHomePos 一并缓存）
	public String getHomeBuilding()
	{
		// 确保缓存已构建
		getHomePos();
		return cachedHomeBuilding;
	}

	// 登记为某建筑居民（生活点系统分配时调用）
	public void setHome(BlockPos home, String buildingName)
	{
		CompoundTag tag = getPersistentData();
		tag.putInt(KEY_HOME_X, home.getX());
		tag.putInt(KEY_HOME_Y, home.getY());
		tag.putInt(KEY_HOME_Z, home.getZ());
		tag.putString(KEY_HOME_BUILDING, buildingName != null ? buildingName : "");
		homeCacheValid = true;
		cachedHomePos = home;
		cachedHomeBuilding = buildingName != null ? buildingName : "";
	}

	// 退房：清空家登记
	public void clearHome()
	{
		CompoundTag tag = getPersistentData();
		tag.remove(KEY_HOME_X);
		tag.remove(KEY_HOME_Y);
		tag.remove(KEY_HOME_Z);
		tag.remove(KEY_HOME_BUILDING);
		homeCacheValid = true;
		cachedHomePos = null;
		cachedHomeBuilding = "";
	}

	// 登记为某建筑居民并同步写盘（生活点系统分配时调用，保证 npc/*.json 与实体 NBT 一致）
	public void setHomeAndSync(BlockPos home, String buildingName)
	{
		setHome(home, buildingName);
		syncToJson();
	}

	// 退房：清空家登记并同步写盘（保证 npc/*.json 不再残留 home 字段）
	public void clearHomeAndSync()
	{
		clearHome();
		syncToJson();
	}

	// 今天是否在家休息
	public boolean isRestToday()
	{
		return getPersistentData().getBoolean(KEY_REST_TODAY);
	}

	public void setRestToday(boolean rest)
	{
		getPersistentData().putBoolean(KEY_REST_TODAY, rest);
	}

	// 现在是不是休息时间：夜里(12000~24000) 或 抽到"今天休息"
	// 休息期间不工作（赴工/干活全停）、也不许被兜底传送（宁可站着走回去，不要凭空拽）
	public boolean isRestingNow()
	{
		return isRestToday() || com.wenzai.neosim.Config.isRestTime(level().getDayTime());
	}

	// 同居/婚姻对象名（等待Phase 3婚姻填充）
	public String getPartner()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_PARTNER) ? tag.getString(KEY_PARTNER) : "";
	}

	public void setPartner(String partner)
	{
		getPersistentData().putString(KEY_PARTNER, partner != null ? partner : "");
	}

	// 父1
	public String getParent1()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_PARENT1) ? tag.getString(KEY_PARENT1) : "";
	}

	// 母2
	public String getParent2()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_PARENT2) ? tag.getString(KEY_PARENT2) : "";
	}

	// 父母名单
	public List<String> getParentNames()
	{
		List<String> parents = new ArrayList<>();
		String p1 = getParent1();
		String p2 = getParent2();
		if (!p1.isEmpty()) parents.add(p1);
		if (!p2.isEmpty()) parents.add(p2);
		return parents;
	}

	public void setParents(String parent1, String parent2)
	{
		CompoundTag tag = getPersistentData();
		tag.putString(KEY_PARENT1, parent1 != null ? parent1 : "");
		tag.putString(KEY_PARENT2, parent2 != null ? parent2 : "");
	}

	// 子女名单
	public List<String> getChildren()
	{
		List<String> children = new ArrayList<>();
		CompoundTag tag = getPersistentData();
		if (tag.contains(KEY_CHILDREN, Tag.TAG_LIST))
		{
			ListTag list = tag.getList(KEY_CHILDREN, Tag.TAG_STRING);
			for (int i = 0; i < list.size(); i++)
			{
				children.add(list.getString(i));
			}
		}
		return children;
	}

	public void setChildren(List<String> children)
	{
		CompoundTag tag = getPersistentData();
		ListTag list = new ListTag();
		if (children != null)
		{
			for (String c : children)
			{
				if (c != null && !c.isEmpty() && !listContains(list, c))
				{
					list.add(StringTag.valueOf(c));
				}
			}
		}
		tag.put(KEY_CHILDREN, list);
	}

	public void addChild(String name)
	{
		if (name == null || name.isEmpty()) return;
		CompoundTag tag = getPersistentData();
		ListTag list = tag.contains(KEY_CHILDREN, Tag.TAG_LIST)
				? tag.getList(KEY_CHILDREN, Tag.TAG_STRING) : new ListTag();
		if (!listContains(list, name))
		{
			list.add(StringTag.valueOf(name));
			tag.put(KEY_CHILDREN, list);
		}
	}

	public void removeChild(String name)
	{
		if (name == null || name.isEmpty()) return;
		CompoundTag tag = getPersistentData();
		if (!tag.contains(KEY_CHILDREN, Tag.TAG_LIST)) return;
		ListTag old = tag.getList(KEY_CHILDREN, Tag.TAG_STRING);
		ListTag list = new ListTag();
		for (int i = 0; i < old.size(); i++)
		{
			if (!old.getString(i).equals(name))
			{
				list.add(StringTag.valueOf(old.getString(i)));
			}
		}
		tag.put(KEY_CHILDREN, list);
	}

	// 孕期进度
	public float getPregnancyStage()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_PREGNANCY) ? tag.getFloat(KEY_PREGNANCY) : 0.0F;
	}

	public void setPregnancyStage(float stage)
	{
		getPersistentData().putFloat(KEY_PREGNANCY, stage);
	}

	// 进度
	public float getMatingStage()
	{
		CompoundTag tag = getPersistentData();
		return tag.contains(KEY_MATING) ? tag.getFloat(KEY_MATING) : -1.0F;
	}

	public void setMatingStage(float stage)
	{
		getPersistentData().putFloat(KEY_MATING, stage);
	}

	private static boolean listContains(ListTag list, String name)
	{
		for (int i = 0; i < list.size(); i++)
		{
			if (list.getString(i).equals(name)) return true;
		}
		return false;
	}

	// 串门社交：当前对象名/凑在一起累计tick（瞬态）
	public String getHangingWith()
	{
		return hangingWith;
	}

	public void setHangingWith(String name)
	{
		this.hangingWith = name != null ? name : "";
	}

	public int getHangTicks()
	{
		return hangTicks;
	}

	public void setHangTicks(int ticks)
	{
		this.hangTicks = ticks;
	}

	// 是否有工作（被分配到工地）
	public boolean hasJob()
	{
		return getPersistentData().contains(KEY_ASSIGNED_SITE_X);
	}

	// 获取皮肤
	public String getSkin()
	{
		return entityData.get(DATA_SKIN);
	}

	// 设置皮肤，同时写入数据
	public void setSkin(String skin)
	{
		entityData.set(DATA_SKIN, skin);
		getPersistentData().putString(KEY_SKIN, skin);
	}

	// 随机选取皮肤文件名
	public static String randomSkinFile(String sex)
	{
		String[] skins = "male".equals(sex) ? MALE_SKINS : FEMALE_SKINS;
		return skins[RANDOM.nextInt(skins.length)];
	}

	// 随机选取完整皮肤路径
	public static String randomSkin(String sex)
	{
		return "skins/" + sex + "/" + randomSkinFile(sex);
	}

	// 生成姓名、性别并写入NBT，在NPC生成时调用（命名风格按配置/客户端语言解析）
	public static void generateAndSetName(ServerLevel level, Entity entity, String cityName)
	{
		generateAndSetName(level, entity, cityName, null, resolveNameLocale(cityName, null));
	}

	// 生成姓名：触发玩家已知时用该玩家的语言池
	public static void generateAndSetName(ServerLevel level, Entity entity, String cityName, @Nullable UUID triggerPlayer)
	{
		generateAndSetName(level, entity, cityName, null, resolveNameLocale(cityName, triggerPlayer));
	}

	// 以指定姓氏与命名风格生成姓名（出生继承用），性别随机
	public static void generateAndSetName(ServerLevel level, Entity entity, String cityName,
			@Nullable String surname, NameLocale locale)
	{
		CompoundTag tag = entity.getPersistentData();
		String sex = RANDOM.nextBoolean() ? "male" : "female";

		// 查重：姓名是身份主键（档案文件名/索引键），英文池组合数远小于中文，必须避免撞名
		String pickedSurname = surname != null && !surname.isEmpty() ? surname : NpcNames.randomSurname(locale);
		Set<String> taken = takenNames(level, cityName);
		String givenName = NpcNames.randomGiven(locale, sex);
		for (int attempt = 0; attempt < 48 && taken.contains(NpcNames.format(locale, pickedSurname, givenName)); attempt++)
		{
			if (surname == null || surname.isEmpty()) pickedSurname = NpcNames.randomSurname(locale);
			givenName = NpcNames.randomGiven(locale, sex);
		}
		if (taken.contains(NpcNames.format(locale, pickedSurname, givenName)))
		{
			LOGGER.warn("NeoSim-Entity: name pool exhausted for city '{}', reuse {}",
					cityName, NpcNames.format(locale, pickedSurname, givenName));
		}

		tag.putString(KEY_NAME_LOCALE, locale.key());
		tag.putString(KEY_SURNAME, pickedSurname);
		tag.putString(KEY_GIVEN_NAME, givenName);
		tag.putString(KEY_FULL_NAME, NpcNames.format(locale, pickedSurname, givenName));
		tag.putString(KEY_SEX, sex);
	}

	// 解析命名风格：配置强制 zh/en 优先；否则用触发玩家语言；再退到城市最近一次玩家语言；最后中文
	public static NameLocale resolveNameLocale(String cityName, @Nullable UUID triggerPlayer)
	{
		NameLocale forced;
		try
		{
			forced = NameLocale.fromKey(Config.NPC_NAME_LOCALE.get());
		}
		catch (IllegalStateException ignored)
		{
			// 配置尚未加载（早期生成路径）→ 按 auto 处理
			forced = null;
		}
		if (forced != null) return forced;

		if (triggerPlayer != null)
		{
			NameLocale byPlayer = PlayerLocales.of(triggerPlayer);
			if (byPlayer != null) return byPlayer;
		}
		NameLocale byCity = PlayerLocales.ofCity(cityName);
		if (byCity != null) return byCity;

		// 城市未记录（玩家先上报语言、之后才入城）→ 取该城市在线玩家的语言
		NameLocale online = PlayerLocales.firstOnlineInCity(cityName);
		return online != null ? online : NameLocale.ZH;
	}

	// 城市内已占用的姓名（已加载实体 + 未加载档案文件名）
	private static Set<String> takenNames(ServerLevel level, String cityName)
	{
		Set<String> taken = new HashSet<>();
		if (cityName == null || cityName.isEmpty()) return taken;
		for (Entity npc : NpcRegistry.byCity(cityName))
		{
			taken.add(npc.getNpcName());
		}
		if (level != null)
		{
			for (String name : NpcData.listNpcNames(level, cityName))
			{
				taken.add(name);
			}
		}
		return taken;
	}

	// GUI出现时冻结NPC
	public boolean isFrozen()
	{
		return entityData.get(DATA_FROZEN);
	}

	public void setFrozen(boolean frozen)
	{
		entityData.set(DATA_FROZEN, frozen);
		if (frozen)
		{
			getNavigation().stop();

			goalSelector.getAvailableGoals().stream().toList()
					.forEach(w -> goalSelector.removeGoal(w.getGoal()));
		}
		else
		{
			// 解冻时重新注册AI
			registerGoals();

			// 仍在工作：重新裁剪成工作AI（registerGoals 是完整AI，会带回闲逛/社交）
			BlockPos site = getAssignedSite();
			if (site != null) assignToSite(site);
		}
	}

	// 声明冻结时面向玩家
	private net.minecraft.world.entity.LivingEntity faceTarget;

	// 玩家打开GUI时冻结，看向玩家
	public void freezeBy(UUID playerUUID)
	{
		guiOpeners.add(playerUUID);
		if (!isFrozen())
		{
			setFrozen(true);
		}

		// 记录目标玩家（多人触发时面向最后一个）
		Player player = level().getPlayerByUUID(playerUUID);
		if (player != null)
		{
			faceTarget = player;
		}
	}

	// 玩家关闭GUI时计数
	public void unfreezeBy(UUID playerUUID)
	{
		guiOpeners.remove(playerUUID);
		if (guiOpeners.isEmpty() && isFrozen())
		{
			setFrozen(false);
			faceTarget = null;
		}
	}

	// 用于修复玩家非正常退出（崩溃、断线）时GUI未正常关闭导致的NPC永久冻结问题
	public void cleanupStaleOpeners()
	{
		if (isFrozen() && !guiOpeners.isEmpty())
		{
			guiOpeners.removeIf(uuid -> {
				if (level().getServer() == null) return true;
				return level().getServer().getPlayerList().getPlayer(uuid) == null;
			});
			if (guiOpeners.isEmpty())
			{
				setFrozen(false);
				faceTarget = null;
			}
		}
	}

	// 渲染抬手动画（1.0=手抬到最高）
	public float getBuildAnim()
	{
		return entityData.get(DATA_BUILD_ANIM);
	}

	// 服务端
	public void setBuildAnim(float value)
	{
		entityData.set(DATA_BUILD_ANIM, Math.max(0.0F, Math.min(1.0F, value)));
	}

	// 客户端上一帧同步值
	public float getPrevBuildAnim()
	{
		return prevBuildAnim;
	}

	// 客户端每帧记录上一值，渲染时插值
	@Override
	public void tick()
	{
		if (level().isClientSide)
		{
			prevBuildAnim = getBuildAnim();
		}
		else
		{
			// 休息时间自动解除工地待命：NPC 可以自由走动（回家/在家附近溜达）
			if (stayPut && isRestingNow()) stayPut = false;

			if (tickCount % 100 == 0)
			{
				// 自愈检查：站点方块已不存在时解除工作状态（见 selfHealStaleSite）
				selfHealStaleSite();
			}
		}
		super.tick();
	}

	// 冻结时停止移动
	@Override
	protected void customServerAiStep()
	{
		if (isFrozen())
		{
			getNavigation().stop();
			if (faceTarget != null && faceTarget.isAlive())
			{
				// 每tick刷新目标
				getLookControl().setLookAt(faceTarget);
			}
			return;
		}
		super.customServerAiStep();
	}

	// （非死亡）受伤：血量由原版存档机制兜底，不再每击写 JSON 文件（D4 去抖）
	@Override
	public boolean hurt(DamageSource source, float amount)
	{
		return super.hurt(source, amount);
	}

	// 同步当前状态（长岁/入住/升级等）：走脏标记 + 合并窗口（周期 flush 落盘）
	public void syncToJson()
	{
		String npcName = getNpcName();
		String cityName = getCityName();
		if (npcName.isEmpty() || cityName.isEmpty()) return;
		NpcData.markDirty(this);
	}

	// 立即写盘（卸载/服务端停止前强制落盘，保证未加载 NPC 从文件恢复时状态最新）
	public void syncToJsonNow()
	{
		String npcName = getNpcName();
		String cityName = getCityName();
		if (npcName.isEmpty() || cityName.isEmpty()) return;

		if (level().getServer() != null && level().getServer().isDedicatedServer())
		{
			NpcData.save(this, cityName);
		}
		else if (level().getServer() != null)
		{
			String saveName = level().getServer().getWorldData().getLevelName();
			NpcData.save(this, cityName, saveName);
		}
	}

	// 死亡时删除文件并同步人口
	@Override
	public void die(DamageSource source)
	{
		// 死亡公告：按死因广播搞怪文案+年龄感慨
		if (level() instanceof ServerLevel serverLevel)
		{
			announceDeath(serverLevel, source);

			// 族谱清理：摘除死者+删其全部关系文件
			Genealogy.onDeath(serverLevel, this);
		}

		String npcName = getNpcName();
		String cityName = getCityName();

		// 死亡时清除临产目标缓存（防 BIRTH_TARGETS 残留）
		com.wenzai.neosim.life.ReproductionSystem.clearBirthTarget(npcName);

		if (!npcName.isEmpty() && !cityName.isEmpty())
		{
			if (level().getServer() != null && level().getServer().isDedicatedServer())
			{
				NpcData.delete(npcName, cityName);
			}
			else if (level().getServer() != null)
			{
				String saveName = level().getServer().getWorldData().getLevelName();
				NpcData.delete(npcName, cityName, saveName);
			}

			// 同步人口（内存值 -1）
			if (level() instanceof ServerLevel serverLevel)
			{
				short pop = Manage.getPopulation(serverLevel, cityName);
				ModSavedData.get(serverLevel).setPopulation(cityName, (short) Math.max(0, pop - 1), serverLevel);
			}
		}

		// 退房：空出生活点
		if (level() instanceof ServerLevel serverLevel)
		{
			CityLivingManager.releaseHome(serverLevel, this);
		}
		super.die(source);
	}

	// 死亡公告：按死因广播搞怪文案+年龄感慨
	private void announceDeath(ServerLevel level, DamageSource source)
	{
		String npcName = getNpcName();
		String cityName = getCityName();
		if (npcName.isEmpty() || cityName.isEmpty()) return;

		boolean oldAge = source.is(DamageTypes.GENERIC_KILL);
		Component cause;
		if (oldAge)
		{
			cause = LifeSystem.tpl(Config.ANNOUNCE_DEATH_CAUSE_OLD_AGE);
		}
		else if (source.is(DamageTypes.DROWN)) cause = LifeSystem.tpl(Config.ANNOUNCE_DEATH_CAUSE_DROWN);
		else if (source.is(DamageTypes.LAVA)) cause = LifeSystem.tpl(Config.ANNOUNCE_DEATH_CAUSE_LAVA);
		else if (source.is(DamageTypes.IN_WALL)) cause = LifeSystem.tpl(Config.ANNOUNCE_DEATH_CAUSE_SUFFOCATE);
		else if (source.is(DamageTypes.FALL) || source.is(DamageTypes.FALLING_BLOCK)) cause = LifeSystem.tpl(Config.ANNOUNCE_DEATH_CAUSE_FALL);
		else if (source.is(DamageTypes.STARVE)) cause = LifeSystem.tpl(Config.ANNOUNCE_DEATH_CAUSE_STARVE);
		else if (source.is(DamageTypes.ON_FIRE) || source.is(DamageTypes.IN_FIRE)) cause = LifeSystem.tpl(Config.ANNOUNCE_DEATH_CAUSE_FIRE);
		else if (source.is(DamageTypes.LIGHTNING_BOLT)) cause = LifeSystem.tpl(Config.ANNOUNCE_DEATH_CAUSE_LIGHTNING);
		else if (source.is(DamageTypes.CACTUS)) cause = LifeSystem.tpl(Config.ANNOUNCE_DEATH_CAUSE_CACTUS);
		else cause = LifeSystem.tpl(Config.ANNOUNCE_DEATH_CAUSE_OTHER);

		Component remark = oldAge
				? LifeSystem.tpl(Config.ANNOUNCE_DEATH_REMARK_OLD, getAge())
				: LifeSystem.tpl(Config.ANNOUNCE_DEATH_REMARK_YOUNG, getAge());

		LifeSystem.announce(level, cityName,
				LifeSystem.tpl(Config.ANNOUNCE_DEATH_TEMPLATE, npcName, cause, remark));
	}

	// 数据同步
	@Override
	public void readAdditionalSaveData(CompoundTag tag)
	{
		super.readAdditionalSaveData(tag);

		// 恢复皮肤
		if (tag.contains(KEY_SKIN))
		{
			setSkin(tag.getString(KEY_SKIN));
		}

		// 读档后把年龄同步到客户端（模型缩放判断用）
		this.entityData.set(DATA_AGE, (int) getAge());

		// 恢复NPC状态
		if (getPersistentData().contains(KEY_ASSIGNED_SITE_X))
		{
			BlockPos site = new BlockPos(
					getPersistentData().getInt(KEY_ASSIGNED_SITE_X),
					getPersistentData().getInt(KEY_ASSIGNED_SITE_Y),
					getPersistentData().getInt(KEY_ASSIGNED_SITE_Z));
			assignToSite(site);
		}

		// 恢复数据，确保客户端能渲染名字
		String name = getNpcName();
		if (!name.isEmpty())
		{
			setCustomName(Component.literal(name));
			setCustomNameVisible(true);
		}
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag)
	{
		super.addAdditionalSaveData(tag);
		tag.putString(KEY_SKIN, getSkin());
	}

	// 右键打开GUI
	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand)
	{
		if (level().isClientSide)
		{
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	// 行为
	@Override
	protected void registerGoals()
	{
		this.goalSelector.addGoal(0, new FloatGoal(this));

		// 安全AI放最高级（0）：敌对生物一律逃；玩家也逃，但工地待料时只躲怪不躲玩家（防乱走）
		// 合并为一个 AvoidEntityGoal，一次 8 格扫描按 predicate 分流，代替两个独立目标
		this.fleeHostileGoal = new AvoidEntityGoal<>(this, LivingEntity.class,
				e -> e.getType().getCategory() == MobCategory.MONSTER
						|| (e instanceof Player && !isPlayerAvoidBlocked()),
				8.0F, 0.6D, 0.8D, e -> true);
		this.goalSelector.addGoal(0, fleeHostileGoal);
		this.moveToSiteGoal = new NpcGoals.MoveToSiteGoal(this, 0.6D);
		this.goalSelector.addGoal(1, moveToSiteGoal);
		this.goalSelector.addGoal(2, new NpcGoals.GoHomeGoal(this, 0.5D));
		this.goalSelector.addGoal(2, new NpcGoals.StayHomeGoal(this, 0.5D));
		this.goalSelector.addGoal(3, new SocialGoal(this));
		this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.5D));
		this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 6.0F));
		this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
	}

	// 分配NPC
	public void assignToSite(BlockPos site)
	{
		getPersistentData().putInt(KEY_ASSIGNED_SITE_X, site.getX());
		getPersistentData().putInt(KEY_ASSIGNED_SITE_Y, site.getY());
		getPersistentData().putInt(KEY_ASSIGNED_SITE_Z, site.getZ());

		// 更新工作点缓存
		siteCacheValid = true;
		cachedAssignedSite = site;

		// 有工作：今日不休息；新岗位不继承待命状态
		setRestToday(false);
		this.stayPut = false;

		// 寻路目标尚未注册（如从NBT加载时）则先注册
		if (this.moveToSiteGoal == null)
		{
			this.registerGoals();
		}

		// 移除AI：保留赴工、夜晚回家、游泳、看向玩家、环视，以及敌对生物逃离
		// 夜晚回家必须留着：休息时间赴工目标停掉后，工人得能自己走回家（不许被传送）
		this.goalSelector.getAvailableGoals().stream()
				.filter(w -> !(w.getGoal() instanceof NpcGoals.MoveToSiteGoal)
						  && !(w.getGoal() instanceof NpcGoals.GoHomeGoal)
						  && !(w.getGoal() instanceof FloatGoal)
						  && !(w.getGoal() instanceof LookAtPlayerGoal)
						  && !(w.getGoal() instanceof RandomLookAroundGoal)
						  && w.getGoal() != this.fleeHostileGoal)
				.toList()
				.forEach(w -> goalSelector.removeGoal(w.getGoal()));

		// 赴工一律交给寻路：能走到就步行过去，不再按固定 320 格传送
		// 够不到的情况（超出 FOLLOW_RANGE，或沿途区块未加载导致无路）
		// 交给 MoveToSiteGoal 的兜底逻辑，那里会先确认确实无路可走才传送
		this.moveToSiteGoal.setTarget(site);
		this.currentMoveTarget = site;
	}

	// 设置寻路目标；目标不变时不重复设置
	public void setMoveTarget(BlockPos pos)
	{
		if (pos == null || pos.equals(currentMoveTarget)) return;
		this.currentMoveTarget = pos;
		if (this.moveToSiteGoal != null)
		{
			this.moveToSiteGoal.setTarget(pos);
		}
	}

	// 夜晚入职清空寻路目标
	public void clearMoveTarget()
	{
		this.currentMoveTarget = null;
		if (this.moveToSiteGoal != null)
		{
			this.moveToSiteGoal.setTarget(null);
		}
		getNavigation().stop();
	}

	// 工地待料待命：停住不动。安全AI不摘（最高级，怪物照样逃）；只屏蔽"躲玩家"这一条
	// 休息时间不算待命：不拦寻路，NPC 可以自由走动
	public void setStayPut(boolean v)
	{
		this.stayPut = v;
		if (v && !isRestingNow()) getNavigation().stop();
	}

	public boolean isStayPut()
	{
		return stayPut;
	}

	// 工地待料且非休息时间：玩家不再触发逃离（避免工人被送料的玩家吓得乱走）
	private boolean isPlayerAvoidBlocked()
	{
		return stayPut && !isRestingNow();
	}

	// 解雇NPC，恢复AI
	public void releaseFromSite()
	{
		this.stayPut = false;
		getPersistentData().remove(KEY_ASSIGNED_SITE_X);
		getPersistentData().remove(KEY_ASSIGNED_SITE_Y);
		getPersistentData().remove(KEY_ASSIGNED_SITE_Z);

		// 清工作点缓存
		siteCacheValid = true;
		cachedAssignedSite = null;
		this.currentMoveTarget = null;

		// 清空现有目标，重新注册完整AI
		this.goalSelector.getAvailableGoals().stream().toList()
				.forEach(w -> goalSelector.removeGoal(w.getGoal()));
		this.registerGoals();
	}

	// 当前工作站点；无工作时返回null（字段缓存，assignToSite/releaseFromSite 时失效）
	@Nullable
	public BlockPos getAssignedSite()
	{
		if (!siteCacheValid)
		{
			siteCacheValid = true;
			cachedAssignedSite = null;
			CompoundTag tag = getPersistentData();
			if (tag.contains(KEY_ASSIGNED_SITE_X))
			{
				cachedAssignedSite = new BlockPos(tag.getInt(KEY_ASSIGNED_SITE_X),
						tag.getInt(KEY_ASSIGNED_SITE_Y), tag.getInt(KEY_ASSIGNED_SITE_Z));
			}
		}
		return cachedAssignedSite;
	}

	// 全服按名字查找已加载的NPC。解雇/释放必须全图搜，限半径会在工人离家/远走时漏掉
	// 走 NpcRegistry 索引 O(1)（C1）
	@Nullable
	public static Entity findByNpcName(ServerLevel level, String name)
	{
		return NpcRegistry.findByName(name);
	}

	// ---- 维度隔离 ----
	// NPC 绑定城市与住宅坐标，跨维度后回家、寻路、寻站、按城市索引全部失效，
	// 所以这里三道口全堵：传送门、直接 changeDimension、指令传送

	// 下界门/末地门：vanilla 在 handlePortal 里先问 canChangeDimensions
	@Override
	public boolean canChangeDimensions(Level oldLevel, Level newLevel)
	{
		if (oldLevel.dimension() != newLevel.dimension())
		{
			LOGGER.warn("NeoSim-NPC: '{}' blocked from portal travel {} -> {}",
				getNpcName(), oldLevel.dimension().location(), newLevel.dimension().location());
			return false;
		}
		return super.canChangeDimensions(oldLevel, newLevel);
	}

	// 兜底：其它模组或代码直接调 changeDimension 时同样拒绝
	// 返回类型必须写全限定名：本类就叫 Entity，直接写 Entity 会解析成 com.wenzai.neosim.npc.Entity
	@Nullable
	@Override
	public net.minecraft.world.entity.Entity changeDimension(DimensionTransition transition)
	{
		if (transition.newLevel().dimension() != level().dimension())
		{
			LOGGER.warn("NeoSim-NPC: '{}' blocked from dimension change {} -> {}",
				getNpcName(), level().dimension().location(), transition.newLevel().dimension().location());
			return null;
		}
		return super.changeDimension(transition);
	}

	// 指令 /tp 与 /execute in 走的是 teleportTo，不经过 changeDimension，必须单独拦
	@Override
	public boolean teleportTo(ServerLevel level, double x, double y, double z,
		Set<RelativeMovement> relativeMovements, float yRot, float xRot)
	{
		if (level != level())
		{
			LOGGER.warn("NeoSim-NPC: '{}' blocked from cross-dimension teleport to {}",
				getNpcName(), level.dimension().location());
			return false;
		}
		return super.teleportTo(level, x, y, z, relativeMovements, yRot, xRot);
	}

	// ---- NpcRegistry 索引维护（C1）----

	@Override
	public void onAddedToLevel()
	{
		super.onAddedToLevel();
		if (level() != null && !level().isClientSide)
		{
			NpcRegistry.register(this);
		}
	}

	@Override
	public void onRemovedFromLevel()
	{
		super.onRemovedFromLevel();
		if (level() != null && !level().isClientSide)
		{
			NpcRegistry.unregister(this);
		}
	}

	// 自愈：站点方块已不存在（盒子被破坏/爆炸/命令删除）时，解除工作状态恢复完整AI
	// 解雇路径只能覆盖“已加载”的NPC；区块未加载或离得远的NPC由这里兜底，
	// 否则 NPC 会一直保持工作AI并反复走向已不存在的站点
	private void selfHealStaleSite()
	{
		if (isFrozen()) return;
		if (!hasJob()) return;
		BlockPos site = getAssignedSite();
		if (site == null) return;

		// 站点区块未加载：跳过，等加载后再判定（避免把未加载误判成方块消失）
		if (!level().isLoaded(site)) return;

		Block b = level().getBlockState(site).getBlock();
		boolean validSite = b instanceof FarmingBox
				|| b instanceof MiningBox
				|| b instanceof DeliveryBox
				|| b instanceof BuildingConstructor;
		if (validSite) return;

		LOGGER.info("NeoSim-Entity: self-heal release '{}' at site {} - block no longer exists", getNpcName(), site);
		releaseFromSite();
		setBuildAnim(0.0F);
		setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		NeoSim.WORKER_MAP.remove(site);
	}

	// 属性
	public static AttributeSupplier.Builder createAttributes()
	{
		// 使用默认值，因为 EntityAttributeCreationEvent 在配置加载之前触发
		// 如需运行时修改属性，应在实体生成后通过其他方式覆盖
		// FOLLOW_RANGE 同时决定三件事（见 PathNavigation / PathFinder）：
		//   1. 寻路探索区域半径 FOLLOW_RANGE + 8 格；
		//   2. 单条路径的最大行走距离（PathFinder 用 maxRange 硬截断，超出的节点不展开）；
		//   3. 搜索节点预算 FOLLOW_RANGE × 16
		// 原版村民是 48；放大到 128 是为了让市民能跨城步行通勤而不是靠传送
		// 注意：节点预算在 PathNavigation 构造时读取一次，运行时改属性不会生效
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0D)
				.add(Attributes.MOVEMENT_SPEED, 0.5D)
				.add(Attributes.FOLLOW_RANGE, 128.0D);
	}

	public static void register(IEventBus eventBus)
	{
		ENTITY_TYPES.register(eventBus);
	}
}

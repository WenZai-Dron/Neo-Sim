package com.wenzai.neosim.npc;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.storage.ModSavedData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Random;
import java.util.function.Supplier;

public class Entity extends PathfinderMob
{
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
        setCustomNameVisible(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder)
    {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, "");
        builder.define(DATA_FROZEN, false);
    }

    // 获取姓名
    public String getNpcName()
    {
        return Name.of(getPersistentData()).get();
    }

    // 获取姓
    public String getNpcSurname()
    {
        return Name.of(getPersistentData()).getSurname();
    }

    // 获取名
    public String getNpcGivenName()
    {
        return Name.of(getPersistentData()).getGivenName();
    }

    // 设置姓名，同步文件
    public void setNpcName(String surname, String givenName)
    {
        String oldName = getNpcName();
        Name nameObj = Name.of(getPersistentData());
        nameObj.setSurname(surname);
        nameObj.setGivenName(givenName);
        String fullName = surname + givenName;
        nameObj.set(fullName);
        setCustomName(Component.literal(fullName));
        setCustomNameVisible(true);

        // 如果名字改变，删除旧文件并保存新文件
        if (!oldName.isEmpty() && !oldName.equals(fullName) && !fullName.isEmpty())
        {
            String cityName = getCityName();
            if (!cityName.isEmpty() && level().getServer() != null)
            {
                if (level().getServer().isDedicatedServer())
                {
                    npcData.delete(oldName, cityName);
                    npcData.save(this, cityName);
                }
                else
                {
                    String saveName = level().getServer().getWorldData().getLevelName();
                    npcData.delete(oldName, cityName, saveName);
                    npcData.save(this, cityName, saveName);
                }
            }
        }
    }

    // 设置姓名，同时同步以在头顶渲染
    public void setNpcName(String name)
    {
        String oldName = getNpcName();
        Name.of(getPersistentData()).set(name);
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

        // 如果名字改变，删除旧JSON并保存新JSON（仅服务端）
        if (!oldName.isEmpty() && !oldName.equals(name) && !name.isEmpty())
        {
            String cityName = getCityName();
            if (!cityName.isEmpty() && level().getServer() != null)
            {
                if (level().getServer().isDedicatedServer())
                {
                    npcData.delete(oldName, cityName);
                    npcData.save(this, cityName);
                }
                else
                {
                    String saveName = level().getServer().getWorldData().getLevelName();
                    npcData.delete(oldName, cityName, saveName);
                    npcData.save(this, cityName, saveName);
                }
            }
        }
    }

    // 获取性别
    public String getSex()
    {
        return Name.of(getPersistentData()).getSex();
    }

    // 设置性别
    public void setSex(String sex)
    {
        Name.of(getPersistentData()).setSex(sex);
    }

    private static final String KEY_CITY_NAME = "nsnpc_cityName";
    private static final String KEY_SKIN = "nsnpc_skin";

    private static final EntityDataAccessor<String> DATA_SKIN =
            SynchedEntityData.defineId(Entity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Boolean> DATA_FROZEN =
            SynchedEntityData.defineId(Entity.class, EntityDataSerializers.BOOLEAN);

    private static final Random RANDOM = new Random();

    private static final String[] MALE_SKINS = {
            "achr1d.png", "daycrime.png", "gohanssj.png", "kazvran.png", "nocqnameponer.png",
            "peaq.png", "poishii.png", "radwool.png", "theezku.png", "whuz.png"
    };

    private static final String[] FEMALE_SKINS = {
            "anya03.png", "b0mbies.png", "blazerhack.png", "fearlicia.png", "kajikasu.png",
            "khristinatina.png", "lunatique.png", "mewlee.png", "osukaari.png", "prueli.png"
    };

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
        }
    }

    // 冻结时跳过AI
    @Override
    protected void customServerAiStep()
    {
        if (isFrozen())
        {
            getNavigation().stop();
            return;
        }
        super.customServerAiStep();
    }

    // （非死亡）受伤时同步血量
    @Override
    public boolean hurt(DamageSource source, float amount)
    {
        boolean result = super.hurt(source, amount);
        if (result && !isDeadOrDying()) syncToJson();
        return result;
    }

    // 同步当前状态
    private void syncToJson()
    {
        String npcName = getNpcName();
        String cityName = getCityName();
        if (npcName.isEmpty() || cityName.isEmpty()) return;

        if (level().getServer() != null && level().getServer().isDedicatedServer())
        {
            npcData.save(this, cityName);
        }
        else if (level().getServer() != null)
        {
            String saveName = level().getServer().getWorldData().getLevelName();
            npcData.save(this, cityName, saveName);
        }
    }

    // 死亡时删除文件并同步人口
    @Override
    public void die(DamageSource source)
    {
        String npcName = getNpcName();
        String cityName = getCityName();
        if (!npcName.isEmpty() && !cityName.isEmpty())
        {
            if (level().getServer() != null && level().getServer().isDedicatedServer())
            {
                npcData.delete(npcName, cityName);
            }
            else if (level().getServer() != null)
            {
                String saveName = level().getServer().getWorldData().getLevelName();
                npcData.delete(npcName, cityName, saveName);
            }

            // 同步人口
            if (level() instanceof ServerLevel serverLevel)
            {
                short pop = Manage.getPopulation(serverLevel, cityName);
                ModSavedData.get(serverLevel).setPopulation(pop, serverLevel);
            }
        }
        super.die(source);
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

    // 右键打开GUI，同时冻结NPC使其停止移动
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand)
    {
        if (level().isClientSide)
        {
            return InteractionResult.SUCCESS;
        }
        setFrozen(true);
        return InteractionResult.PASS;
    }

    // 行为
    @Override
    protected void registerGoals()
    {
        // 漫游目标
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Player.class, 8.0F, 0.5D, 0.5D));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.5D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    // 属性
    public static AttributeSupplier.Builder createAttributes()
    {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.5D)
                .add(Attributes.FOLLOW_RANGE, 16.0D);
    }

    public static void register(IEventBus eventBus)
    {
        ENTITY_TYPES.register(eventBus);
    }
}

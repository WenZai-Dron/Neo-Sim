package com.wenzai.neosim.network;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.building.ConstructionEngine;
import com.wenzai.neosim.client.preview.PreviewState;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.schematic.SchematicRegistry;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import com.wenzai.neosim.storage.SimData;
import com.wenzai.neosim.util.RateLimiter;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public class ClientToServerPayloads
{
    private ClientToServerPayloads() {}

    // 发送mode及NPC的GUI操作
    public record UpdatePayload(byte mode, int npcEntityId, String npcNewSurname, String npcNewGivenName, String npcNewSkin) implements CustomPacketPayload
    {
        // mode更新构造
        public UpdatePayload(byte mode)
        {
            this(mode, 0, "", "", "");
        }

        // NPC更新构造
        public UpdatePayload(int npcEntityId, String npcNewSurname, String npcNewGivenName, String npcNewSkin)
        {
            this((byte) 0, npcEntityId, npcNewSurname, npcNewGivenName, npcNewSkin);
        }

        public static final Type<UpdatePayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "update"));

        public static final StreamCodec<ByteBuf, UpdatePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BYTE,
                        UpdatePayload::mode,
                        ByteBufCodecs.VAR_INT,
                        UpdatePayload::npcEntityId,
                        ByteBufCodecs.STRING_UTF8,
                        UpdatePayload::npcNewSurname,
                        ByteBufCodecs.STRING_UTF8,
                        UpdatePayload::npcNewGivenName,
                        ByteBufCodecs.STRING_UTF8,
                        UpdatePayload::npcNewSkin,
                        UpdatePayload::new
                );

        public static void handle(UpdatePayload payload, IPayloadContext context)
        {
            context.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) context.player();

                // NPC更新
                if (payload.npcEntityId() > 0)
                {
                    if (player.level().getEntity(payload.npcEntityId()) instanceof Entity npc)
                    {
                        boolean needSave = false;

                        // 重命名（姓+名）
                        if (!payload.npcNewSurname().isEmpty() && !payload.npcNewGivenName().isEmpty())
                        {
                            npc.setNpcName(payload.npcNewSurname(), payload.npcNewGivenName());
                            needSave = true;
                        }

                        // 换皮肤
                        if (!payload.npcNewSkin().isEmpty())
                        {
                            npc.setSkin(payload.npcNewSkin());
                            needSave = true;
                        }

                        // 保存NBT中的姓和名到文件
                        if (needSave)
                        {
                            String cityName = npc.getCityName();
                            if (!cityName.isEmpty())
                            {
                                if (player.serverLevel().getServer().isDedicatedServer())
                                {
                                    NpcData.save(npc, cityName);
                                }
                                else
                                {
                                    String saveName = player.serverLevel().getServer().getWorldData().getLevelName();
                                    NpcData.save(npc, cityName, saveName);
                                }
                            }
                        }
                        NeoSim.LOGGER.debug("NeoSim-Update(C→S): NPC entityId={}, surname={}, givenName={}, skin={}",
                                payload.npcEntityId(), payload.npcNewSurname(), payload.npcNewGivenName(), payload.npcNewSkin());
                    }
                    else
                    {
                        NeoSim.LOGGER.warn("NeoSim-Update: NPC not found, entityId={}", payload.npcEntityId());
                    }
                }
                else
                {
                    // mode更新
                    ModSavedData savedData = ModSavedData.get(player.serverLevel());
                    SimData updated = savedData.getData().withMode(payload.mode());
                    savedData.setData(updated, player.serverLevel());
                    NeoSim.LOGGER.debug("NeoSim-handle(C→S): mode={}", payload.mode());
                }
            }).exceptionally(e -> {
                NeoSim.LOGGER.error("NeoSim-Update: Fail", e);
                return null;
            });
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type()
        {
            return TYPE;
        }
    }

    // 通知冻结/解冻NPC
    public record FreezeNpcPayload(int entityId, boolean frozen) implements CustomPacketPayload
    {
        public static final Type<FreezeNpcPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "freeze_npc"));

        public static final StreamCodec<ByteBuf, FreezeNpcPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT,
                        FreezeNpcPayload::entityId,
                        ByteBufCodecs.BOOL,
                        FreezeNpcPayload::frozen,
                        FreezeNpcPayload::new
                );

        public static void handle(FreezeNpcPayload payload, IPayloadContext context)
        {
            context.enqueueWork(() -> {
                if (context.player().level().getEntity(payload.entityId()) instanceof Entity npc)
                {
                    java.util.UUID playerUUID = context.player().getUUID();
                    if (payload.frozen())
                    {
                        npc.freezeBy(playerUUID);
                    }
                    else
                    {
                        npc.unfreezeBy(playerUUID);
                    }
                    NeoSim.LOGGER.debug("NeoSim-FreezeNpc: entityId={}, frozen={}, player={}, openers={}",
                            payload.entityId(), payload.frozen(), playerUUID, npc.isFrozen());
                }
                else
                {
                    NeoSim.LOGGER.warn("NeoSim-FreezeNpc: NPC not found, entityId={}", payload.entityId());
                }
            }).exceptionally(e -> {
                NeoSim.LOGGER.error("NeoSim-FreezeNpc: Fail", e);
                return null;
            });
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type()
        {
            return TYPE;
        }
    }

    // 灵魂出窍结束，传送回原位置
    public record SoulReturnPayload(double x, double y, double z, float yaw, float pitch) implements CustomPacketPayload
    {
        public static final Type<SoulReturnPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "soul_return"));

        public static final StreamCodec<ByteBuf, SoulReturnPayload> STREAM_CODEC =
                StreamCodec.ofMember(SoulReturnPayload::write, SoulReturnPayload::new);

        private SoulReturnPayload(ByteBuf buf)
        {
            this(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat());
        }

        private void write(ByteBuf buf)
        {
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeFloat(yaw);
            buf.writeFloat(pitch);
        }

        public static void handle(SoulReturnPayload payload, IPayloadContext context)
        {
            context.enqueueWork(() ->
            {
                if (context.player() instanceof ServerPlayer player && player.isAlive())
                {
                    // 只允许传送回自己的原位置（距离有上限，防止被恶意利用）（未作明确规定）
                    double distSqr = player.distanceToSqr(payload.x(), payload.y(), payload.z());
                    if (distSqr > 512.0 * 512.0) return;
                    double y = payload.y();
                    if (y < player.serverLevel().getMinBuildHeight()
                            || y > player.serverLevel().getMaxBuildHeight()) return;

                    player.resetFallDistance();
                    player.teleportTo(player.serverLevel(),
                            payload.x(), payload.y(), payload.z(),
                            java.util.Collections.emptySet(), payload.yaw(), payload.pitch());
                }
            }).exceptionally(e -> {
                NeoSim.LOGGER.error("NeoSim-SoulReturn: Fail", e);
                return null;
            });
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type()
        {
            return TYPE;
        }
    }

    // 确认建筑放置
    public record ConfirmPlacementPayload(String schematicName,
                                          BlockPos origin,
                                          Rotation rotation,
                                          Mirror mirror,
                                          BlockPos constructorPos,
                                          net.minecraft.core.Direction facing) implements CustomPacketPayload
    {
        public static final Type<ConfirmPlacementPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "confirm_placement"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ConfirmPlacementPayload> STREAM_CODEC =
                StreamCodec.ofMember(ConfirmPlacementPayload::write, ConfirmPlacementPayload::new);

        private ConfirmPlacementPayload(RegistryFriendlyByteBuf buf)
        {
            this(buf.readUtf(), buf.readBlockPos(),
                    buf.readEnum(Rotation.class), buf.readEnum(Mirror.class),
                    buf.readBlockPos(), readFacing(buf));
        }

        private void write(RegistryFriendlyByteBuf buf)
        {
            buf.writeUtf(schematicName);
            buf.writeBlockPos(origin);
            buf.writeEnum(rotation);
            buf.writeEnum(mirror);
            buf.writeBlockPos(constructorPos);
            buf.writeByte(facing != null ? facing.get3DDataValue() : -1);
        }

        // 方向（可能为null，定为-1）
        private static net.minecraft.core.Direction readFacing(RegistryFriendlyByteBuf buf)
        {
            int f = buf.readByte();
            return f >= 0 ? net.minecraft.core.Direction.from3DDataValue(f) : null;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        // 服务端处理
        public static void handle(ConfirmPlacementPayload payload, IPayloadContext ctx)
        {
            ctx.enqueueWork(() ->
            {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !player.isAlive()) return;
                ServerLevel level = player.serverLevel();

                // 1秒内只能确认一次：防止刷包/重复放置
                if (!RateLimiter.check(player.getUUID(), "confirm_placement", 1000))
                {
                    player.displayClientMessage(
                            Component.literal("§cPlease wait before confirming again."), true);
                    return;
                }

                // 安全校验
                if (!isValidBlueprintName(payload.schematicName)) return;
                if (!isWithinBuildHeight(level, payload.origin)) return;
                if (!isWithinRange(player, payload.origin, 50)) return;

                var schematic = SchematicRegistry.getInstance().get(payload.schematicName);
                if (schematic == null) return;

                PreviewState state = new PreviewState();
                state.setSchematic(schematic);
                state.setOrigin(payload.origin);
                state.setFacing(payload.facing);

                // 恢复旋转/镜像
                state.setRotation(payload.rotation);
                state.setMirror(payload.mirror);

                var building = ConstructionEngine.createBuilding(schematic, state, level,
                        player.getName().getString(), payload.constructorPos);
                if (building == null)
                {
                    player.displayClientMessage(
                            Component.literal("§cCannot place: area overlaps an existing building."), false);
                }
            });
        }

        private static boolean isValidBlueprintName(String name)
        {
            return name != null && !name.contains("..") && !name.contains("/") && !name.contains("\\");
        }

        private static boolean isWithinBuildHeight(ServerLevel level, BlockPos pos)
        {
            return pos.getY() >= level.getMinBuildHeight() && pos.getY() <= level.getMaxBuildHeight();
        }

        private static boolean isWithinRange(ServerPlayer player, BlockPos pos, int maxDist)
        {
            return player.blockPosition().distSqr(pos) <= maxDist * maxDist;
        }
    }
}

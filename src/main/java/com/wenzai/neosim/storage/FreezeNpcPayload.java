// 客户端→服务端：通知服务端冻结/解冻NPC

package com.wenzai.neosim.storage;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.npc.Entity;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

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

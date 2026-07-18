// 服务端→客户端

package com.wenzai.neosim.storage;

import com.wenzai.neosim.NeoSim;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record SyncDataPayload(SimData data) implements CustomPacketPayload
{
    public static final Type<SyncDataPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "sync_data"));

    public static final StreamCodec<ByteBuf, SyncDataPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BYTE,    p -> p.data.mode(),
                    ByteBufCodecs.SHORT,   p -> p.data.population(),
                    ByteBufCodecs.VAR_INT, p -> p.data.dayOfWeek(),
                    ByteBufCodecs.VAR_INT, p -> p.data.day(),
                    ByteBufCodecs.DOUBLE,  p -> p.data.credit(),
                    (mode, population, dayOfWeek, day, credit) ->
                            new SyncDataPayload(new SimData(mode, population, dayOfWeek, day, credit))
            );

    public static void handle(SyncDataPayload payload, IPayloadContext context)
    {
        context.enqueueWork(() -> {
            ClientDataHolder.getInstance().updateData(payload.data());
            NeoSim.LOGGER.debug("NeoSim-SyncData(S→C): {}", payload.data());
        }).exceptionally(e -> {
            NeoSim.LOGGER.error("NeoSim-SyncDataPayload: Fail", e);
            return null;
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }
}

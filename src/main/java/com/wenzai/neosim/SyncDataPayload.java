// 双向网络包

package com.wenzai.neosim;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record SyncDataPayload(SimData data) implements CustomPacketPayload
{
    public static final Type<SyncDataPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "sync_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncDataPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT,  p -> p.data.mode(),
                    ByteBufCodecs.INT,  p -> p.data.singleOrMulti(),
                    ByteBufCodecs.INT,  p -> p.data.population(),
                    ByteBufCodecs.INT,  p -> p.data.dayOfWeek(),
                    ByteBufCodecs.INT,  p -> p.data.day(),
                    ByteBufCodecs.DOUBLE, p -> p.data.credit(),
                    (mode, som, pop, dow, day, credit) ->
                            new SyncDataPayload(new SimData(mode, som, pop, dow, day, credit))
            );

    public static void handle(SyncDataPayload payload, IPayloadContext context)
    {
        if (context.flow().isClientbound())
        {
            // 服务端 → 客户端：全量更新客户端缓存
            context.enqueueWork(() -> {
                ClientDataHolder.getInstance().updateData(payload.data());
                NeoSim.LOGGER.info("NeoSim-SyncData(S→C): {}", payload.data());
            });
        }
        else
        {
            // 客户端 → 服务端：从当前数据出发，只更新 mode 和 singleOrMulti
            context.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) context.player();
                SimData d = payload.data();
                ModSavedData savedData = ModSavedData.get(player.serverLevel());
                SimData updated = savedData.getData()
                        .withMode(d.mode())
                        .withSingleOrMulti(d.singleOrMulti());
                savedData.setData(updated, player.serverLevel());
                NeoSim.LOGGER.info("NeoSim-SyncData(C→S): mode={}, singleOrMulti={}", d.mode(), d.singleOrMulti());
            });
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }
}

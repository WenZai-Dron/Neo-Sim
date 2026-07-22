// 服务端→客户端：通知客户端打开Run或City

package com.wenzai.neosim.storage;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.NeoSimClient;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record OpenGuiPayload(GuiType guiType) implements CustomPacketPayload
{
    public enum GuiType
    {
        RUN,
        CITY
    }

    public static final Type<OpenGuiPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "open_gui"));

    public static final StreamCodec<ByteBuf, OpenGuiPayload> STREAM_CODEC = new StreamCodec<>()
    {
        @Override
        public @NotNull OpenGuiPayload decode(@NotNull ByteBuf buf)
        {
            return new OpenGuiPayload(GuiType.values()[buf.readByte()]);
        }

        @Override
        public void encode(@NotNull ByteBuf buf, @NotNull OpenGuiPayload payload)
        {
            buf.writeByte(payload.guiType().ordinal());
        }
    };

    public static void handle(OpenGuiPayload payload, IPayloadContext context)
    {
        context.enqueueWork(() -> {
            NeoSimClient.scheduleOpenGui(payload.guiType());
            NeoSim.LOGGER.debug("NeoSim-OpenGuiPayload: Success, type={}", payload.guiType());
        }).exceptionally(e -> {
            NeoSim.LOGGER.error("NeoSim-OpenGuiPayload: Fail", e);
            return null;
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }
}

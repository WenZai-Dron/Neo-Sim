// 客户端→服务端：发送 mode 及 NPC GUI 操作（重命名、换皮肤）

package com.wenzai.neosim.storage;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.npcData;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record UpdatePayload(byte mode, int npcEntityId, String npcNewSurname, String npcNewGivenName, String npcNewSkin) implements CustomPacketPayload
{
    // 纯mode更新构造
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

                    // 重命名（姓 + 名）
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
                                npcData.save(npc, cityName);
                            }
                            else
                            {
                                String saveName = player.serverLevel().getServer().getWorldData().getLevelName();
                                npcData.save(npc, cityName, saveName);
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

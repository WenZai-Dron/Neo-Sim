package com.wenzai.neosim.network;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.block.*;
import com.wenzai.neosim.building.ConstructionEngine;
import com.wenzai.neosim.building.ControlBoxPersistence;
import com.wenzai.neosim.life.Genealogy;
import com.wenzai.neosim.npc.CityLivingManager;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.schematic.BuildingType;
import com.wenzai.neosim.schematic.PreviewState;
import com.wenzai.neosim.schematic.SchematicRegistry;
import com.wenzai.neosim.storage.*;
import com.wenzai.neosim.util.JsonUtil;
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
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
					// 归属校验（5.1）：只允许修改"自己城市"且 64 格内的 NPC
					String myCity = CityManager.getCity(player.getUUID());
					net.minecraft.world.entity.Entity target = player.level().getEntity(payload.npcEntityId());
					if (!(target instanceof Entity npc))
					{
						NeoSim.LOGGER.warn("NeoSim-Update: NPC not found, entityId={}", payload.npcEntityId());
						return;
					}
					if (myCity.isEmpty() || !myCity.equals(npc.getCityName())
							|| player.distanceToSqr(npc) > 64.0 * 64.0)
					{
						NeoSim.LOGGER.warn("NeoSim-Update: {} denied modifying NPC {} (city mismatch or too far)",
								player.getName().getString(), npc.getNpcName());
						return;
					}

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
					// 5.2：限流（1 秒一次）+ 只允许传送回原位置附近（64 格内），防止恶意瞬移
					if (!JsonUtil.check(player.getUUID(), "soul_return", 1000)) return;
					double distSqr = player.distanceToSqr(payload.x(), payload.y(), payload.z());
					if (distSqr > 64.0 * 64.0) return;
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
				if (!JsonUtil.check(player.getUUID(), "confirm_placement", 1000))
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

	// 工作盒选择页确认
	public record WorkBoxApplyPayload(byte kind, BlockPos boxPos, int discards, String farmCsv) implements CustomPacketPayload
	{
		public static final Type<WorkBoxApplyPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "workbox_apply"));

		public static final StreamCodec<ByteBuf, WorkBoxApplyPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.BYTE,
						WorkBoxApplyPayload::kind,
						BlockPos.STREAM_CODEC,
						WorkBoxApplyPayload::boxPos,
						ByteBufCodecs.VAR_INT,
						WorkBoxApplyPayload::discards,
						ByteBufCodecs.STRING_UTF8,
						WorkBoxApplyPayload::farmCsv,
						WorkBoxApplyPayload::new
				);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }

		public static void handle(WorkBoxApplyPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				ServerPlayer player = (ServerPlayer) context.player();
				if (player == null || !player.isAlive()) return;
				ServerLevel level = player.serverLevel();

				PlotTask task = WorkPlotEngine.findTask(payload.boxPos());
				if (task != null)
				{
					if (payload.kind() == 0)
					{
						if (task instanceof FarmTask ft)
						{
							ft.applyFarmCsv(payload.farmCsv());
							WorkPlotEngine.saveAll(level);
							NeoSim.LOGGER.info("NeoSim-WorkBoxApply: farm {} <- {}", payload.boxPos(), payload.farmCsv());
						}
					}
					else if (task instanceof MineTask mt)
					{
						mt.setDiscards(payload.discards());
						WorkPlotEngine.saveAll(level);
						NeoSim.LOGGER.info("NeoSim-WorkBoxApply: mine {} <- {}", payload.boxPos(), payload.discards());
					}
					return;
				}

				// 无任务：设置仍写入记录，绑定后重建任务时生效（服务端权威取玩家城市）
				String city = CityManager.getCity(player);
				if (city.isEmpty())
				{
					NeoSim.LOGGER.warn("NeoSim-WorkBoxApply: no task & no active city at {}", payload.boxPos());
					return;
				}
				WorkBoxPersistence.WorkBoxRecord rec = WorkBoxPersistence.findRecord(level, city, payload.boxPos());
				if (rec == null)
				{
					NeoSim.LOGGER.warn("NeoSim-WorkBoxApply: no record at {} in city {}", payload.boxPos(), city);
					return;
				}
				if (payload.kind() == 0)
				{
					rec = rec.withFarmType(FarmTask.normalizeFarmCsv(payload.farmCsv()));
				}
				else
				{
					rec = rec.withDiscards(payload.discards());
				}
				WorkBoxPersistence.updateRecord(level, city, rec);
				NeoSim.LOGGER.info("NeoSim-WorkBoxApply: record {} <- {}",
						payload.boxPos(), payload.kind() == 0 ? payload.farmCsv() : payload.discards());
			});
		}
	}

	// 控制箱管理住户：驱逐单个(1)/清空全部(2)/安排入住(3)
	public record ControlBoxManagePayload(BlockPos boxPos, byte action, String targetName) implements CustomPacketPayload
	{
		public static final Type<ControlBoxManagePayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "control_box_manage"));

		public static final StreamCodec<ByteBuf, ControlBoxManagePayload> STREAM_CODEC =
				StreamCodec.composite(
						BlockPos.STREAM_CODEC,
						ControlBoxManagePayload::boxPos,
						ByteBufCodecs.BYTE,
						ControlBoxManagePayload::action,
						ByteBufCodecs.STRING_UTF8,
						ControlBoxManagePayload::targetName,
						ControlBoxManagePayload::new
				);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }

		public static void handle(ControlBoxManagePayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				ServerPlayer player = (ServerPlayer) context.player();
				if (player == null || !player.isAlive()) return;
				ServerLevel level = player.serverLevel();

				// 1秒限流防刷包
				if (!JsonUtil.check(player.getUUID(), "control_box_manage", 1000)) return;

				// 玩家所属城市（服务端权威，不用 getActiveCityName）
				String playerName = player.getName().getString();
				String city = level.getServer().isDedicatedServer()
						? FileCreater.findPlayerCity(playerName)
						: FileCreater.findPlayerCity(level.getServer().getWorldData().getLevelName(), playerName);
				if (city.isEmpty())
				{
					NeoSim.LOGGER.warn("NeoSim-ControlBoxManage: player '{}' has no city", playerName);
					return;
				}

				ControlBoxPersistence.ControlBoxRecord rec = ControlBoxPersistence.findRecord(level, city, payload.boxPos());
				if (rec == null)
				{
					NeoSim.LOGGER.warn("NeoSim-ControlBoxManage: no record at {} in city '{}'", payload.boxPos(), city);
					return;
				}

				// 仅住宅可管理（非住宅双保险）
				var schematic = SchematicRegistry.getInstance().get(rec.schematicName());
				if (schematic == null || schematic.getType() != BuildingType.RESIDENTIAL)
				{
					NeoSim.LOGGER.warn("NeoSim-ControlBoxManage: '{}' is not residential, reject", rec.schematicName());
					return;
				}

				switch (payload.action())
				{
					case 1 ->
							CityLivingManager.evictResident(level, city, rec, payload.targetName());
					case 2 ->
							CityLivingManager.evictAllResidents(level, city, rec);
					case 3 ->
					{
						String err = CityLivingManager.moveInHomeless(level, city, rec, payload.targetName());
						if (err != null)
						{
							player.displayClientMessage(Component.literal(err), false);
						}
					}
					default ->
							NeoSim.LOGGER.warn("NeoSim-ControlBoxManage: unknown action {}", payload.action());
				}

				// 动作已处理并落盘：回 ack 让客户端刷新控制箱GUI（避免客户端读到旧文件）
				PacketDistributor.sendToPlayer(player,
						new ServerToClientPayloads.ControlBoxAckPayload(payload.boxPos()));
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-ControlBoxManage: Fail", e);
				return null;
			});
		}
	}

	// 请求某NPC的族谱数据
	public record FamilyRequestPayload(String cityName, String npcName) implements CustomPacketPayload
	{
		public static final Type<FamilyRequestPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "family_request"));

		public static final StreamCodec<ByteBuf, FamilyRequestPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8,
						FamilyRequestPayload::cityName,
						ByteBufCodecs.STRING_UTF8,
						FamilyRequestPayload::npcName,
						FamilyRequestPayload::new
				);

		public static void handle(FamilyRequestPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				ServerPlayer player = (ServerPlayer) context.player();
				if (player == null || !player.isAlive()) return;
				ServerLevel level = player.serverLevel();

				// 1秒限流防刷包
				if (!JsonUtil.check(player.getUUID(), "family_request", 1000)) return;

				if (payload.cityName().isEmpty() || payload.npcName().isEmpty()) return;

				List<Genealogy.FamilyNode> nodes =
						Genealogy.collectFamily(level, payload.cityName(), payload.npcName());
				PacketDistributor.sendToPlayer(player,
						new ServerToClientPayloads.FamilyDataPayload(payload.npcName(), nodes));
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-FamilyRequest: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}
	}

	// 确认整地：模盒 + 方案 + 地块四角（四角由服务端与相邻标记矩形比对校验）
	public record TerraformStartPayload(BlockPos boxPos, String plan,
			int minX, int minZ, int maxX, int maxZ, int baselineY) implements CustomPacketPayload
	{
		public static final Type<TerraformStartPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "terraform_start"));

		public static final StreamCodec<RegistryFriendlyByteBuf, TerraformStartPayload> STREAM_CODEC =
				StreamCodec.ofMember(TerraformStartPayload::write, TerraformStartPayload::new);

		private TerraformStartPayload(RegistryFriendlyByteBuf buf)
		{
			this(buf.readBlockPos(), buf.readUtf(),
					buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
		}

		private void write(RegistryFriendlyByteBuf buf)
		{
			buf.writeBlockPos(boxPos);
			buf.writeUtf(plan);
			buf.writeInt(minX);
			buf.writeInt(minZ);
			buf.writeInt(maxX);
			buf.writeInt(maxZ);
			buf.writeInt(baselineY);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }

		public static void handle(TerraformStartPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				ServerPlayer player = (ServerPlayer) context.player();
				if (player == null || !player.isAlive()) return;
				ServerLevel level = player.serverLevel();

				// 1 秒限流防刷包
				if (!JsonUtil.check(player.getUUID(), "terraform_start", 1000))
				{
					player.displayClientMessage(Component.literal("§c请稍后再试"), true);
					return;
				}

				// 玩家所属城市（服务端权威）
				String playerName = player.getName().getString();
				String city = level.getServer().isDedicatedServer()
						? FileCreater.findPlayerCity(playerName)
						: FileCreater.findPlayerCity(level.getServer().getWorldData().getLevelName(), playerName);

				TerraformPlan plan = TerraformPlan.valueOfSafe(payload.plan());
				if (plan == null)
				{
					player.displayClientMessage(Component.literal("§c无效的整地方案"), false);
					return;
				}

				String err = TerraformEngine.start(level, city, payload.boxPos(), plan,
						payload.minX(), payload.minZ(), payload.maxX(), payload.maxZ(), payload.baselineY());
				if (err != null)
				{
					player.displayClientMessage(Component.literal(err), false);
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-TerraformStart: Fail", e);
				return null;
			});
		}
	}

	// ===== 城市系统（C→S，原 CityPayloads）=====
	// 创建城市（模式为全服全局，由 Run 界面的 UpdatePayload 设置，此处不带 mode）
	public record CreateCityPayload(String cityName) implements CustomPacketPayload
	{
		public static final Type<CreateCityPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "create_city"));

		public static final StreamCodec<ByteBuf, CreateCityPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, CreateCityPayload::cityName,
						CreateCityPayload::new);

		public static void handle(CreateCityPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (!(context.player() instanceof ServerPlayer player) || !player.isAlive()) return;
				String err = CityManager.createCity(player.serverLevel(), player, payload.cityName());
				if (err != null)
				{
					player.displayClientMessage(Component.literal(err), false);
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-CreateCity: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	// 加入已有城市
	public record JoinCityPayload(String cityName) implements CustomPacketPayload
	{
		public static final Type<JoinCityPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "join_city"));

		public static final StreamCodec<ByteBuf, JoinCityPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, JoinCityPayload::cityName,
						JoinCityPayload::new);

		public static void handle(JoinCityPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (!(context.player() instanceof ServerPlayer player) || !player.isAlive()) return;
				String err = CityManager.joinCity(player.serverLevel(), player, payload.cityName());
				if (err != null)
				{
					player.displayClientMessage(Component.literal(err), false);
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-JoinCity: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	// 请求服务器城市列表（City GUI 的"选择城市"页，联机客户端无服务器文件系统）
	public record CityListRequestPayload() implements CustomPacketPayload
	{
		public static final Type<CityListRequestPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "city_list_request"));

		public static final StreamCodec<ByteBuf, CityListRequestPayload> STREAM_CODEC =
				StreamCodec.unit(new CityListRequestPayload());

		public static void handle(CityListRequestPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (!(context.player() instanceof ServerPlayer player)) return;
				List<String> cities = com.wenzai.neosim.storage.FileCreater.listCities(player.serverLevel());
				PacketDistributor.sendToPlayer(player, new ServerToClientPayloads.CityListResponsePayload(cities));
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-CityListRequest: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	// 请求可雇佣市民列表（C→S；jobKind: 0 建筑师 1 农夫 2 矿工 3 快递员）
	public record HireListRequestPayload(net.minecraft.core.BlockPos boxPos, int jobKind) implements CustomPacketPayload
	{
		public static final Type<HireListRequestPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "hire_list_request"));

		public static final StreamCodec<ByteBuf, HireListRequestPayload> STREAM_CODEC =
				StreamCodec.composite(
						net.minecraft.core.BlockPos.STREAM_CODEC, HireListRequestPayload::boxPos,
						ByteBufCodecs.VAR_INT, HireListRequestPayload::jobKind,
						HireListRequestPayload::new);

		public static void handle(HireListRequestPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (!(context.player() instanceof ServerPlayer player)) return;
				String city = com.wenzai.neosim.storage.CityManager.getCity(player.getUUID());
				if (city.isEmpty()) return;
				PacketDistributor.sendToPlayer(player, new ServerToClientPayloads.HireListResponsePayload(
						com.wenzai.neosim.building.HireListService.collect(
								player.serverLevel(), city, payload.jobKind())));
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-HireListRequest: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	// 雇佣市民（C→S）
	public record HirePayload(net.minecraft.core.BlockPos boxPos, String npcName) implements CustomPacketPayload
	{
		public static final Type<HirePayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "hire"));

		public static final StreamCodec<ByteBuf, HirePayload> STREAM_CODEC =
				StreamCodec.composite(
						net.minecraft.core.BlockPos.STREAM_CODEC, HirePayload::boxPos,
						ByteBufCodecs.STRING_UTF8, HirePayload::npcName,
						HirePayload::new);

		public static void handle(HirePayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (!(context.player() instanceof ServerPlayer player) || !player.isAlive()) return;
				if (!com.wenzai.neosim.util.JsonUtil.check(player.getUUID(), "hire", 1000)) return;
				String err = com.wenzai.neosim.building.WorkerService.tryHire(
						player.serverLevel(), player, payload.boxPos(), payload.npcName());
				if (err != null)
				{
					player.displayClientMessage(Component.literal(err), false);
				}
				else
				{
					PacketDistributor.sendToPlayer(player,
							new ServerToClientPayloads.WorkerUpdatePayload(payload.boxPos(), payload.npcName()));
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-Hire: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	// 解雇（C→S）
	public record FirePayload(net.minecraft.core.BlockPos boxPos) implements CustomPacketPayload
	{
		public static final Type<FirePayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "fire"));

		public static final StreamCodec<ByteBuf, FirePayload> STREAM_CODEC =
				StreamCodec.composite(
						net.minecraft.core.BlockPos.STREAM_CODEC, FirePayload::boxPos,
						FirePayload::new);

		public static void handle(FirePayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (!(context.player() instanceof ServerPlayer player) || !player.isAlive()) return;
				if (!com.wenzai.neosim.util.JsonUtil.check(player.getUUID(), "fire", 1000)) return;
				String err = com.wenzai.neosim.building.WorkerService.tryFire(
						player.serverLevel(), payload.boxPos());
				if (err != null)
				{
					player.displayClientMessage(Component.literal(err), false);
				}
				else
				{
					PacketDistributor.sendToPlayer(player, new ServerToClientPayloads.WorkerUpdatePayload(payload.boxPos(), ""));
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-Fire: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	// 请求重算缺少材料（C→S）
	public record MissingScanRequestPayload(net.minecraft.core.BlockPos boxPos) implements CustomPacketPayload
	{
		public static final Type<MissingScanRequestPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "missing_scan_request"));

		public static final StreamCodec<ByteBuf, MissingScanRequestPayload> STREAM_CODEC =
				StreamCodec.composite(
						net.minecraft.core.BlockPos.STREAM_CODEC, MissingScanRequestPayload::boxPos,
						MissingScanRequestPayload::new);

		public static void handle(MissingScanRequestPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (!(context.player() instanceof ServerPlayer player)) return;
				if (!com.wenzai.neosim.util.JsonUtil.check(player.getUUID(), "missing_scan", 2000)) return;
				java.util.List<ServerToClientPayloads.MissingScanResponsePayload.MissingEntry> entries =
						com.wenzai.neosim.building.MissingScanService.scan(player.serverLevel(), payload.boxPos());
				PacketDistributor.sendToPlayer(player,
						new ServerToClientPayloads.MissingScanResponsePayload(payload.boxPos(), entries));
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-MissingScanRequest: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	// 请求无家 NPC 名单（C→S；缺陷 C 结构性，服务端按玩家城市权威生成）
	public record HomelessListRequestPayload() implements CustomPacketPayload
	{
		public static final Type<HomelessListRequestPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "homeless_list_request"));

		public static final StreamCodec<ByteBuf, HomelessListRequestPayload> STREAM_CODEC =
				StreamCodec.unit(new HomelessListRequestPayload());

		public static void handle(HomelessListRequestPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (!(context.player() instanceof ServerPlayer player)) return;
				String city = com.wenzai.neosim.storage.CityManager.getCity(player.getUUID());
				if (city.isEmpty()) return;
				PacketDistributor.sendToPlayer(player, new ServerToClientPayloads.HomelessListResponsePayload(
						com.wenzai.neosim.building.HomelessListService.collect(player.serverLevel(), city)));
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-HomelessListRequest: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}

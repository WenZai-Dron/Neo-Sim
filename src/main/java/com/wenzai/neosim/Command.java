package com.wenzai.neosim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.wenzai.neosim.npc.Manage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.concurrent.CompletableFuture;

public class Command
{
	@SubscribeEvent
	public static void register(RegisterCommandsEvent event)
	{
		CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

		dispatcher.register(
			Commands.literal("neosim")
				.then(Commands.literal("credit")
					.then(Commands.literal("set")
						.then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
							.then(Commands.argument("cityName", StringArgumentType.greedyString())
								.suggests(Command::suggestCities)
								.executes(ctx -> setCredit(ctx,
										DoubleArgumentType.getDouble(ctx, "value"),
										StringArgumentType.getString(ctx, "cityName")))
							)
						)
					)
					.then(Commands.literal("add")
						.then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
							.then(Commands.argument("cityName", StringArgumentType.greedyString())
								.suggests(Command::suggestCities)
								.executes(ctx -> addCredit(ctx,
										DoubleArgumentType.getDouble(ctx, "value"),
										StringArgumentType.getString(ctx, "cityName")))
							)
						)
					)
				)
				.then(Commands.literal("npc")
					.then(Commands.literal("spawn")
						.then(Commands.argument("cityName", StringArgumentType.greedyString())
							.suggests(Command::suggestCities)
							.executes(ctx -> spawnNpc(ctx, StringArgumentType.getString(ctx, "cityName")))
						)
					)
				)
		);
	}

	// Tab补全：列出已有城市名
	private static <S> CompletableFuture<Suggestions> suggestCities(
			CommandContext<S> ctx, SuggestionsBuilder builder)
	{
		if (ctx.getSource() instanceof net.minecraft.commands.CommandSourceStack source)
		{
			for (String city : com.wenzai.neosim.storage.FileCreater.listCities(source.getLevel()))
			{
				builder.suggest(city);
			}
		}
		return builder.buildFuture();
	}

	private static int setCredit(CommandContext<CommandSourceStack> ctx, double value, String cityName)
	{
		ServerLevel level = ctx.getSource().getLevel();

		// 检查城市是否存在
		if (!Manage.cityExists(level, cityName))
		{
			ctx.getSource().sendFailure(Component.literal("§cCity '" + cityName + "' does not exist"));
			return 0;
		}

		double rounded = Math.round(value * 100.0) / 100.0;
		com.wenzai.neosim.storage.SimData.CityData city = com.wenzai.neosim.storage.SimData.CityData.read(level, cityName);
		com.wenzai.neosim.storage.SimData.CityData.write(level, cityName, city.withCredit(rounded));

		// 同步给该城市在线玩家，HUD刷新
		com.wenzai.neosim.storage.ModSavedData.get(level).syncCityToClients(level, cityName);
		NeoSim.LOGGER.info("NeoSim-Command: credit={} city={}", rounded, cityName);
		return 1;
	}

	private static int addCredit(CommandContext<CommandSourceStack> ctx, double value, String cityName)
	{
		ServerLevel level = ctx.getSource().getLevel();

		// 检查城市是否存在
		if (!Manage.cityExists(level, cityName))
		{
			ctx.getSource().sendFailure(Component.literal("§cCity '" + cityName + "' does not exist"));
			return 0;
		}

		com.wenzai.neosim.storage.SimData.CityData city = com.wenzai.neosim.storage.SimData.CityData.read(level, cityName);
		double rounded = Math.round((city.credit() + value) * 100.0) / 100.0;
		com.wenzai.neosim.storage.SimData.CityData.write(level, cityName, city.withCredit(rounded));

		// 同步给该城市在线玩家，HUD刷新
		com.wenzai.neosim.storage.ModSavedData.get(level).syncCityToClients(level, cityName);
		NeoSim.LOGGER.info("NeoSim-Command: credit +{} city={} now={}", value, cityName, rounded);
		return 1;
	}

	private static int spawnNpc(CommandContext<CommandSourceStack> ctx, String cityName)
	{
		ServerLevel level = ctx.getSource().getLevel();

		// 检查城市是否存在
		if (!Manage.cityExists(level, cityName))
		{
			ctx.getSource().sendFailure(Component.literal("§cCity '" + cityName + "' does not exist"));
			return 0;
		}

		BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());
		Manage.spawnAt(level, pos, cityName);
		return 1;
	}

	// 禁止/summon生成NPC
	@SubscribeEvent
	public static void onCommand(CommandEvent event)
	{
		String cmd = event.getParseResults().getReader().getString();
		String trimmed = cmd.stripLeading();
		if (trimmed.startsWith("summon ") || trimmed.startsWith("minecraft:summon "))
		{
			if (trimmed.contains("neo_sim:"))
			{
				event.setCanceled(true);
				event.getParseResults().getContext().getSource()
						.sendFailure(Component.literal("§cFail"));
			}
		}
	}
}

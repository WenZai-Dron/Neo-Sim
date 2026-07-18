package com.wenzai.neosim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.storage.ModSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public class Command
{
    @SubscribeEvent
    public static void register(RegisterCommandsEvent event)
    {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("neosim")
                .then(Commands.literal("mode")
                    .then(Commands.literal("set")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 3))
                            .executes(ctx -> setMode(ctx, IntegerArgumentType.getInteger(ctx, "value")))
                        )
                    )
                )
                .then(Commands.literal("credit")
                    .then(Commands.literal("set")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> setCredit(ctx, DoubleArgumentType.getDouble(ctx, "value")))
                        )
                    )
                )
                .then(Commands.literal("npc")
                    .then(Commands.argument("cityName", StringArgumentType.greedyString())
                        .executes(ctx -> spawnNpc(ctx, StringArgumentType.getString(ctx, "cityName")))
                    )
                )
        );
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx, int value)
    {
        ServerLevel level = ctx.getSource().getLevel();
        ModSavedData data = ModSavedData.get(level);
        data.setMode((byte) value, level);
        NeoSim.LOGGER.info("NeoSim-Command: mode={}", value);
        return 1;
    }

    private static int setCredit(CommandContext<CommandSourceStack> ctx, double value)
    {
        ServerLevel level = ctx.getSource().getLevel();
        ModSavedData data = ModSavedData.get(level);
        double rounded = Math.round(value * 100.0) / 100.0;
        data.setCredit(rounded, level);
        NeoSim.LOGGER.info("NeoSim-Command: credit={}", rounded);
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
        ctx.getSource().sendSuccess(() -> Component.literal("NPC spawned in city " + cityName), true);
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

package com.wenzai.neosim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
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
        data.setCredit(value, level);
        NeoSim.LOGGER.info("NeoSim-Command: credit={}", value);
        return 1;
    }
}

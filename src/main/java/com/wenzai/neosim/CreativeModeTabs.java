package com.wenzai.neosim;

import com.wenzai.neosim.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

import com.wenzai.neosim.NeoSim;

public class CreativeModeTabs
{
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, NeoSim.MOD_ID);

    public static final Supplier<CreativeModeTab> NEOSIM_TAB =
            CREATIVE_MODE_TABS.register("neosim_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModBlocks.BUILDING_CONSTRUCTOR.get()))
                    .title(Component.translatable("itemGroup.neosim_tab"))
                    .displayItems((itemDisplayParameters, output) -> {



                        // 此处加入
                        output.accept(ModBlocks.BUILDING_CONSTRUCTOR);
                        output.accept(ModBlocks.CONTROL_BOX);
                        output.accept(ModBlocks.MARKER);
                        output.accept(ModBlocks.FARMING_BOX);
                        output.accept(ModBlocks.MINING_BOX);

                    }).build());

    public static void register(IEventBus eventBus)
    {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
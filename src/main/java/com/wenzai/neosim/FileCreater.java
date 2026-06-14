package com.wenzai.neosim;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@EventBusSubscriber(modid = NeoSim.MOD_ID)
public class FileCreater
{
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void createNeoSimFolders()
    {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path neoSimDir = gameDir.resolve("NeoSim");
        Path skinDir = neoSimDir.resolve("Skin");

        if (!Files.exists(skinDir))
        {
            try
            {
                Files.createDirectories(skinDir);
                LOGGER.info("NeoSim-createNeoSimFolders: Succeed, {}", skinDir.toAbsolutePath());
            }
            catch (IOException e)
            {
                LOGGER.error("NeoSim-createNeoSimFolders: Fail, {}", e.getMessage(), e);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event)
    {
        createNeoSimFolders();
    }
}

package com.wenzai.neosim;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CreateFiles
{
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateFiles.class);
    private static final Path Neo_Sim_Path;
    private static final Path Buildings_Path;
    private static final Path Skins_Path;
    private static final Path Save_Path;
    private static final Path Money_Path;
    private static final Path NPC_Path;
    private static final Path Array_Path;
    private static final Path MoneyJson_Path;

    static
    {
        // 获取.minecraft
        File minecraftDir = Minecraft.getInstance().gameDirectory;

        // 拼接目录：.minecraft/Neo-Sim
        Neo_Sim_Path = minecraftDir.toPath().resolve("Neo-Sim");

        // 拼接目录：.minecraft/Neo-Sim/Buildings
        Buildings_Path = Neo_Sim_Path.resolve("Buildings");

        // 拼接目录：.minecraft/Neo-Sim/Skins
        Skins_Path = Neo_Sim_Path.resolve("Skins");

        // 拼接目录：.minecraft/Neo-Sim/Save
        Save_Path = Neo_Sim_Path.resolve("Save");

        // 拼接目录：.minecraft/Neo-Sim/Save/Money
        Money_Path = Save_Path.resolve("Money");

        // 拼接目录：.minecraft/Neo-Sim/Save/NPC
        NPC_Path = Save_Path.resolve("NPC");

        // 拼接目录：.minecraft/Neo-Sim/Save/Array.json
        Array_Path = Save_Path.resolve("Array.json");

        // 拼接目录：.minecraft/Neo-Sim/Save/Money/Money.json
        MoneyJson_Path = Money_Path.resolve("Money.json");
    }

    public static void initNeoSimDir()
    {
        try
        {
            if (!Files.exists(Neo_Sim_Path))
            {
                Files.createDirectories(Neo_Sim_Path);
                LOGGER.info("[Neo-Sim] 成功创建文件：{}", Neo_Sim_Path.toAbsolutePath());
            } else
            {
                LOGGER.info("[Neo-Sim] 文件已存在：{}", Neo_Sim_Path.toAbsolutePath());
            }

            // 依次创建Neo-Sim下的文件
            createSubDirectory(Buildings_Path, "Buildings");
            createSubDirectory(Skins_Path, "Skins");
            createSubDirectory(Save_Path, "Save");
            createSubDirectory(Money_Path, "Money");
            createSubDirectory(NPC_Path, "NPC");
            createFile(Array_Path, "Array.json");
            createFile(MoneyJson_Path, "Money.json");

        } catch (IOException e)
        {
            // 输出IO异常日志
            LOGGER.error("[Neo-Sim] 创建文件失败！Path：{}", Neo_Sim_Path.toAbsolutePath(), e);
        }
    }

    private static void createSubDirectory(Path dirPath, String dirName)
    {
        try
        {
            if (!Files.exists(dirPath))
            {
                Files.createDirectories(dirPath);
                LOGGER.info("[Neo-Sim] 成功创建文件 [{}]：{}", dirName, dirPath.toAbsolutePath());
            } else
            {
                LOGGER.info("[Neo-Sim] 文件 [{}] 已存在：{}", dirName, dirPath.toAbsolutePath());
            }
        } catch (IOException e)
        {
            LOGGER.error("[Neo-Sim] 创建文件 [{}] 失败！Path：{}", dirName, dirPath.toAbsolutePath(), e);
        }
    }

    private static void createFile(Path filePath, String fileName)
    {
        try
        {
            Path parentDir = filePath.getParent();
            if (parentDir != null && !Files.exists(parentDir))
            {
                Files.createDirectories(parentDir);
                LOGGER.info("[Neo-Sim] 创建文件：{}", parentDir.toAbsolutePath());
            }

            if (!Files.exists(filePath))
            {
                Files.createFile(filePath);
                LOGGER.info("[Neo-Sim] 成功创建文件 [{}]：{}", fileName, filePath.toAbsolutePath());
            } else
            {
                LOGGER.info("[Neo-Sim] 文件 [{}] 已存在：{}", fileName, filePath.toAbsolutePath());
            }
        } catch (IOException e)
        {
            LOGGER.error("[Neo-Sim] 创建文件 [{}] 失败！Path：{}", fileName, filePath.toAbsolutePath(), e);
        }
    }
}
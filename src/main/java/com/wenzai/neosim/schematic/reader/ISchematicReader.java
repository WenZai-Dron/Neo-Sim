package com.wenzai.neosim.schematic.reader;

import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SchematicFormat;

import java.io.IOException;
import java.nio.file.Path;

//读取schematic文件并生成统一{@link SchematicData}模型
public interface ISchematicReader
{
    // 从文件路径读取schematic
    SchematicData read(Path filePath) throws IOException;

    // 此读取器处理的文件格式
    SchematicFormat getFormat();
}

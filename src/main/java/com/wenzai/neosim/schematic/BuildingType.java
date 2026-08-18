package com.wenzai.neosim.schematic;

//建筑类型枚举，对应Sim-U-Kraft目录结构
public enum BuildingType
{
	RESIDENTIAL("Residential"),
	COMMERCIAL("Commercial"),
	INDUSTRIAL("Industrial"),
	OTHER("Other"),
	CUSTOM("Custom");

	private final String displayName;

	BuildingType(String displayName)
	{
		this.displayName = displayName;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	// 从目录名解析建筑类型
	public static BuildingType fromDirectoryName(String name)
	{
		if (name == null) return OTHER;
		return switch (name.toLowerCase())
		{
			case "residential" -> RESIDENTIAL;
			case "commercial"  -> COMMERCIAL;
			case "industrial"  -> INDUSTRIAL;
			default            -> OTHER;
		};
	}
}

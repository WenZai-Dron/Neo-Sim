package com.wenzai.neosim.client.preview;

import com.wenzai.neosim.schematic.PreviewState;

// 客户端预览状态：在 common PreviewState 之上附加 VBO 网格缓存（GPU 资源）
public class ClientPreviewState extends PreviewState
{
	private GhostBlockRenderer.GhostMeshCache meshCache;

	public GhostBlockRenderer.GhostMeshCache getMeshCache()
	{
		// 仅客户端渲染路径调用
		if (meshCache == null) meshCache = new GhostBlockRenderer.GhostMeshCache();
		return meshCache;
	}

	@Override
	public void setActive(boolean v)
	{
		super.setActive(v);
		// 预览结束：释放缓存的GPU显存
		if (!v && meshCache != null) meshCache.invalidate();
	}
}

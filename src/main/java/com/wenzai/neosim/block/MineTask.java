package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.building.InventoryManager;
import com.wenzai.neosim.npc.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class MineTask extends PlotTask
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private int discards;

	public MineTask(ServerLevel level, String cityName, WorkBoxPersistence.WorkBoxRecord record)
	{
		super(level, cityName, record);
		this.discards = record.discards();
	}

	// GUI设置丢弃过滤
	public void setDiscards(int d)
	{
		this.discards = Math.max(0, Math.min(7, d));
		record = record.withDiscards(this.discards);
		updateRecord();
		LOGGER.info("NeoSim-MineTask: discards={} at {}", this.discards, boxPos());
	}

	public int getDiscards() { return discards; }

	// 当前开采深度
	public int getDepth() { return record.depth(); }

	@Override
	protected void onArrived()
	{
		setState(PlotState.CHECKING_CHESTS);
		setHandTool();
	}

	@Override
	protected void onChestsReady()
	{
		setState(PlotState.MINING);
		setHandTool();
		record = record.withCursor(0, 0);
	}

	@Override
	protected Item handItem()
	{
		return Items.IRON_PICKAXE;
	}

	@Override
	protected byte jobLevelOf(Entity npc) { return npc.getJobMiner(); }

	@Override
	protected void setNpcJobLevel(Entity npc, int lvl) { npc.setJobMiner((byte) lvl); }

	@Override
	protected void subclassWorkTick()
	{
		switch (state)
		{
			case MINING -> doMining();
			case WAITING_FOR_CHEST ->
			{
				long now = System.currentTimeMillis();
				if (currentMode() != 2 && now - lastOpTime < runDelay) return;
				lastOpTime = now;
				refreshChests();
				setState(PlotState.MINING);
			}
			default -> setState(PlotState.CHECKING_CHESTS);
		}
	}

	// 开采
	private void doMining()
	{
		refreshChests();
		while (true)
		{
			BlockPos pos = cellPos(record.row(), record.col());
			if (cellOutsideBuildHeight(pos))
			{
				if (advanceMineCursor()) return;
				continue;
			}
			BlockState bs = level.getBlockState(pos);

			if (!isMinable(bs))
			{
				// 该格无可挖：跳过游标
				setHandAnim(0.0F);
				if (advanceMineCursor()) return;
				continue;
			}

			long now = System.currentTimeMillis();
			if (animateHand(now)) return;

			List<ItemStack> drops = InventoryManager.getBlockDrops(level, pos, bs);
			List<ItemStack> kept = new ArrayList<>();
			for (ItemStack stack : drops)
			{
				if (!shouldDiscard(stack)) kept.add(stack);
			}

			// 箱子放得下才挖
			for (ItemStack stack : kept)
			{
				if (!InventoryManager.canDeposit(nearbyChests, stack))
				{
					// 箱子满了：停滞等待，非工作清空手持
					clearHand();
					setState(PlotState.WAITING_FOR_CHEST);
					return;
				}
			}
			for (ItemStack stack : kept)
			{
				InventoryManager.depositItems(nearbyChests, stack);
			}
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			deductCredits(Config.WORK_MINE_CREDIT_PER_BLOCK.get());
			gainXp();
			advanceMineCursor();
			return;
		}
	}

	// 深度优先游标：返回true表示已完成，调用方应停止
	private boolean advanceMineCursor()
	{
		int col = record.col() + 1;
		int row = record.row();
		if (col >= cols())
		{
			col = 0;
			row++;
		}
		if (row >= rows())
		{
			int nd = record.depth() - 1;
			// 下一层越出世界高度（防无基岩世界无限下挖），或已含基岩：挖到基岩，采尽
			if (nd < level.getMinBuildHeight() || layerHasBedrock(nd))
			{
				deplete();
				return true;
			}
			record = record.withDepth(nd).withCursor(0, 0);
			return false;
		}
		record = record.withCursor(row, col);
		return false;
	}

	// 该层矩形内是否已出现基岩：挖到基岩层即无法继续下挖，触发采尽
	private boolean layerHasBedrock(int y)
	{
		for (int r = 0; r < rows(); r++)
		{
			for (int c = 0; c < cols(); c++)
			{
				if (level.getBlockState(cellPos(r, c, y)).getBlock() == Blocks.BEDROCK) return true;
			}
		}
		return false;
	}

	// 挖到基岩：自动解雇矿工，转终态
	private void deplete()
	{
		String name = NeoSim.WORKER_MAP.remove(boxPos());
		if (name != null) releaseNpc(name);
		worker = null;
		record = record.withWorker(null);
		setState(PlotState.DEPLETED);
		clearHand();
		releaseChunks();
		updateRecord();
		LOGGER.info("NeoSim-MineTask: plot depleted, miner fired — work box at {}", boxPos());
	}

	// 可挖：非空气/水/岩浆/基岩
	private static boolean isMinable(BlockState bs)
	{
		if (bs.isAir()) return false;
		Block b = bs.getBlock();
		if (b == Blocks.BEDROCK) return false;
		if (!bs.getFluidState().isEmpty()) return false;
		return true;
	}

	// 丢弃过滤
	private boolean shouldDiscard(ItemStack stack)
	{
		Item item = stack.getItem();
		if (item == Items.VINE) return true;
		boolean dirtLike = item == Items.DIRT || item == Items.GRASS_BLOCK;
		boolean stoneLike = item == Items.STONE || item == Items.COBBLESTONE;
		boolean sandLike = item == Items.SAND || item == Items.RED_SAND;
		return ((discards & 1) != 0 && dirtLike)
				|| ((discards & 2) != 0 && stoneLike)
				|| ((discards & 4) != 0 && sandLike);
	}

	// 标记矩形边框不计入范围（矩形太窄没有内部时退回整框）
	private int insetX() { return record.rx2() - record.rx1() + 1 > 2 ? 1 : 0; }
	private int insetZ() { return record.rz2() - record.rz1() + 1 > 2 ? 1 : 0; }

	private int rows() { return record.rz2() - record.rz1() + 1 - 2 * insetZ(); }
	private int cols() { return record.rx2() - record.rx1() + 1 - 2 * insetX(); }

	// 当前游标的世界坐标
	private BlockPos cellPos(int row, int col)
	{
		return cellPos(row, col, record.depth());
	}

	// 指定高度的单元格世界坐标
	private BlockPos cellPos(int row, int col, int y)
	{
		return new BlockPos(record.rx1() + insetX() + col, y, record.rz1() + insetZ() + row);
	}

	private void refreshChests()
	{
		nearbyChests = InventoryManager.findNearbyChests(level, boxPos());
	}
}

package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.building.InventoryManager;
import com.wenzai.neosim.npc.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FarmTask extends PlotTask
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// 作物类型
	public enum FarmType
	{
		WHEAT, CARROT, POTATO, MELON, PUMPKIN, SUGAR, CACTUS, CUSTOM, LIVESTOCK;

		public static FarmType valueOfSafe(String name)
		{
			if (name == null || name.isEmpty()) return WHEAT;
			for (FarmType t : values())
			{
				if (t.name().equals(name)) return t;
			}
			return WHEAT;
		}
	}

	// 牲畜类型
	public enum LivestockType
	{
		CHICKEN, PIG, COW, SHEEP
	}

	// 树种类型（均为 1×1 常规树）
	public enum TreeType
	{
		OAK, BIRCH, SPRUCE, JUNGLE, ACACIA, CHERRY;

		public static TreeType valueOfSafe(String name)
		{
			if (name == null || name.isEmpty()) return OAK;
			for (TreeType t : values())
			{
				if (t.name().equals(name)) return t;
			}
			return OAK;
		}
	}

	// 选中的作物组合
	private List<FarmType> farmTypes;

	// 选中的牲畜
	private List<LivestockType> livestockTypes = List.of(LivestockType.values());

	// 选中的树种（林业）
	private List<TreeType> forestryTypes = List.of();

	// 育种轮转游标（畜牧公平性修复，Task 2 使用）
	private int livestockIndex;

	// 各物种最近一次成功繁殖的时间（毫秒，内存态，不持久化）
	private final java.util.Map<LivestockType, Long> lastBreedTime = new java.util.HashMap<>();

	// 本轮田间扫描是否遇到"空树位但箱子无树苗"
	private boolean missingSaplings;

	// 砍树进行中的队列（跨 tick）
	private final java.util.ArrayDeque<BlockPos> chopLogs = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<BlockPos> chopLeaves = new java.util.ArrayDeque<>();

	private boolean waterPlaced;

	// 交替种植游标
	private int plantIndex;

	// 交替种植树苗游标（多树种轮转）
	private int treePlantIndex;

	// 田间侧最近一次整轮是否产生过实际动作（收割/翻地/种植/清杂/种树/砍树任一）
	private boolean fieldBusy;

	// 本轮田间累计动作数：整轮（finishRound）结算 fieldBusy 用
	private int roundActions;

	// 空轮歇息节流计数：整轮无动作后累计，到 IDLE_RESCAN_INTERVAL 才重扫
	private int idleRescanTicks;

	// 田间侧待恢复状态：切去畜牧前记录（WAITING_SEED 等），切回时恢复，null=无
	private PlotState fieldPending;

	// 畜牧侧待恢复状态：切去田间前记录（WAITING_FEED 等），切回时恢复，null=无
	private PlotState livestockPending;

	// 缺种子时的等待时长：等3秒还补不上就跳过该格
	private static final long SEED_WAIT_MS = 3000L;

	// 空轮歇息节流：整轮无动作后，10 tick（0.5 秒）才重扫一次田间（避免无谓的整田扫描）
	private static final int IDLE_RESCAN_INTERVAL = 10;

	// C6b：田间/畜牧循环每 tick 扫描格位上/物种操作上限（防单 tick 叠加风暴）
	private static final int MAX_SCAN_PER_TICK = 64;

	// 本轮畜牧物种实体缓存（每 tick 每物种只扫一次围栏）
	private final Map<LivestockType, List<Animal>> penCache = new HashMap<>();

	// 畜牧整轮动作节流计数（20 tick 才整轮处理一次，中间 tick 直接让位）
	private int herdTimer;

	// C8：本轮「可选作物/树苗」缓存（refreshChests 时失效；整田扫描/逐格判断时只算一次，避免每格每物种 countItems 扫箱子）
	private FarmType cachedPlantable;
	private boolean plantableComputed;
	private TreeType cachedTreePlantable;
	private boolean treePlantableComputed;

	// L10：游标原始 int 字段（record 仅在持久化时同步，跳过循环不再每格复制 17 字段 record）
	private int cursorRow;
	private int cursorCol;

	// 林业：树苗 6 步格位（两树苗之间空 5 格，树冠 5×5 留 1 格余量）
	private static final int TREE_STEP = 6;
	// 砍树：单棵树 BFS 连通原木上限（防误扫连片森林）
	private static final int CHOP_LOG_LIMIT = 64;
	// C7：创造模式每 tick 方块动作预算上限（对齐性能文档 4 块/tick）
	private static final int CREATIVE_BLOCK_BUDGET = 4;
	// 砍树：每 tick 处理的方块数上限
	private static final int CHOP_BATCH = 12;

	// 26 向连通（含对角）：树干弯曲（如金合欢斜拐）/斜枝/树冠横枝都能连上
	private static final BlockPos[] DIR = buildDirs();

	private static BlockPos[] buildDirs()
	{
		List<BlockPos> out = new ArrayList<>();
		for (int x = -1; x <= 1; x++)
		{
			for (int y = -1; y <= 1; y++)
			{
				for (int z = -1; z <= 1; z++)
				{
					if (x != 0 || y != 0 || z != 0) out.add(new BlockPos(x, y, z));
				}
			}
		}
		return out.toArray(new BlockPos[0]);
	}

	public FarmTask(ServerLevel level, String cityName, WorkBoxPersistence.WorkBoxRecord record)
	{
		super(level, cityName, record);
		this.farmTypes = parseFarmTypes(record.farmType());
		this.livestockTypes = parseLivestockTypes(record.farmType());
		this.forestryTypes = parseForestryTypes(record.farmType());
		// L10：游标改为任务内原始 int 字段（record 仅在持久化时同步，跳过循环不再每格复制 17 字段 record）
		this.cursorRow = record.row();
		this.cursorCol = record.col();
	}

	// L10：持久化前把游标同步进 record
	@Override
	protected void updateRecord()
	{
		record = record.withCursor(cursorRow, cursorCol);
		super.updateRecord();
	}

	// GUI应用配置：写记录并重置流程（空配置回退小麦？？？要改）
	public void applyFarmCsv(String csv)
	{
		String normalized = normalizeFarmCsv(csv);
		this.farmTypes = parseFarmTypes(normalized);
		this.livestockTypes = parseLivestockTypes(normalized);
		this.forestryTypes = parseForestryTypes(normalized);
		this.livestockIndex = 0;
		this.lastBreedTime.clear();
		this.missingSaplings = false;
		this.plantIndex = 0;
		this.treePlantIndex = 0;
		this.fieldBusy = false;
		this.roundActions = 0;
		this.idleRescanTicks = 0;
		this.fieldPending = null;
		this.livestockPending = null;
		cursorRow = 0;
		cursorCol = 0;
		record = record.withFarmType(normalized).withCursor(0, 0);
		setState(PlotState.CHECKING_CHESTS);
		updateRecord();
		LOGGER.info("NeoSim-FarmTask: farm config set to '{}' at {}", normalized, boxPos());
	}

	public List<FarmType> getFarmTypes() { return farmTypes; }

	// 当前饲养
	public List<LivestockType> getLivestockTypes() { return livestockTypes; }

	public List<TreeType> getForestryTypes() { return forestryTypes; }

	// 是否林业模式
	public boolean isForestryMode()
	{
		return !forestryTypes.isEmpty();
	}

	// 是否有田间作业（种植或林业）
	public boolean hasFieldWork()
	{
		return hasCrops() || isForestryMode();
	}

	// 是否本轮缺树苗（供 GUI 显示「缺少树苗」）
	public boolean isMissingSaplings() { return missingSaplings; }

	// 是否畜牧模式
	public boolean isLivestockMode()
	{
		return farmTypes.contains(FarmType.LIVESTOCK)
				|| (record.farmType() != null && record.farmType().startsWith("LIVESTOCK"));
	}

	// 是否包含种植（组合非空）
	public boolean hasCrops()
	{
		for (FarmType t : farmTypes)
		{
			if (t != FarmType.LIVESTOCK) return true;
		}
		return false;
	}

	// 解析：逗号分隔标记
	public static List<FarmType> parseFarmTypes(String csv)
	{
		List<FarmType> out = new ArrayList<>();
		if (csv != null)
		{
			for (String s : csv.split(","))
			{
				String tok = s.trim();
				if (tok.isEmpty()) continue;
				if (tok.startsWith("LIVESTOCK"))
				{
					if (!out.contains(FarmType.LIVESTOCK)) out.add(FarmType.LIVESTOCK);
					continue;
				}
				if (tok.startsWith("FORESTRY"))
				{
					continue;
				}
				FarmType t = FarmType.valueOfSafe(tok);
				if (cropOf(t) != null && !out.contains(t)) out.add(t);
			}
		}
		return out;
	}

	// 解析选中牲畜
	public static List<LivestockType> parseLivestockTypes(String csv)
	{
		List<LivestockType> out = new ArrayList<>();
		boolean seenLivestock = false;
		if (csv != null)
		{
			for (String s : csv.split(","))
			{
				String tok = s.trim();
				if (tok.isEmpty()) continue;
				if (tok.startsWith("FORESTRY")) continue;
				if (tok.startsWith("LIVESTOCK"))
				{
					seenLivestock = true;
					if (tok.length() > "LIVESTOCK".length() && tok.charAt("LIVESTOCK".length()) == ':')
					{
						for (String a : tok.substring("LIVESTOCK:".length()).split("[+,]"))
						{
							addLivestockName(out, a.trim());
						}
					}
				}
				else
				{
					// 兼容旧档 "LIVESTOCK:CHICKEN,PIG" 被逗号拆开的遗留动物名
					addLivestockName(out, tok);
				}
			}
		}
		if (out.isEmpty() && seenLivestock) out.addAll(List.of(LivestockType.values()));
		return out;
	}

	// 解析选中树种
	public static List<TreeType> parseForestryTypes(String csv)
	{
		List<TreeType> out = new ArrayList<>();
		boolean seenForestry = false;
		if (csv != null)
		{
			for (String s : csv.split(","))
			{
				String tok = s.trim();
				if (tok.isEmpty()) continue;
				if (tok.startsWith("FORESTRY"))
				{
					seenForestry = true;
					if (tok.length() > "FORESTRY".length() && tok.charAt("FORESTRY".length()) == ':')
					{
						for (String a : tok.substring("FORESTRY:".length()).split("[+,]"))
						{
							addTreeName(out, a.trim());
						}
					}
				}
			}
		}
		if (out.isEmpty() && seenForestry) out.addAll(List.of(TreeType.values()));
		return out;
	}

	private static void addTreeName(List<TreeType> out, String name)
	{
		if (name == null || name.isEmpty()) return;
		for (TreeType t : TreeType.values())
		{
			if (t.name().equals(name) && !out.contains(t))
			{
				out.add(t);
				return;
			}
		}
	}

	private static void addLivestockName(List<LivestockType> out, String name)
	{
		if (name == null || name.isEmpty()) return;
		for (LivestockType t : LivestockType.values())
		{
			if (t.name().equals(name) && !out.contains(t))
			{
				out.add(t);
				return;
			}
		}
	}

	// 规范配置串
	public static String normalizeFarmCsv(String csv)
	{
		if (csv == null || csv.isBlank()) return "";
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (String s : csv.split(","))
		{
			String tok = s.trim();
			if (tok.isEmpty()) continue;
			if (tok.startsWith("LIVESTOCK"))
			{
				List<LivestockType> ls = parseLivestockTypes(tok);
				String live = ls.size() == LivestockType.values().length
						? "LIVESTOCK"
						: "LIVESTOCK:" + ls.stream().map(LivestockType::name)
								.collect(Collectors.joining("+"));
				if (!first) sb.append(",");
				sb.append(live);
				first = false;
				continue;
			}
			if (tok.startsWith("FORESTRY"))
			{
				List<TreeType> ts = parseForestryTypes(tok);
				String trees = ts.size() == TreeType.values().length
						? "FORESTRY"
						: "FORESTRY:" + ts.stream().map(TreeType::name)
								.collect(Collectors.joining("+"));
				if (!first) sb.append(",");
				sb.append(trees);
				first = false;
				continue;
			}
			FarmType t = FarmType.valueOfSafe(tok);
			if (cropOf(t) != null)
			{
				if (!first) sb.append(",");
				sb.append(t.name());
				first = false;
			}
		}
		if (sb.length() == 0) return "";
		return sb.toString();
	}

	// 写回记录：逗号分隔
	public static String farmTypesToCsv(List<FarmType> types)
	{
		return types.stream().map(FarmType::name).collect(Collectors.joining(","));
	}

	@Override
	protected void onArrived()
	{
		setState(PlotState.CHECKING_CHESTS);
		setHandTool();
	}

	@Override
	protected void onChestsReady()
	{
		if (!hasFieldWork() && !isLivestockMode())
		{
			// 空目标：等玩家设置
			setState(PlotState.IDLE);
			return;
		}
		setState(hasFieldWork() ? PlotState.HARVEST : PlotState.RAISE);
		setHandTool();
		cursorRow = 0;
		cursorCol = 0;
		record = record.withCursor(0, 0);
	}

	@Override
	protected Item handItem()
	{
		// 手持随当前轮次：畜牧轮（RAISE/等饲料）拿小麦（喂食形象）；田间轮林业开启拿铁斧（砍树），否则铁锄（种植）
		if (state == PlotState.RAISE || state == PlotState.WAITING_FEED) return Items.WHEAT;
		if (isForestryMode()) return Items.IRON_AXE;
		return Items.IRON_HOE;
	}

	@Override
	protected byte jobLevelOf(Entity npc) { return npc.getJobFarmer(); }

	@Override
	protected void setNpcJobLevel(Entity npc, int lvl) { npc.setJobFarmer((byte) lvl); }

	@Override
	protected void subclassWorkTick()
	{
		switch (state)
		{
			case HARVEST, TILL, PLANT ->
			{
				doFieldWork();

				// 田间动作后：畜牧有活（或畜牧在等待恢复）才切畜牧；否则继续田间
				if (isLivestockMode() && isFieldState(state)
						&& (livestockPending != null || livestockHasWork()))
				{
					fieldPending = state;
					PlotState target = livestockPending != null ? livestockPending : PlotState.RAISE;
					livestockPending = null;
					setState(target);
					setHandTool();
				}
			}
			case RAISE ->
			{
				doLivestock();

				// 畜牧轮后：田间有活（或田间在等待恢复）才切回；否则继续畜牧
				if (hasFieldWork() && state == PlotState.RAISE)
				{
					if (fieldPending != null || fieldBusy || maybeRefreshFieldBusy())
					{
						PlotState target = fieldPending != null ? fieldPending : PlotState.HARVEST;
						fieldPending = null;
						setState(target);
						setHandTool();
					}
				}
			}
			case WAITING_SEED ->
			{
				doWaitSeed();

				// 缺种子不阻塞畜牧：畜牧有活才切（记录田间侧待恢复状态）
				if (isLivestockMode() && state == PlotState.WAITING_SEED && livestockHasWork())
				{
					fieldPending = PlotState.WAITING_SEED;
					setState(PlotState.RAISE);
					setHandTool();
				}
			}
			case WAITING_FEED ->
			{
				doWaitFeed();

				// 缺饲料不阻塞种植：田间有活才切（记录畜牧侧待恢复状态）
				if (hasFieldWork() && state == PlotState.WAITING_FEED
						&& (fieldBusy || maybeRefreshFieldBusy()))
				{
					livestockPending = PlotState.WAITING_FEED;
					setState(PlotState.HARVEST);
					setHandTool();
				}
			}
			case WAITING_FOR_CHEST ->
			{
				long now = System.currentTimeMillis();
				if (currentMode() != 2 && now - lastOpTime < runDelay) return;
				lastOpTime = now;
				setState(hasFieldWork() ? PlotState.HARVEST : PlotState.RAISE);
			}
			default -> setState(PlotState.CHECKING_CHESTS);
		}
	}

	private boolean isFieldState(PlotState s)
	{
		return s == PlotState.HARVEST || s == PlotState.TILL || s == PlotState.PLANT;
	}

	// 每个格子当下缺什么就做什么：收割/除草/翻地/种植/种树/砍树
	private void doFieldWork()
	{
		if (hasCrops()) ensureFieldWater();
		refreshChests();
		// 空轮节流：上一整轮无动作时，10 tick 内不重扫（不烧延迟、不做无谓扫描）
		if (!fieldBusy && idleRescanTicks > 0 && idleRescanTicks < IDLE_RESCAN_INTERVAL)
		{
			idleRescanTicks++;
			setHandAnim(0.0F);
			return;
		}
		idleRescanTicks = 0;
		int scanned = 0;
		while (true)
		{
			// C6b：跳过循环每 tick 上限
			if (++scanned > MAX_SCAN_PER_TICK) return;
			// 砍树队列未清空：继续处理（先于游标扫描）
			if (!chopLogs.isEmpty() || !chopLeaves.isEmpty())
			{
				long now = System.currentTimeMillis();
				if (animateHand(now)) return;
				if (processChopBatch()) return;   // 仍有剩余：本 tick 结束
				continue;                          // 队列清空：重新取当前格
			}

			BlockPos pos = cellPos(cursorRow, cursorCol);
			if (cellOutsideBuildHeight(pos))
			{
				if (advanceCursor()) { finishRound(); return; }
				continue;
			}
			BlockState bs = level.getBlockState(pos);
			BlockState below = level.getBlockState(pos.below());

			// 林业：选中树种原木 -> 砍树（整棵树入队，分 tick 处理）
			if (isSelectedLog(bs))
			{
				enqueueTree(pos);
				long now = System.currentTimeMillis();
				if (animateHand(now)) return;
				if (processChopBatch()) return;
				continue;
			}

			// 该格当下是否有实际可干（缺种子格不算可干，快速跳过，不烧延迟）
			boolean mature = bs.getBlock() instanceof CropBlock cb
					&& cb.getAge(bs) >= cb.getMaxAge() && isSelectedCrop(bs.getBlock());
			boolean actionable = isRemovableVegetation(bs)
					|| mature
					|| (bs.isAir() && isTillableSoil(below))
					|| (bs.isAir() && below.is(Blocks.FARMLAND) && pickPlantable() != null);

			if (!actionable)
			{
				// 无活可干：跳过游标（树苗/非选中树/水格/缺种子格等）
				setHandAnim(0.0F);
				if (advanceCursor()) { finishRound(); return; }
				continue;
			}

			long now = System.currentTimeMillis();
			if (animateHand(now)) return;

			// 清掉该格地表植被
			if (isRemovableVegetation(bs))
			{
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				deductCredits(Config.WORK_FARM_CREDIT_PER_BLOCK.get());
				gainXp();
				roundActions++;
				advanceCursor();
				return;
			}

			// 成熟作物->掉落->入箱清空
			if (mature)
			{
				List<ItemStack> drops = InventoryManager.getBlockDrops(level, pos, bs);
				boolean room = true;
				for (ItemStack stack : drops)
				{
					if (!InventoryManager.canDeposit(nearbyChests, stack))
					{
						room = false;
						break;
					}
				}
				if (!room)
				{
					// 箱子满了：停滞等待，非工作清空手持
					clearHand();
					setState(PlotState.WAITING_FOR_CHEST);
					return;
				}
				for (ItemStack stack : drops)
				{
					InventoryManager.depositItems(nearbyChests, stack);
				}
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				deductCredits(Config.WORK_FARM_CREDIT_PER_BLOCK.get());
				gainXp();
				roundActions++;
				advanceCursor();
				return;
			}

			// 空气格
			if (bs.isAir())
			{
				// 林业：6 步格且下方适宜土 -> 种树苗
				if (isForestryMode() && isTreeSpot(cursorRow, cursorCol)
						&& isSoilForSapling(below))
				{
					TreeType toPlant = pickPlantableTree();
					if (toPlant == null)
					{
						// 无树苗：跳过该格（林业空转，树叶掉落可回补）
						missingSaplings = true;
						if (advanceCursor()) { finishRound(); return; }
						continue;
					}
					if (currentMode() != 2)
					{
						InventoryManager.extractItem(nearbyChests, saplingOf(toPlant), 1);
					}
					level.setBlock(pos, saplingBlockOf(toPlant).defaultBlockState(), Block.UPDATE_ALL);
					treePlantIndex++;
					deductCredits(Config.WORK_FARM_CREDIT_PER_BLOCK.get());
					gainXp();
					roundActions++;
					advanceCursor();
					return;
				}

				// 翻地：仅勾种植时；树苗步格留给树
				if (hasCrops() && isTillableSoil(below)
						&& (!isForestryMode() || !isTreeSpot(cursorRow, cursorCol)))
				{
					level.setBlock(pos.below(), Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
					deductCredits(Config.WORK_FARM_CREDIT_PER_BLOCK.get());
					gainXp();
					roundActions++;
					advanceCursor();
					return;
				}

				// 种植：按轮转种选中作物里箱子里有种子的
				if (hasCrops() && below.is(Blocks.FARMLAND))
				{
					FarmType toPlant = pickPlantable();
					if (toPlant == null)
					{
						// 全部缺种子：整体等待（游标冻结；正常不会走到，actionable 已排除）
						setState(PlotState.WAITING_SEED);
						clearHand();
						return;
					}
					if (currentMode() != 2)
					{
						InventoryManager.extractItem(nearbyChests, seedOf(toPlant), 1);
					}
					level.setBlock(pos, cropOf(toPlant).defaultBlockState(), Block.UPDATE_ALL);
					plantIndex++;
					deductCredits(Config.WORK_FARM_CREDIT_PER_BLOCK.get());
					gainXp();
					roundActions++;
					advanceCursor();
					return;
				}
			}

			// 游标前进
			if (advanceCursor())
			{
				finishRound();
				return;
			}
		}
	}

	// 收集一棵树的原木（BFS 连通，上限 64）与树冠树叶，入队
	private void enqueueTree(BlockPos start)
	{
		Block logBlock = level.getBlockState(start).getBlock();
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		java.util.Set<BlockPos> visited = new java.util.HashSet<>();
		queue.add(start);
		visited.add(start);
		int minX = start.getX(), maxX = start.getX();
		int minY = start.getY(), maxY = start.getY();
		int minZ = start.getZ(), maxZ = start.getZ();
		while (!queue.isEmpty() && chopLogs.size() < CHOP_LOG_LIMIT)
		{
			BlockPos cur = queue.poll();
			if (chopLogs.size() >= CHOP_LOG_LIMIT) break;
			chopLogs.add(cur);
			minX = Math.min(minX, cur.getX()); maxX = Math.max(maxX, cur.getX());
			minY = Math.min(minY, cur.getY()); maxY = Math.max(maxY, cur.getY());
			minZ = Math.min(minZ, cur.getZ()); maxZ = Math.max(maxZ, cur.getZ());
			for (int d = 0; d < DIR.length; d++)
			{
				BlockPos next = cur.offset(DIR[d]);
				if (visited.add(next) && level.getBlockState(next).getBlock() == logBlock)
				{
					queue.add(next);
				}
			}
		}
		// 树冠树叶：以原木包围盒 XZ±4、Y 向上扩 8 收集选中树种树叶（上限 256）
		Block leavesBlock = leavesOf(treeTypeOf(logBlock));
		if (leavesBlock != null)
		{
			for (int x = minX - 4; x <= maxX + 4; x++)
			{
				for (int z = minZ - 4; z <= maxZ + 4; z++)
				{
					for (int y = minY; y <= maxY + 8 && chopLeaves.size() < 256; y++)
					{
						BlockPos p = new BlockPos(x, y, z);
						if (level.getBlockState(p).getBlock() == leavesBlock)
						{
							chopLeaves.add(p);
						}
					}
				}
			}
		}
	}

	// 原木方块 -> 树种
	private static TreeType treeTypeOf(Block log)
	{
		for (TreeType t : TreeType.values())
		{
			if (logOf(t) == log) return t;
		}
		return TreeType.OAK;
	}

	// 处理一批砍树方块（先原木后树叶）；返回是否仍有剩余
	private boolean processChopBatch()
	{
		// C7：创造模式每 tick 动作预算上限（4 块），避免砍树 12 块/tick 的 setBlock 风暴
		int budget = currentMode() == 2 ? Math.min(CHOP_BATCH, CREATIVE_BLOCK_BUDGET) : CHOP_BATCH;
		int done = 0;
		while (done < budget)
		{
			BlockPos p = !chopLogs.isEmpty() ? chopLogs.poll() : chopLeaves.poll();
			if (p == null) return false;
			BlockState st = level.getBlockState(p);
			if (st.isAir())
			{
				continue;   // 已被砍/已衰变消失
			}
			List<ItemStack> drops = InventoryManager.getBlockDrops(level, p, st);
			boolean room = true;
			for (ItemStack stack : drops)
			{
				if (!InventoryManager.canDeposit(nearbyChests, stack))
				{
					room = false;
					break;
				}
			}
			if (!room)
			{
				// 箱子满了：该块压回队首，停滞等待
				if (!chopLogs.isEmpty()) chopLogs.addFirst(p);
				else chopLeaves.addFirst(p);
				clearHand();
				setState(PlotState.WAITING_FOR_CHEST);
				return true;
			}
			for (ItemStack stack : drops)
			{
				InventoryManager.depositItems(nearbyChests, stack);
			}
			level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			deductCredits(Config.WORK_FARM_CREDIT_PER_BLOCK.get());
			gainXp();
			roundActions++;
			done++;
		}
		return !chopLogs.isEmpty() || !chopLeaves.isEmpty();
	}

	// 一整轮扫完：结算本轮是否有动作；无动作进入空轮节流
	private void finishRound()
	{
		fieldBusy = roundActions > 0;
		roundActions = 0;
		if (!fieldBusy) idleRescanTicks = 1;
		cursorRow = 0;
		cursorCol = 0;
		record = record.withCursor(0, 0);
		missingSaplings = false;
		setState(PlotState.HARVEST);
		setHandTool();
	}

	// 畜牧侧实时探测：屠宰/补种/繁殖任一有活（便宜：实体扫描 + 箱子计数，每动作调用）
	private boolean livestockHasWork()
	{
		for (LivestockType t : livestockTypes)
		{
			List<Animal> animals = penAnimals(t);
			int adults = adultCount(animals);
			if (adults > Config.WORK_FARM_MAX_ADULTS.get()) return true;      // 需屠宰
			if (adults < 2) return true;                                       // 需补种（免费，不算缺料）
			List<Animal> adultsList = animals.stream().filter(a -> !a.isBaby()).toList();
			if (adultsList.size() >= 2 && animals.size() < Config.WORK_FARM_MAX_TOTAL.get()
					&& !onBreedCooldown(t)
					&& (currentMode() == 2 || InventoryManager.countItems(nearbyChests, feedOf(t)) > 0))
			{
				return true;                                                   // 可繁殖且有料
			}
		}
		return false;
	}

	// 田间侧探测：整田扫描找「是否有活」（较贵，节流调用；调用前需 refreshChests）
	private boolean fieldHasWorkQuick()
	{
		if (!hasFieldWork()) return false;
		boolean forestry = isForestryMode();
		int rows = rows(), cols = cols();
		for (int row = 0; row < rows; row++)
		{
			for (int col = 0; col < cols; col++)
			{
				BlockPos pos = cellPos(row, col);
				if (cellOutsideBuildHeight(pos)) continue;
				BlockState bs = level.getBlockState(pos);
				BlockState below = level.getBlockState(pos.below());
				if (isSelectedLog(bs)) return true;                            // 有树可砍
				if (bs.getBlock() instanceof CropBlock cb
						&& cb.getAge(bs) >= cb.getMaxAge()
						&& isSelectedCrop(bs.getBlock())) return true;         // 有成熟作物
				if (isRemovableVegetation(bs)) return true;                    // 有杂物可清
				if (bs.isAir())
				{
					if (forestry && isTreeSpot(row, col) && isSoilForSapling(below)
							&& pickPlantableTree() != null) return true;       // 可种树（有树苗）
					if (hasCrops() && isTillableSoil(below)
							&& (!forestry || !isTreeSpot(row, col))) return true; // 可翻地
					if (hasCrops() && below.is(Blocks.FARMLAND)
							&& pickPlantable() != null) return true;           // 可种植（有种子）
				}
			}
		}
		return false;
	}

	// 畜牧轮结束/缺饲料时：节流刷新田间探测（10 tick 一次）；返回刷新后的 fieldBusy
	private boolean maybeRefreshFieldBusy()
	{
		idleRescanTicks++;
		if (idleRescanTicks < IDLE_RESCAN_INTERVAL) return fieldBusy;
		idleRescanTicks = 0;
		refreshChests();
		fieldBusy = fieldHasWorkQuick();
		return fieldBusy;
	}

	// 布置水源
	private void ensureFieldWater()
	{
		if (waterPlaced) return;
		waterPlaced = true;
		int yWater = record.ry() - 1;
		if (yWater < level.getMinBuildHeight()) return;
		int rxMin = record.rx1() + insetX();
		int rxMax = record.rx2() - insetX();
		int rzMin = record.rz1() + insetZ();
		int rzMax = record.rz2() - insetZ();

		// 地块内已经有水：不再重复布置
		for (int x = rxMin; x <= rxMax; x++)
		{
			for (int z = rzMin; z <= rzMax; z++)
			{
				if (level.getBlockState(new BlockPos(x, yWater, z)).getBlock() == Blocks.WATER)
				{
					return;
				}
			}
		}

		// 网格铺水（步距 9：两个水之间空 8 格）
		for (int x = rxMin; x <= rxMax; x += 9)
		{
			int wx = Math.min(x + 4, rxMax);
			for (int z = rzMin; z <= rzMax; z += 9)
			{
				int wz = Math.min(z + 4, rzMax);
				BlockPos wp = new BlockPos(wx, yWater, wz);
				BlockState ws = level.getBlockState(wp);
				if (ws.isAir() || isTillableSoil(ws))
				{
					level.setBlock(wp, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
		LOGGER.info("NeoSim-FarmTask: laid water for field at {}", boxPos());
	}

	// 缺种子：游标冻结整体等待，每 tick 重查箱子；种子补上立即恢复种植
	private void doWaitSeed()
	{
		refreshChests();
		if (pickPlantable() != null)
		{
			// 种子补上：从当前游标继续种植
			setState(PlotState.PLANT);
			setHandTool();
		}
		else
		{
			// 游标冻结，保持等待；清空手持
			clearHand();
		}
	}

	// 屠宰->补种->繁殖（各环节从育种游标起轮转，缺料跳过物种继续轮转）
	private void doLivestock()
	{
		refreshChests();
		// 本轮物种实体缓存重建（每 tick 每物种只扫一次）
		penCache.clear();
		if (++herdTimer >= 20)
		{
			herdTimer = 0;
			for (LivestockType t : livestockTypes)
			{
				herdAnimals(t);
			}
		}
		// 先查后等：本轮无活立即让位，不烧动作延迟
		if (!livestockHasWork())
		{
			setHandAnim(0.0F);
			return;
		}
		while (true)
		{
			long now = System.currentTimeMillis();
			if (animateHand(now)) return;
			int n = livestockTypes.size();
			if (n == 0) { setHandAnim(0.0F); return; }

			// 超员屠宰
			for (int i = 0; i < n; i++)
			{
				LivestockType t = livestockTypes.get((livestockIndex + i) % n);
				List<Animal> animals = penAnimals(t);
				if (adultCount(animals) > Config.WORK_FARM_MAX_ADULTS.get())
				{
					if (!slaughterAdult(adultOf(animals)))
					{
						// 箱子满了：停滞等待
						clearHand();
						setState(PlotState.WAITING_FOR_CHEST);
					}
					return;
				}
			}

			// 起步补种
			for (int i = 0; i < n; i++)
			{
				LivestockType t = livestockTypes.get((livestockIndex + i) % n);
				List<Animal> animals = penAnimals(t);
				int adults = adultCount(animals);
				if (adults < 2)
				{
					spawnStarterAnimals(t, 2 - adults);
					return;
				}
			}

			// 喂食繁殖（冷却中跳过；缺料跳过该物种继续轮转；成功后游标前进）
			boolean anyMissingFeed = false;
			for (int i = 0; i < n; i++)
			{
				LivestockType t = livestockTypes.get((livestockIndex + i) % n);
				if (onBreedCooldown(t)) continue;
				List<Animal> animals = penAnimals(t);
				List<Animal> adults = animals.stream().filter(a -> !a.isBaby()).toList();
				if (adults.size() >= 2 && animals.size() < Config.WORK_FARM_MAX_TOTAL.get())
				{
					if (!tryBreed(t, adults.get(0), adults.get(1)))
					{
						// 该物种缺饲料：标记并继续试下一种（不再卡死轮转）
						anyMissingFeed = true;
					}
					else
					{
						livestockIndex = (livestockIndex + i + 1) % n;
						return;
					}
				}
			}

			// 全部物种都试过：全部缺料 -> 整体等待；否则无事可做原地歇息
			if (anyMissingFeed)
			{
				setState(PlotState.WAITING_FEED);
				clearHand();
			}
			else
			{
				// 无事可做（全冷却/无成年/总数已满）：原地歇息
				setHandAnim(0.0F);
			}
			return;
		}
	}

	// 该物种是否在繁殖冷却中
	private boolean onBreedCooldown(LivestockType t)
	{
		int cd = Config.WORK_FARM_BREED_COOLDOWN_SECONDS.get();
		if (cd <= 0) return false;
		Long last = lastBreedTime.get(t);
		return last != null && System.currentTimeMillis() - last < cd * 1000L;
	}

	// 缺饲料：整体等待，每 tick 重查；任一物种恢复可繁殖立即回到畜牧轮
	private void doWaitFeed()
	{
		refreshChests();
		if (livestockHasWork())
		{
			setState(PlotState.RAISE);
			setHandTool();
		}
		else
		{
			clearHand();
		}
	}

	// 围栏内某类牲畜（每 tick 每物种缓存一次）
	private List<Animal> penAnimals(LivestockType type)
	{
		return penCache.computeIfAbsent(type, this::scanPen);
	}

	private List<Animal> scanPen(LivestockType type)
	{
		List<Animal> out = new ArrayList<>();
		for (Animal a : level.getEntitiesOfClass(Animal.class, penAabb()))
		{
			if (animalClass(type).isInstance(a) && a.isAlive()) out.add(a);
		}
		return out;
	}

	// 成年数
	private static int adultCount(List<Animal> animals)
	{
		int n = 0;
		for (Animal a : animals)
		{
			if (!a.isBaby()) n++;
		}
		return n;
	}

	// 取一只成年牲畜
	private static Animal adultOf(List<Animal> animals)
	{
		for (Animal a : animals)
		{
			if (!a.isBaby()) return a;
		}
		return animals.get(0);
	}

	// 走失牲畜牵回圈内
	private void herdAnimals(LivestockType type)
	{
		AABB big = penAabb().inflate(24.0D);
		for (Animal a : level.getEntitiesOfClass(Animal.class, big))
		{
			if (!animalClass(type).isInstance(a) || !a.isAlive()) continue;
			if (!penAabb().contains(a.position()))
			{
				BlockPos p = findSpawnPos();
				a.moveTo(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, a.getYRot(), a.getXRot());
				a.getNavigation().stop();
			}
		}
	}

	// 围栏范围：矩形外扩2格，Y为地表附近
	private AABB penAabb()
	{
		return new AABB(record.rx1() - 2.0D, record.ry() - 1.0D, record.rz1() - 2.0D,
				record.rx2() + 3.0D, record.ry() + 4.0D, record.rz2() + 3.0D);
	}

	// 屠宰成年牲畜：掉落物直接入箱（箱子满返回false），扣款，升级
	private boolean slaughterAdult(Animal animal)
	{
		List<ItemStack> drops = getAnimalDrops(animal);
		for (ItemStack stack : drops)
		{
			if (!InventoryManager.canDeposit(nearbyChests, stack)) return false;
		}
		for (ItemStack stack : drops)
		{
			InventoryManager.depositItems(nearbyChests, stack);
		}
		animal.discard();
		deductCredits(Config.WORK_FARM_CREDIT_PER_BLOCK.get());
		gainXp();
		return true;
	}

	// 对应饲料
	private static Item feedOf(LivestockType type)
	{
		return switch (type)
		{
			case CHICKEN -> Items.WHEAT_SEEDS;
			case PIG -> Items.CARROT;
			case COW -> Items.WHEAT;
			case SHEEP -> Items.WHEAT;
		};
	}

	// 喂食繁殖：消耗该牲畜的对应饲料（创造模式免饲料）
	private boolean tryBreed(LivestockType type, Animal a1, Animal a2)
	{
		Item feed = feedOf(type);
		if (currentMode() != 2 && InventoryManager.countItems(nearbyChests, feed) <= 0) return false;
		AgeableMob baby = a1.getBreedOffspring(level, a2);
		if (baby == null) return false;
		if (currentMode() != 2)
		{
			InventoryManager.extractItem(nearbyChests, feed, 1);
		}
		a1.setInLove(null);
		a2.setInLove(null);
		baby.setAge(-Config.WORK_FARM_BABY_GROW_MINUTES.get() * 1200);
		baby.moveTo(a1.getX(), a1.getY(), a1.getZ(), level.random.nextFloat() * 360.0F, 0.0F);
		level.addFreshEntity(baby);
		level.broadcastEntityEvent(a1, (byte) 18);
		deductCredits(Config.WORK_FARM_CREDIT_PER_BLOCK.get());
		gainXp();
		lastBreedTime.put(type, System.currentTimeMillis());
		return true;
	}

	// 起步补种：免费生成成年牲畜到2只
	private void spawnStarterAnimals(LivestockType type, int count)
	{
		for (int i = 0; i < count; i++)
		{
			Animal a = createAnimal(type);
			if (a == null) return;
			BlockPos p = findSpawnPos();
			a.moveTo(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D,
					level.random.nextFloat() * 360.0F, 0.0F);
			level.addFreshEntity(a);
		}
	}

	// 圈内随机可落地点（优先双格空气）
	private BlockPos findSpawnPos()
	{
		for (int i = 0; i < 16; i++)
		{
			BlockPos p = randomPenPos();
			if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir())
			{
				return p;
			}
		}
		return randomPenPos();
	}

	// 矩形内随机格子
	private BlockPos randomPenPos()
	{
		int spanX = Math.max(1, record.rx2() - record.rx1());
		int spanZ = Math.max(1, record.rz2() - record.rz1());
		int x = Math.min(record.rx2(), record.rx1() + level.random.nextInt(spanX + 1));
		int z = Math.min(record.rz2(), record.rz1() + level.random.nextInt(spanZ + 1));
		return new BlockPos(x, record.ry(), z);
	}

	// 掉落物：读实体战利品表入箱
	private List<ItemStack> getAnimalDrops(Animal animal)
	{
		if (level.getServer() == null) return List.of();
		ResourceKey<LootTable> key = animal.getLootTable();
		LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
		LootParams params = new LootParams.Builder(level)
				.withParameter(LootContextParams.THIS_ENTITY, animal)
				.withParameter(LootContextParams.ORIGIN, animal.position())
				.withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
				.create(LootContextParamSets.ENTITY);
		return table.getRandomItems(params);
	}

	// 牲畜实体类
	private static Class<? extends Animal> animalClass(LivestockType type)
	{
		return switch (type)
		{
			case CHICKEN -> Chicken.class;
			case PIG -> Pig.class;
			case COW -> Cow.class;
			case SHEEP -> Sheep.class;
		};
	}

	// 新建牲畜实体
	private Animal createAnimal(LivestockType type)
	{
		return switch (type)
		{
			case CHICKEN -> EntityType.CHICKEN.create(level);
			case PIG -> EntityType.PIG.create(level);
			case COW -> EntityType.COW.create(level);
			case SHEEP -> EntityType.SHEEP.create(level);
		};
	}

	// 标记矩形边框不计入范围（矩形太窄没有内部时退回整框）
	private int insetX() { return record.rx2() - record.rx1() + 1 > 2 ? 1 : 0; }
	private int insetZ() { return record.rz2() - record.rz1() + 1 > 2 ? 1 : 0; }

	// 矩形/游标
	private int rows() { return record.rz2() - record.rz1() + 1 - 2 * insetZ(); }
	private int cols() { return record.rx2() - record.rx1() + 1 - 2 * insetX(); }

	// 当前游标的世界坐标
	private BlockPos cellPos(int row, int col)
	{
		return new BlockPos(record.rx1() + insetX() + col, record.ry(), record.rz1() + insetZ() + row);
	}

	// 游标前进：走完一整轮返回true（L10：操作 int 字段，record 仅持久化时同步）
	private boolean advanceCursor()
	{
		int col = cursorCol + 1;
		int row = cursorRow;
		if (col >= cols())
		{
			col = 0;
			row++;
		}
		if (row >= rows())
		{
			cursorRow = 0;
			cursorCol = 0;
			record = record.withCursor(0, 0);
			return true;
		}
		cursorRow = row;
		cursorCol = col;
		return false;
	}

	private void refreshChests()
	{
		nearbyChests = InventoryManager.findNearbyChests(level, boxPos());
		// C8：箱子内容可能变化 → 作物/树苗缓存失效（下轮扫描重算一次）
		plantableComputed = false;
		treePlantableComputed = false;
	}

	// 该作物方块是否属于选中组合
	private boolean isSelectedCrop(Block b)
	{
		for (FarmType t : farmTypes)
		{
			if (cropOf(t) == b) return true;
		}
		return false;
	}

	// 选中作物按种植游标轮转，取箱子里有种子的，全缺返回null（创造模式免种子）
	private FarmType pickPlantable()
	{
		// C8：本轮缓存一次（refreshChests 时失效），整田扫描/逐格判断不再每格每物种扫箱子
		if (plantableComputed) return cachedPlantable;
		plantableComputed = true;
		cachedPlantable = null;
		if (farmTypes.isEmpty()) return null;
		for (int i = 0; i < farmTypes.size(); i++)
		{
			FarmType t = farmTypes.get((plantIndex + i) % farmTypes.size());
			Item seed = seedOf(t);
			if (seed != null && (currentMode() == 2 || InventoryManager.countItems(nearbyChests, seed) > 0))
			{
				cachedPlantable = t;
				return t;
			}
		}
		return null;
	}

	// 6 步格位：两树苗之间空 5 格，树冠 5×5 互不干扰
	private static boolean isTreeSpot(int row, int col)
	{
		return row % TREE_STEP == 0 && col % TREE_STEP == 0;
	}

	// 树苗可种的下方方块（泥土标签或耕地）
	private static boolean isSoilForSapling(BlockState state)
	{
		return state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND);
	}

	// 取箱子里有树苗的选中树种（创造模式免树苗；按 treePlantIndex 轮转，多树种轮流种植）
	private TreeType pickPlantableTree()
	{
		// C8：本轮缓存一次（refreshChests 时失效），整田扫描不再每格每物种扫箱子
		if (treePlantableComputed) return cachedTreePlantable;
		treePlantableComputed = true;
		cachedTreePlantable = null;
		if (forestryTypes.isEmpty()) return null;
		int n = forestryTypes.size();
		for (int i = 0; i < n; i++)
		{
			TreeType t = forestryTypes.get((treePlantIndex + i) % n);
			if (currentMode() == 2 || InventoryManager.countItems(nearbyChests, saplingOf(t)) > 0)
			{
				cachedTreePlantable = t;
				return t;
			}
		}
		return null;
	}

	// 该原木是否属于选中的树种
	private boolean isSelectedLog(BlockState bs)
	{
		if (forestryTypes.isEmpty()) return false;
		for (TreeType t : forestryTypes)
		{
			if (logOf(t) == bs.getBlock()) return true;
		}
		return false;
	}

	// 作物方块
	private static Block cropOf(FarmType type)
	{
		return switch (type)
		{
			case WHEAT -> Blocks.WHEAT;
			case CARROT -> Blocks.CARROTS;
			case POTATO -> Blocks.POTATOES;
			default -> null;
		};
	}

	// 种子
	private static Item seedOf(FarmType type)
	{
		return switch (type)
		{
			case WHEAT -> Items.WHEAT_SEEDS;
			case CARROT -> Items.CARROT;
			case POTATO -> Items.POTATO;
			default -> null;
		};
	}

	// 原木方块
	private static Block logOf(TreeType t)
	{
		return switch (t)
		{
			case OAK -> Blocks.OAK_LOG;
			case BIRCH -> Blocks.BIRCH_LOG;
			case SPRUCE -> Blocks.SPRUCE_LOG;
			case JUNGLE -> Blocks.JUNGLE_LOG;
			case ACACIA -> Blocks.ACACIA_LOG;
			case CHERRY -> Blocks.CHERRY_LOG;
		};
	}

	// 树叶方块
	private static Block leavesOf(TreeType t)
	{
		return switch (t)
		{
			case OAK -> Blocks.OAK_LEAVES;
			case BIRCH -> Blocks.BIRCH_LEAVES;
			case SPRUCE -> Blocks.SPRUCE_LEAVES;
			case JUNGLE -> Blocks.JUNGLE_LEAVES;
			case ACACIA -> Blocks.ACACIA_LEAVES;
			case CHERRY -> Blocks.CHERRY_LEAVES;
		};
	}

	// 树苗方块
	private static Block saplingBlockOf(TreeType t)
	{
		return switch (t)
		{
			case OAK -> Blocks.OAK_SAPLING;
			case BIRCH -> Blocks.BIRCH_SAPLING;
			case SPRUCE -> Blocks.SPRUCE_SAPLING;
			case JUNGLE -> Blocks.JUNGLE_SAPLING;
			case ACACIA -> Blocks.ACACIA_SAPLING;
			case CHERRY -> Blocks.CHERRY_SAPLING;
		};
	}

	// 树苗物品
	private static Item saplingOf(TreeType t)
	{
		return switch (t)
		{
			case OAK -> Items.OAK_SAPLING;
			case BIRCH -> Items.BIRCH_SAPLING;
			case SPRUCE -> Items.SPRUCE_SAPLING;
			case JUNGLE -> Items.JUNGLE_SAPLING;
			case ACACIA -> Items.ACACIA_SAPLING;
			case CHERRY -> Items.CHERRY_SAPLING;
		};
	}

	// 可翻土方块
	private static boolean isTillableSoil(BlockState state)
	{
		return state.is(Blocks.DIRT)
				|| state.is(Blocks.GRASS_BLOCK)
				|| state.is(Blocks.PODZOL)
				|| state.is(Blocks.COARSE_DIRT)
				|| state.is(Blocks.ROOTED_DIRT)
				|| state.is(Blocks.MYCELIUM)
				|| state.is(Blocks.DIRT_PATH);
	}

	// 翻地前需清除的地表杂物
	private static boolean isRemovableVegetation(BlockState bs)
	{
		Block b = bs.getBlock();
		return b == Blocks.SHORT_GRASS
				|| b == Blocks.TALL_GRASS
				|| b == Blocks.FERN
				|| b == Blocks.LARGE_FERN
				|| b == Blocks.DEAD_BUSH
				|| b == Blocks.SNOW
				|| b == Blocks.SWEET_BERRY_BUSH
				|| b == Blocks.TORCHFLOWER
				|| b == Blocks.TORCHFLOWER_CROP
				|| b == Blocks.PITCHER_PLANT
				|| b == Blocks.PITCHER_CROP
				|| b == Blocks.PINK_PETALS
				|| bs.is(BlockTags.FLOWERS);
	}
}

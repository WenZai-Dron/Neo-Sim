package com.wenzai.neosim.compat.sable;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

// Sable（子世界）物理适配器 —— 纯反射实现：本类不 import 任何 dev.ryanhcode.sable 类型，
// 因此 Neo-Sim 的编译期与运行期都不依赖任何外部模组
// 仅在 Sable 已加载时，PhysicsAdapterRegistry 才会反射加载本类（类静态块注册自身）；
// 未安装 Sable 时本类永远不被加载，所有反射句柄也不会解析
//
// 当前仅保留与子世界无写交互的辅助能力（世界坐标投影、NPC 跟踪、子世界判定）；
// 方块读写已通过 isBlockIoSupported() 停用——任何对子世界的访问都可能触发
// Sable 卸载队列自旋（保存卡死），见 关于兼容Sable.md。Sable 修复后改回 true 即可恢复
public class SablePhysicsAdapter implements IPhysicsAdapter
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String MOD_ID = "sable";

	// 世界命中结果：子世界 + 局部坐标（子世界以 Object 持有，保持零编译期依赖）
	private record Hit(Object sub, BlockPos local) {}

	// 类加载时注册：仅当 Sable 已加载，PhysicsAdapterRegistry.init() 才会反射加载本类
	static
	{
		PhysicsAdapterRegistry.register(new SablePhysicsAdapter());
	}

	@Override
	public String modId()
	{
		return MOD_ID;
	}

	@Override
	public boolean isAvailable()
	{
		return Ref.OK && ModList.get().isLoaded(MOD_ID);
	}

	// 方块读写停用：任何对子世界的访问都可能触发 Sable 卸载队列自旋（保存卡死）
	@Override
	public boolean isBlockIoSupported()
	{
		return false;
	}

	// 公开判定：pos 是否位于某子世界 plot 网格内
	@Override
	public boolean isOnStructure(ServerLevel level, BlockPos pos)
	{
		try
		{
			return containingLocal(level, pos) != null;
		}
		catch (Throwable t)
		{
			return fail("isOnStructure", t, false);
		}
	}

	// 情况A：pos 已位于某子世界 plot 网格内
	private static Object containingLocal(ServerLevel level, BlockPos pos) throws Exception
	{
		Object sub = Ref.getContaining.invoke(Ref.helper, level, pos);
		return (sub == null || isRemoved(sub)) ? null : sub;
	}

	private static boolean isRemoved(Object sub) throws Exception
	{
		return (boolean) Ref.subIsRemoved.invoke(sub);
	}

	// 世界坐标 → 局部坐标（与 Sable 自身 runIncludingSubLevels 相同的换算）
	private static BlockPos toLocal(Object pose, BlockPos world) throws Exception
	{
		Vec3 v = (Vec3) Ref.poseInverse.invoke(pose, new Vec3(world.getX(), world.getY(), world.getZ()));
		return new BlockPos((int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z));
	}

	// 情况B：世界 pos 处确有结构方块时返回命中结果
	private static Hit worldHit(ServerLevel level, BlockPos worldPos) throws Exception
	{
		Object box = Ref.bboxCtor.newInstance(
				(double) worldPos.getX(), (double) worldPos.getY(), (double) worldPos.getZ(),
				worldPos.getX() + 1.0, worldPos.getY() + 1.0, worldPos.getZ() + 1.0);
		Iterable<?> subs = (Iterable<?>) Ref.getAllIntersecting.invoke(Ref.helper, level, box);
		for (Object sub : subs)
		{
			if (isRemoved(sub)) continue;
			BlockPos local = toLocal(Ref.subLogicalPose.invoke(sub), worldPos);
			if (!plotGet(Ref.subGetPlot.invoke(sub), local).isAir())
			{
				return new Hit(sub, local);
			}
		}
		return null;
	}

	// plot 内读取：只碰 plot 自己的 holder 区块，绝不触发父级 chunk 加载
	private static BlockState plotGet(Object plot, BlockPos local) throws Exception
	{
		LevelChunk chunk = plotChunk(plot, local);
		return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(local);
	}

	// 取 plot 的 holder 区块（绝对局部坐标 → plot 内索引）
	private static LevelChunk plotChunk(Object plot, BlockPos local) throws Exception
	{
		ChunkPos inPlot = (ChunkPos) Ref.plotToLocal.invoke(plot, new ChunkPos(local.getX() >> 4, local.getZ() >> 4));
		return (LevelChunk) Ref.plotGetChunk.invoke(plot, inPlot);
	}

	@Override
	public BlockState getBlockState(ServerLevel level, BlockPos pos)
	{
		try
		{
			Object local = containingLocal(level, pos);
			if (local != null)
			{
				// 局部空间：结构方块（甲板）优先，其次主世界对应位置的建筑方块
				BlockState plotState = plotGet(Ref.subGetPlot.invoke(local), pos);
				if (!plotState.isAir()) return plotState;
				return level.getBlockState(toWorld(level, pos));
			}

			Hit hit = worldHit(level, pos);
			if (hit != null) return plotGet(Ref.subGetPlot.invoke(hit.sub()), hit.local());
			return null;
		}
		catch (Throwable t)
		{
			return fail("getBlockState", t, null);
		}
	}

	@Override
	public boolean setBlock(ServerLevel level, BlockPos pos, BlockState state, int flags)
	{
		try
		{
			Object local = containingLocal(level, pos);
			if (local != null)
			{
				// 世界坐标放置：写入主世界对应位置，绝不触碰 20.48M plot 区块
				level.setBlock(toWorld(level, pos), state, flags);
				return true;
			}
			Hit hit = worldHit(level, pos);
			if (hit == null) hit = worldHit(level, pos.below());
			if (hit != null)
			{
				level.setBlock(pos, state, flags);
				return true;
			}
			return false;
		}
		catch (Throwable t)
		{
			return fail("setBlock", t, false);
		}
	}

	@Override
	public boolean destroyBlock(ServerLevel level, BlockPos pos)
	{
		try
		{
			Object local = containingLocal(level, pos);
			if (local != null)
			{
				level.setBlock(toWorld(level, pos), Blocks.AIR.defaultBlockState(), 3);
				return true;
			}
			Hit hit = worldHit(level, pos);
			if (hit != null)
			{
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
				return true;
			}
			return false;
		}
		catch (Throwable t)
		{
			return fail("destroyBlock", t, false);
		}
	}

	// 把 NPC 登记进子世界跟踪：设置 plotPosition（局部坐标），EntityMixin.tick 会把它投影到
	// 甲板的世界坐标并建立碰撞（随船体姿态站立）
	@Override
	public void attachNpc(ServerLevel level, Entity npc, BlockPos localPos)
	{
		if (npc == null || localPos == null) return;
		try
		{
			Object sub = Ref.getContaining.invoke(Ref.helper, level, localPos);
			if (sub == null || isRemoved(sub)) return;
			if (Ref.stickExtClass.isInstance(npc))
			{
				Ref.stickSetPlotPosition.invoke(npc, Vec3.atCenterOf(localPos));
			}
			if (Ref.moveExtClass.isInstance(npc))
			{
				Ref.moveSetTrackingSubLevel.invoke(npc, sub);
			}
		}
		catch (Throwable t)
		{
			fail("attachNpc", t, false);
		}
	}

	// 局部坐标 → 世界坐标（供 NPC 生成/导航等需要世界坐标的场景）
	@Override
	public BlockPos toWorld(ServerLevel level, BlockPos localPos)
	{
		try
		{
			Object sub = containingLocal(level, localPos);
			if (sub == null) return localPos;
			Vec3 v = (Vec3) Ref.poseForward.invoke(Ref.subLogicalPose.invoke(sub),
					new Vec3(localPos.getX() + 0.5, localPos.getY() + 0.5, localPos.getZ() + 0.5));
			return new BlockPos((int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z));
		}
		catch (Throwable t)
		{
			return fail("toWorld", t, localPos);
		}
	}

	// 反射异常统一处理：隔离失败、避免刷屏，并返回调用方期望的原版降级值
	private static <T> T fail(String where, Throwable t, T fallback)
	{
		if (Ref.OK) LOGGER.debug("NeoSim-SablePhysicsAdapter: {} 反射调用失败，已降级原版", where, t);
		return fallback;
	}

	// 反射句柄：仅在 Sable 已加载时解析一次；解析失败即视为不可用（isAvailable=false）
	private static final class Ref
	{
		static final boolean OK;
		static final Object helper;
		static final Class<?> stickExtClass;
		static final Class<?> moveExtClass;
		static final Method getContaining;
		static final Method getAllIntersecting;
		static final Method subIsRemoved;
		static final Method subGetPlot;
		static final Method subLogicalPose;
		static final Method poseInverse;
		static final Method poseForward;
		static final Constructor<?> bboxCtor;
		static final Method plotToLocal;
		static final Method plotGetChunk;
		static final Method stickSetPlotPosition;
		static final Method moveSetTrackingSubLevel;

		static
		{
			boolean ok = false;
			Object h = null;
			Class<?> stick = null;
			Class<?> move = null;
			Method gc = null, gai = null, sir = null, sgp = null, slp = null;
			Method pinv = null, pfwd = null, ptl = null, pgc = null, sspp = null, msts = null;
			Constructor<?> bc = null;
			try
			{
				Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
				Field field = sable.getField("HELPER");
				h = field.get(null);
				Class<?> helperClass = h.getClass();
				Class<?> sub = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
				Class<?> plot = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
				Class<?> bbox = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3d");
				stick = Class.forName("dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension");
				move = Class.forName("dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension");

				gc = findMethod(helperClass, "getContaining", Level.class, BlockPos.class);
				gai = findMethod(helperClass, "getAllIntersecting", Level.class, bbox);
				bc = bbox.getConstructor(double.class, double.class, double.class, double.class, double.class, double.class);
				sir = sub.getMethod("isRemoved");
				sgp = sub.getMethod("getPlot");
				slp = sub.getMethod("logicalPose");
				Class<?> pose = slp.getReturnType();
				pinv = pose.getMethod("transformPositionInverse", Vec3.class);
				pfwd = pose.getMethod("transformPosition", Vec3.class);
				ptl = plot.getMethod("toLocal", ChunkPos.class);
				pgc = plot.getMethod("getChunk", ChunkPos.class);
				sspp = stick.getMethod("sable$setPlotPosition", Vec3.class);
				msts = move.getMethod("sable$setTrackingSubLevel", sub);
				ok = h != null;
			}
			catch (Throwable t)
			{
				LOGGER.warn("NeoSim-SablePhysicsAdapter: Sable API 反射解析失败，兼容层停用", t);
			}
			OK = ok;
			helper = h;
			stickExtClass = stick;
			moveExtClass = move;
			getContaining = gc;
			getAllIntersecting = gai;
			subIsRemoved = sir;
			subGetPlot = sgp;
			subLogicalPose = slp;
			poseInverse = pinv;
			poseForward = pfwd;
			bboxCtor = bc;
			plotToLocal = ptl;
			plotGetChunk = pgc;
			stickSetPlotPosition = sspp;
			moveSetTrackingSubLevel = msts;
		}
	}

	// 反射方法查找：按名字 + 参数可赋值性挑选最具体的重载（Sable 同名重载很多，
	// 直接 getMethod 会因 BlockPos/Vec3i/Position 这类重载歧义而失败）
	private static Method findMethod(Class<?> owner, String name, Class<?>... args) throws NoSuchMethodException
	{
		Method best = null;
		for (Method m : owner.getMethods())
		{
			if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
			Class<?>[] ps = m.getParameterTypes();
			boolean ok = true;
			for (int i = 0; i < ps.length; i++)
			{
				if (!box(ps[i]).isAssignableFrom(box(args[i])))
				{
					ok = false;
					break;
				}
			}
			if (!ok) continue;
			if (best == null || moreSpecific(m.getParameterTypes(), best.getParameterTypes())) best = m;
		}
		if (best == null) throw new NoSuchMethodException(owner.getName() + "#" + name);
		return best;
	}

	private static boolean moreSpecific(Class<?>[] a, Class<?>[] b)
	{
		for (int i = 0; i < a.length; i++)
		{
			if (!box(b[i]).isAssignableFrom(box(a[i]))) return false;
		}
		return true;
	}

	private static Class<?> box(Class<?> c)
	{
		if (!c.isPrimitive()) return c;
		if (c == int.class) return Integer.class;
		if (c == double.class) return Double.class;
		if (c == float.class) return Float.class;
		if (c == long.class) return Long.class;
		if (c == boolean.class) return Boolean.class;
		if (c == byte.class) return Byte.class;
		if (c == short.class) return Short.class;
		if (c == char.class) return Character.class;
		return c;
	}
}

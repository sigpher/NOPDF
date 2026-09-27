package com.github.barteksc.pdfviewer.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分块的两层缓存：「这一轮用到的」与「其余的」。
 *
 * <p>纯 JVM，不碰 {@code Bitmap} / {@code RectF}，所以可以单测（见 {@code PartCacheTest}）。
 *
 * <h3>为什么不能用 PriorityQueue</h3>
 *
 * <p>这里原来（0.5.5 之前）是两个按 {@code cacheOrder} 排序的 {@code PriorityQueue}。
 * {@code PriorityQueue} 有两个性质会在「同一格被画了两遍」之后把缓存弄坏，而症状是
 * **整页永久空白、只有重载文档才能恢复**——也就是「切换主题按钮就好了」那条线索指向的东西：
 *
 * <ol>
 *   <li>{@code remove(Object)} 删掉的是「<b>等于</b>该对象的某个元素」，<b>不是该对象本身</b>。
 *       原来的 {@code upPartIfContained} 把自己找到的那个对象传进去，可缓存里一旦有两个
 *       page+bounds 相同的 {@code PagePart}（同一格渲染两遍就会产生一对），它就可能删掉
 *       <b>另一个</b>——于是同一个对象同时留在 passive 和 active 两层里。</li>
 *   <li>{@code AbstractQueue.addAll} 是逐个 {@code offer}，<b>允许重复</b>。于是
 *       {@code makeANewSet()} 的 {@code passiveCache.addAll(activeCache)} 会把那个对象
 *       再塞一份进 passiveCache。</li>
 *   <li>下一轮 {@code makeAFreeSpace()} 从 passiveCache 轮询出一份并
 *       {@code bitmap.recycle()}——<b>另一份还在缓存里，而它的位图已经是死的</b>。</li>
 *   <li>{@code drawPart} 遇到回收过的位图直接 return（不画），而
 *       {@code upPartIfContained} 只按 page+bounds 匹配、从不看位图是否已回收，于是这一格被
 *       当成「已经在缓存里」，<b>永远不会再被请求</b>——那一格永久空白。</li>
 * </ol>
 *
 * <p>只有 {@code recycle()}（切换主题按钮走的就是它）会清空两层，所以才会「切一下主题就好了」。
 *
 * <h3>这里的做法</h3>
 *
 * <p>改用 {@link LinkedHashSet}：<b>集合在结构上不可能装下两个相等的条目</b>，上面整类问题
 * 就不存在了，而不是靠小心翼翼地写对；并且 {@link #add} 会显式保证同一条目只存在于一层。
 * 淘汰顺序也不再依赖 {@code cacheOrder} 这个「会在堆里被就地改掉」的排序键：passive 里的
 * 条目一律比 active 里的老（{@link #newPass()} 把上一轮的 active 降级到 passive 末尾），
 * 所以「先淘汰 passive 的第一个、再淘汰 active 的第一个」就是 LRU。
 *
 * <p>另外，<b>死条目一律当不存在</b>（{@link Adapter#alive}），扫描时顺手清掉。原来的缓存没有
 * 这一步，所以一旦因为上面那条链留下一个死条目，那一格就再也回不来了；有了它，即使将来还有
 * 别的地方能造出死条目，也会被重新请求，<b>不需要重载文档</b>。
 */
public final class PartCache<T> {

    /**
     * 由调用方提供的、与具体条目类型无关的规则。实现必须是纯 JVM 的：{@link #same} 会被用来
     * 拿「探针」（一个位图为 null 的假条目）比较，所以不能假设条目一定有资源。
     */
    public interface Adapter<T> {

        /** 两个条目是否占同一个格子。 */
        boolean same(T a, T b);

        /** 条目背后的资源是否还在。已回收的条目在缓存里必须当作不存在。 */
        boolean alive(T item);
    }

    private final Adapter<T> adapter;

    /** 插在末尾 = 最近用过。{@link #promote} / {@link #add} 都会把条目重新插到末尾。 */
    private final Set<T> active = new LinkedHashSet<T>();

    private final Set<T> passive = new LinkedHashSet<T>();

    public PartCache(Adapter<T> adapter) {
        this.adapter = adapter;
    }

    /** 一轮开始：active 里没被用到的都降级到 passive。 */
    public void newPass() {
        // 先清一遍 passive 的死条目。LinkedHashSet.add 会**拒绝**已经存在的键，所以如果
        // passive 里还躺着一个与待降级条目相等的死条目，那次 add 会被静默丢弃——活的那个被丢掉、
        // 位图成了孤儿，而死条目还占着名额。清一遍就保证降级一定成功。
        reap(passive);
        Iterator<T> it = active.iterator();
        while (it.hasNext()) {
            T item = it.next();
            it.remove();
            if (adapter.alive(item)) {
                passive.add(item);
            }
        }
    }

    /**
     * 新画出来的一格，放进 active。
     *
     * <p><b>必须</b>先清掉另一层里与它相等的条目：两层各留一份的话，淘汰时会 recycle 其中
     * 一份的位图，另一份就成了「{@code drawPart} 跳过、{@link #promote} 又当成命中」的死条目
     * ——也就是上面那段注释描述的永久空白。同样的理由，这里也要清掉 active 里的相等条目：
     * 同一格被渲染两遍时会送来两个相等但不同的对象（见 {@code RenderingHandler} 里
     * {@code pending} 移除与 {@code onBitmapRendered} 之间的那个窗口）。
     *
     * @return 被顶掉的相等条目——它的位图归调用方了，调用方<b>必须</b>回收，否则每次重复渲染
     *         都漏一张 {@code PART_SIZE} 的位图。没有则 null。因为本类保证两层里不会出现
     *         相等的条目（{@link LinkedHashSet} 每个键最多一份），被顶掉的至多一个。
     */
    public T add(T item) {
        reap(passive);
        T displaced = dropEqual(passive, item);
        if (displaced == null) {
            displaced = dropEqual(active, item);
        }
        active.add(item);
        return displaced;
    }

    /**
     * 与 {@code probe} 占同一格、且还活着的条目挪到 active（作为最近用过）。
     *
     * <p>扫描途中遇到的死条目会被顺手清掉，<b>并且不算命中</b>——于是那一格会被重新请求。
     * 这就是「不用重载文档也能自己恢复」的那一步。
     *
     * @return 是否命中一个活着的条目
     */
    public boolean promote(T probe) {
        T match = take(passive, probe);
        if (match == null) {
            match = take(active, probe);
        }
        if (match == null) {
            return false;
        }
        // take() 已经把它从所在的那一层摘掉了；插到 active 末尾即「最近用过」。
        active.add(match);
        return true;
    }

    /**
     * 淘汰到 {@code size() < maxSize} 为止。
     *
     * <p>注意是 {@code <} 而不是 {@code <=}：原实现的条件是 {@code >= CACHE_SIZE} 就淘汰一个，
     * 之后才放新的进来，所以稳态下最多持有 {@code CACHE_SIZE} 个。这里保持同样的语义。
     *
     * @return 被淘汰的条目，调用方负责回收它们的资源
     */
    public List<T> trimTo(int maxSize) {
        List<T> removed = new ArrayList<T>();
        while (size() >= maxSize) {
            T item = pollOldest();
            if (item == null) {
                break;
            }
            removed.add(item);
        }
        return removed;
    }

    /** 画的时候用：passive 在前、active 在后，并跳过死条目。 */
    public List<T> entries() {
        List<T> out = new ArrayList<T>(size());
        for (T item : passive) {
            if (adapter.alive(item)) {
                out.add(item);
            }
        }
        for (T item : active) {
            if (adapter.alive(item)) {
                out.add(item);
            }
        }
        return out;
    }

    public int size() {
        return active.size() + passive.size();
    }

    public void clear() {
        active.clear();
        passive.clear();
    }

    /**
     * 在 {@code set} 里找与 {@code probe} 相等、且还活着的条目，把它摘出来返回。
     *
     * <p>顺带把走到的那一段里的死条目清掉，<b>命中就返回</b>——不整趟扫完。
     *
     * <p>提前返回是安全的，理由有两条：<b>（一）</b>清死条目只是顺手清理，判定「这一格是不是
     * 真的在缓存里」靠的是 {@link Adapter#alive} 那个判断本身，不是清没清掉——所以即使某个死
     * 条目这一趟没被清掉，它下一趟也会被清，而且无论清没清掉，这一格都会因为「不算命中」而被
     * 重新请求（这才是能自己恢复的关键）。<b>（二）</b>「一个死条目排在活条目前面」这种顺序是
     * 正常的，先清掉它再返回后面的活条目，结果不变。
     *
     * <p>原来的 {@code find} 也是命中即返回，所以这里没有引入每轮的额外开销。
     */
    private T take(Set<T> set, T probe) {
        Iterator<T> it = set.iterator();
        while (it.hasNext()) {
            T item = it.next();
            if (!adapter.alive(item)) {
                it.remove();
                continue;
            }
            if (adapter.same(item, probe)) {
                it.remove();
                return item;
            }
        }
        return null;
    }

    /** passive 里的都比 active 里的老，所以先淘汰 passive 的第一个；这就是 LRU。 */
    private T pollOldest() {
        Iterator<T> it = passive.iterator();
        if (it.hasNext()) {
            T item = it.next();
            it.remove();
            return item;
        }
        it = active.iterator();
        if (it.hasNext()) {
            T item = it.next();
            it.remove();
            return item;
        }
        return null;
    }

    /** @return 被摘掉的那一个，没有则 null（{@link Set} 里每个键最多一份，所以至多一个） */
    private T dropEqual(Set<T> set, T item) {
        Iterator<T> it = set.iterator();
        while (it.hasNext()) {
            T candidate = it.next();
            if (adapter.same(candidate, item)) {
                it.remove();
                return candidate;
            }
        }
        return null;
    }

    private void reap(Set<T> set) {
        Iterator<T> it = set.iterator();
        while (it.hasNext()) {
            if (!adapter.alive(it.next())) {
                it.remove();
            }
        }
    }
}

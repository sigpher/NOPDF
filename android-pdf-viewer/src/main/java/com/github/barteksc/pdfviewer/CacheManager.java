/**
 * Copyright 2016 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer;

import android.graphics.Bitmap;
import android.graphics.RectF;

import com.github.barteksc.pdfviewer.model.PagePart;
import com.github.barteksc.pdfviewer.util.PartCache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.github.barteksc.pdfviewer.util.Constants.Cache.CACHE_SIZE;
import static com.github.barteksc.pdfviewer.util.Constants.Cache.THUMBNAILS_CACHE_SIZE;

class CacheManager {

    /**
     * 分块的两层缓存。
     *
     * <p>这里原来（0.5.5 之前）是两个按 {@code cacheOrder} 排序的 {@code PriorityQueue}，那条
     * 链会让「同一个 {@code PagePart} 对象同时存在于两层」，再被淘汰回收掉位图，于是那一格
     * 被 {@code PDFView.drawPart} 跳过、又被 {@link #upPartIfContained} 当成已在缓存里而
     * <b>永远不再被请求</b>——整页永久空白，只有 {@code recycle()}（切换主题按钮）能治好。
     * 完整推导见 {@link PartCache} 的类注释。
     */
    private final PartCache<PagePart> parts = new PartCache<PagePart>(PART_ADAPTER);

    private static final PartCache.Adapter<PagePart> PART_ADAPTER = new PartCache.Adapter<PagePart>() {
        @Override
        public boolean same(PagePart a, PagePart b) {
            // 探针的位图是 null，PagePart.equals 不看位图，所以这样比较是安全的。
            return a.equals(b);
        }

        @Override
        public boolean alive(PagePart item) {
            return isAlive(item);
        }
    };

    private final List<PagePart> thumbnails = new ArrayList<>();

    private final Object passiveActiveLock = new Object();

    public void cachePart(PagePart part) {
        synchronized (passiveActiveLock) {
            // If cache too big, remove and recycle
            trimAndRecycle();
            // 同一格被渲染两遍时，新来的会把旧的顶掉；旧的那张位图就此归我们，必须回收。
            recycle(parts.add(part));
        }
    }

    public void makeANewSet() {
        synchronized (passiveActiveLock) {
            parts.newPass();
        }
    }

    private void trimAndRecycle() {
        List<PagePart> evicted = parts.trimTo(CACHE_SIZE);
        for (int i = 0; i < evicted.size(); i++) {
            recycle(evicted.get(i));
        }
    }

    public void cacheThumbnail(PagePart part) {
        synchronized (thumbnails) {
            reapDeadThumbnails();
            // If cache too big, remove and recycle
            while (thumbnails.size() >= THUMBNAILS_CACHE_SIZE) {
                recycle(thumbnails.remove(0));
            }

            // Then add thumbnail
            addWithoutDuplicates(thumbnails, part);
        }

    }

    /**
     * 与 {@code pageRelativeBounds} 占同一格的分块是否已经在缓存里；命中则把它当作最近用过。
     *
     * <p>位图已被回收的条目<b>不算命中</b>，并且会被清掉——否则 {@code PDFView.drawPart} 会
     * 跳过它（那里有 {@code if (renderedBitmap.isRecycled()) return;}），而这一格又因为这里
     * 返回 true 而永远不会被重新请求，就是一格永久空白。
     *
     * <p>两层的分块一律不是缩略图（{@code PDFView.onBitmapRendered} 按 isThumbnail 分流），
     * 所以探针用 {@code thumbnail=false}；{@code PagePart.equals} 现在也看 thumbnail，两者
     * 必须一致。
     */
    public boolean upPartIfContained(int page, RectF pageRelativeBounds) {
        PagePart probe = new PagePart(page, null, pageRelativeBounds, false, 0);
        synchronized (passiveActiveLock) {
            return parts.promote(probe);
        }
    }

    /**
     * Return true if already contains the described PagePart
     */
    public boolean containsThumbnail(int page, RectF pageRelativeBounds) {
        PagePart probe = new PagePart(page, null, pageRelativeBounds, true, 0);
        synchronized (thumbnails) {
            for (int i = thumbnails.size() - 1; i >= 0; i--) {
                PagePart part = thumbnails.get(i);
                if (!isAlive(part)) {
                    // 死条目当作不存在，清掉让这一格能被重新请求。
                    thumbnails.remove(i);
                    continue;
                }
                if (part.equals(probe)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Add part if it doesn't exist, recycle bitmap otherwise
     */
    private void addWithoutDuplicates(Collection<PagePart> collection, PagePart newPart) {
        for (PagePart part : collection) {
            if (part.equals(newPart)) {
                recycle(newPart);
                return;
            }
        }
        collection.add(newPart);
    }

    private void reapDeadThumbnails() {
        for (int i = thumbnails.size() - 1; i >= 0; i--) {
            if (!isAlive(thumbnails.get(i))) {
                thumbnails.remove(i);
            }
        }
    }

    private static boolean isAlive(PagePart part) {
        Bitmap bitmap = part.getRenderedBitmap();
        return bitmap != null && !bitmap.isRecycled();
    }

    private static void recycle(PagePart part) {
        if (part == null) {
            return;
        }
        Bitmap bitmap = part.getRenderedBitmap();
        // 死条目不用再回收一次；位图也可能为 null（那只是探针，不会进缓存）。
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    public List<PagePart> getPageParts() {
        synchronized (passiveActiveLock) {
            return parts.entries();
        }
    }

    public List<PagePart> getThumbnails() {
        synchronized (thumbnails) {
            return thumbnails;
        }
    }

    public void recycle() {
        synchronized (passiveActiveLock) {
            // entries() 跳过死条目，而死条目的位图本来就已经被回收了。
            List<PagePart> live = parts.entries();
            for (int i = 0; i < live.size(); i++) {
                recycle(live.get(i));
            }
            parts.clear();
        }
        synchronized (thumbnails) {
            for (int i = 0; i < thumbnails.size(); i++) {
                recycle(thumbnails.get(i));
            }
            thumbnails.clear();
        }
    }

}

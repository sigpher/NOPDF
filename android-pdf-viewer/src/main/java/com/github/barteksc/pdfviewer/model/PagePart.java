/**
 * Copyright 2016 Bartosz Schiller
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer.model;

import android.graphics.Bitmap;
import android.graphics.RectF;

public class PagePart {

    private int page;

    private Bitmap renderedBitmap;

    private RectF pageRelativeBounds;

    private boolean thumbnail;

    /**
     * 上一次被用到时的次序标记。
     *
     * <p><b>不再用来决定淘汰顺序</b>：0.5.6 起淘汰顺序由 {@code util.PartCache} 的插入序
     * 决定（见那里的类注释——用 {@code PriorityQueue} 按这个字段排序正是「整页永久空白」的
     * 根因之一，因为排序键会在堆里被就地改掉）。保留字段是为了不改动这个公开模型类的 API。
     */
    private int cacheOrder;

    public PagePart(int page, Bitmap renderedBitmap, RectF pageRelativeBounds, boolean thumbnail, int cacheOrder) {
        super();
        this.page = page;
        this.renderedBitmap = renderedBitmap;
        this.pageRelativeBounds = pageRelativeBounds;
        this.thumbnail = thumbnail;
        this.cacheOrder = cacheOrder;
    }

    @Override
    public String toString() {
        return "PagePart{" +
                "page=" + page +
                ", renderedBitmap=" + renderedBitmap +
                ", pageRelativeBounds=" + pageRelativeBounds +
                ", thumbnail=" + thumbnail +
                ", cacheOrder=" + cacheOrder +
                '}';
    }

    public int getCacheOrder() {
        return cacheOrder;
    }

    public int getPage() {
        return page;
    }

    public Bitmap getRenderedBitmap() {
        return renderedBitmap;
    }

    public RectF getPageRelativeBounds() {
        return pageRelativeBounds;
    }

    public boolean isThumbnail() {
        return thumbnail;
    }

    public void setCacheOrder(int cacheOrder) {
        this.cacheOrder = cacheOrder;
    }

    /**
     * 同一个格子 = 同一页 + 同一块区域 + 同一个是不是缩略图。
     *
     * <p>三件事曾经都是错的：
     * <ul>
     *   <li>没有重写 {@code hashCode}（{@code equals} 相等而 hash 不等），拿它当
     *       {@code Set} / {@code Map} 的键会让去重彻底失效；</li>
     *   <li>不看 {@code thumbnail}，于是「整页大小的缩略图」和「恰好覆盖整页的分块」被当成
     *       同一格；</li>
     *   <li>不重写 {@code hashCode} 加上 {@code PriorityQueue.remove(Object)} 按 equals 删
     *       元素，会让缓存删掉「相等的另一个」而把真正找到的那个留在原地——见
     *       {@code util.PartCache} 的类注释，那条链的终点是整页永久空白。</li>
     * </ul>
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PagePart)) {
            return false;
        }

        PagePart part = (PagePart) obj;
        return part.getPage() == page
                && part.isThumbnail() == thumbnail
                && part.getPageRelativeBounds().left == pageRelativeBounds.left
                && part.getPageRelativeBounds().right == pageRelativeBounds.right
                && part.getPageRelativeBounds().top == pageRelativeBounds.top
                && part.getPageRelativeBounds().bottom == pageRelativeBounds.bottom;
    }

    @Override
    public int hashCode() {
        // equals 用 == 比较浮点，这里用 floatToIntBits 哈希：两处对 +0.0f 一致。
        // 唯一的分歧是 -0.0f（== 判定相等、floatToIntBits 哈希不同），而这四个值是
        // 1f/cols * col（col >= 0）及其和，全都是非负的有限值，不可能是 -0.0f。
        int result = page;
        result = 31 * result + (thumbnail ? 1 : 0);
        result = 31 * result + Float.floatToIntBits(pageRelativeBounds.left);
        result = 31 * result + Float.floatToIntBits(pageRelativeBounds.top);
        result = 31 * result + Float.floatToIntBits(pageRelativeBounds.right);
        result = 31 * result + Float.floatToIntBits(pageRelativeBounds.bottom);
        return result;
    }

}

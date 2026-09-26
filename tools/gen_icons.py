#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NO PDF 图标生成脚本。

从仓库根目录的源图（默认 NoPDF.png）生成整套 Android 图标资源：

  mipmap-<density>/app_ic_launcher[_round].png          legacy 方形/圆形图标（API 21~25 兜底）
  mipmap-<density>/app_ic_launcher_foreground.png       自适应图标前景（108dp 画布）
  mipmap-<density>/app_ic_launcher_dev.png              debug 专用（含 Dev 角标）
  mipmap-<density>/app_ic_launcher_round_dev.png
  mipmap-<density>/app_ic_launcher_foreground_dev.png
  drawable-xxhdpi/app_ic_nopdf[_dev].png                「关于」页用图（120dp）

源图接受两种形态，脚本会自动判别：

1. 「圆角卡片 + 白色四角底」（旧版 NoPDF.png）：直接抠掉四角白底。
2. 「满幅设计稿」（新版 NoPdf 应用图标设计.png）：背景铺满整张画布、四角不是白色。
   脚本会先补上圆角（CARD_CORNER_RATIO）并置于白底，再走同一条抠角流程，
   因此原始设计稿可以原样放进仓库，不必预先裁剪。

卡片底色取自左右竖边内缩 CARD_SAMPLE_INSET 处的像素（跳过近白、取中位数），
用于填充透明像素以免缩小时白边渗色，并供 colors.xml 的
app_ic_launcher_background 使用。实测新版源图：卡片底色 #FD8631，
深色文档图形占卡片 47.7% 宽 / 62.2% 高（与旧版 47.4% / 61.1% 基本一致，
故 ADAPTIVE_CONTENT_DP 无需调整）。

Dev 角标素材固化在 tools/dev_badge.png，脚本不会读取自己生成的图标，
因此可以反复执行。首次使用（或需要更新角标样式）时，先从原始图标中提取一次：

    python tools/gen_icons.py --extract-badge   # 依赖 drawable-xxhdpi 下仍是原始图标

依赖：Pillow（venv 内 `pip install Pillow`；用到 rounded_rectangle，需 >= 8.2）。

用法：
    python tools/gen_icons.py
    python tools/gen_icons.py --source path/to/icon.png
"""

import argparse
import io
import os
import sys
from collections import deque

from PIL import Image, ImageChops, ImageDraw, ImageFilter

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SOURCE = os.path.join(REPO_ROOT, "NoPDF.png")
DEFAULT_BADGE = os.path.join(REPO_ROOT, "tools", "dev_badge.png")
DEFAULT_BADGE_SOURCE = os.path.join(
    REPO_ROOT, "app", "src", "main", "res", "drawable-xxhdpi", "app_ic_nopdf_dev.png")
DEFAULT_BADGE_RELEASE = os.path.join(
    REPO_ROOT, "app", "src", "main", "res", "drawable-xxhdpi", "app_ic_nopdf.png")
DEFAULT_RES_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "res")

DENSITIES = (("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0))

ADAPTIVE_CANVAS_DP = 108       # 自适应图标画布
# 自适应图标前景里文档图形的高度（居中放置），其余区域透明。
# 换算：旧版 86dp 卡片 × 文档高 61.1% ≈ 53dp，落在系统蒙版可见区（居中 72dp）内。
ADAPTIVE_DOC_HEIGHT_DP = 53
# 文档宽高比（留空则由源图自动测量）。
CONTENT_ASPECT = None
LEGACY_ICON_DP = 48            # legacy 启动图标基准尺寸（mdpi）
ABOUT_ICON_DP = 120            # 「关于」页 ImageView 尺寸
ABOUT_ICON_DENSITY = "xxhdpi"

# 满幅设计稿（无白色四角）会被自动裁成圆角卡片再放到白底上，圆角半径 / 边长。
CARD_CORNER_RATIO = 0.214
# 采样卡片底色所用的左右竖边内缩比例。必须落在
# 「圆角半径 < inset < 文档半宽」之间：小于圆角半径会采到白底，
# 大于文档半宽会采到文档图形。文档居中且宽约 48%，故 0.24 安全。
CARD_SAMPLE_INSET = 0.24

WHITE_TOLERANCE = 12           # 判定「背景白」的容差
BADGE_WIDTH_RATIO = 0.254      # Dev 角标宽度 / 图标宽度（沿用旧图实测比例）
BADGE_CORNER_MARGIN = 0.0      # 角标贴右下角，无额外内边距
BADGE_CIRCLE_CENTER_RATIO = 0.74   # 圆形/自适应可见区内，角标中心相对位置
BADGE_CIRCLE_SIZE_RATIO = 0.20     # 圆形/自适应可见区内，角标宽度占比
BADGE_ADAPTIVE_SIZE_RATIO = 0.16    # 自适应前景专用：可见区比圆形 legacy 更挤，角标略小

# PNG 调色板化（PNG-8）参数。见 save_icon / _palette_error_ok。
PALETTE_COLORS = 256
PALETTE_P99_RGB_ERROR = 16          # 可见像素 RGB 误差的 99 分位上限（0~255）
PALETTE_MAX_ALPHA_ERROR = 48        # alpha 误差上限，超出说明素材不适合压成调色板


def log(msg):
    print("[gen_icons] %s" % msg)


def is_near_white(p, tol=WHITE_TOLERANCE):
    limit = 255 - tol
    return p[0] >= limit and p[1] >= limit and p[2] >= limit


def white_corner_mask(im, tol=WHITE_TOLERANCE):
    """泛洪填充出「与四角相连的白色背景」，返回 (L 掩码, 像素数)。

    掩码取值 255=保留原像素，0=判定为白色背景。被抠像素以 4 邻域连通，
    因此内部的白色（例如文字）不会被误抠。
    """
    im = im.convert("RGBA")
    w, h = im.size
    px = im.load()
    removed = bytearray(w * h)
    queue = deque()

    def push(x, y):
        if not removed[y * w + x] and is_near_white(px[x, y], tol):
            removed[y * w + x] = 1
            queue.append((x, y))

    for x in range(w):
        push(x, 0)
        push(x, h - 1)
    for y in range(h):
        push(0, y)
        push(w - 1, y)

    while queue:
        x, y = queue.popleft()
        if x > 0:
            push(x - 1, y)
        if x < w - 1:
            push(x + 1, y)
        if y > 0:
            push(x, y - 1)
        if y < h - 1:
            push(x, y + 1)

    count = sum(removed)
    alpha = Image.frombytes("L", (w, h), bytes(255 if not v else 0 for v in removed))
    return alpha, count


def strip_white_corners(im, tol=WHITE_TOLERANCE):
    """把与四角相连的白色背景置为透明，返回 (RGBA 图, 被扣掉的像素数)。"""
    im = im.convert("RGBA")
    w, h = im.size
    alpha, count = white_corner_mask(im, tol)

    if count == 0 or count > w * h * 0.5:
        raise ValueError(
            "源图四角没有可识别的白色背景（抠掉 %d / %d 像素）。"
            "请确认源图为「圆角图形 + 白色底」，或调整 WHITE_TOLERANCE。" % (count, w * h))

    # 轻微羽化，避免缩放到小尺寸时出现硬边
    alpha = alpha.filter(ImageFilter.GaussianBlur(0.6))
    im.putalpha(alpha)
    return im, count


def rounded_rect_mask(w, h, radius, supersample=4):
    """圆角矩形掩码（L 模式，255=圆角矩形内）。"""
    big_w, big_h = w * supersample, h * supersample
    mask = Image.new("L", (big_w, big_h), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, big_w - 1, big_h - 1), radius=radius * supersample, fill=255)
    return mask.resize((w, h), Image.LANCZOS)


def round_card_to_white(im, ratio=CARD_CORNER_RATIO):
    """把满幅设计稿裁成圆角卡片并放到白底上，使其可被后续抠角流程处理。

    设计稿本身没有「卡片」形状（背景铺满整张画布），圆角是由本脚本补上的，
    以便输出与旧版一致的圆角方形图标，并让自适应图标的背景色可以取到卡片底色。
    """
    im = im.convert("RGBA")
    w, h = im.size
    mask = rounded_rect_mask(w, h, int(round(min(w, h) * ratio)))
    white = Image.new("RGBA", (w, h), (255, 255, 255, 255))
    white.paste(im, (0, 0), mask)
    return white


def is_full_bleed(im, tol=WHITE_TOLERANCE):
    """源图四角是否没有可抠的白色背景（即满幅设计稿）。"""
    _, count = white_corner_mask(im, tol)
    return count == 0


def sample_card_color(im, inset=None, tol=WHITE_TOLERANCE):
    """采样卡片底色，返回 (RGB 元组, HEX 字符串)。

    只取卡片**左右两条竖边**上的像素：这两条边在「圆角半径 < 内缩 < 文档半宽」的
    窗口内既不会落到圆角外的白底，也不会落到居中的文档图形上。
    本项目图标文档居中且宽度约 48%，因此该窗口必然存在（见 CARD_SAMPLE_*）。
    近白像素一律跳过，避免圆角外的白底被算进来。

    取每通道的中位数而非均值：均值会被偶发的深色文档边缘或抗锯齿像素拉偏。
    """
    im = im.convert("RGB")
    w, h = im.size
    px = im.load()
    if inset is None:
        inset = CARD_SAMPLE_INSET
    ring = int(min(w, h) * inset)
    samples = []
    for y in range(ring, h - ring):
        for x in (ring, w - 1 - ring):
            p = px[x, y]
            if not is_near_white(p, tol):
                samples.append(p)

    if not samples:
        raise SystemExit(
            "未能从卡片左右边缘采样到底色（内缩 %.1f%%）。请检查 CARD_SAMPLE_INSET "
            "是否落在「圆角半径与文档半宽之间」。" % (inset * 100))
    samples.sort()
    mid = len(samples) // 2
    rgb = (samples[mid][0], samples[mid][1], samples[mid][2])
    return rgb, "#%02X%02X%02X" % rgb


def fill_transparent_with(im, color):
    """把全透明像素的 RGB 填成指定颜色，避免缩小时白边渗色。"""
    im = im.convert("RGBA")
    w, h = im.size
    px = im.load()
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            if p[3] == 0:
                px[x, y] = (color[0], color[1], color[2], 0)
    return im


def scale(im, size):
    return im.resize((size, size), Image.LANCZOS)


def rounded_circle_mask(size, supersample=4):
    big = size * supersample
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big - 1, big - 1), fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def apply_circle_mask(im):
    im = im.convert("RGBA")
    im.putalpha(ImageChops.multiply(im.getchannel("A"), rounded_circle_mask(im.size[0])))
    return im


def extract_badge(dev_path, release_path):
    """从旧 dev 图标与 release 图标的差异区域裁出 Dev 角标位图。"""
    for path in (dev_path, release_path):
        if not os.path.isfile(path):
            raise SystemExit("找不到 Dev 角标来源文件：%s" % path)
    dev = Image.open(dev_path).convert("RGBA")
    rel = Image.open(release_path).convert("RGBA")
    if dev.size != rel.size:
        raise SystemExit("Dev 图标与 release 图标尺寸不一致，无法定位角标")
    diff = ImageChops.difference(dev.convert("RGB"), rel.convert("RGB"))
    box = diff.getbbox()
    if box is None:
        raise SystemExit("Dev 图标与 release 图标内容相同，找不到 Dev 角标区域")
    badge = dev.crop(box)
    if badge.getchannel("A").getbbox() is None:
        raise SystemExit("裁出的 Dev 角标完全透明")
    return badge, box


def paste_badge(canvas, badge, box):
    """把角标缩放到目标框并合成到画布上。"""
    canvas = canvas.convert("RGBA")
    box = tuple(int(round(v)) for v in box)
    w = max(1, box[2] - box[0])
    h = max(1, box[3] - box[1])
    badge = badge.resize((w, h), Image.LANCZOS)
    canvas.alpha_composite(badge, (box[0], box[1]))
    return canvas


def corner_badge_box(size, badge_aspect):
    """右下角贴边（legacy 方形图标、关于页图标）。"""
    w = size * BADGE_WIDTH_RATIO
    h = w * badge_aspect
    return (size - w - size * BADGE_CORNER_MARGIN,
            size - h - size * BADGE_CORNER_MARGIN,
            size - size * BADGE_CORNER_MARGIN,
            size - size * BADGE_CORNER_MARGIN)


def circle_badge_box(size, badge_aspect):
    """圆形可见区内的安全位置（legacy 圆形图标、自适应前景）。"""
    w = size * BADGE_CIRCLE_SIZE_RATIO
    h = w * badge_aspect
    cx = size * BADGE_CIRCLE_CENTER_RATIO
    cy = size * BADGE_CIRCLE_CENTER_RATIO
    return (cx - w / 2.0, cy - h / 2.0, cx + w / 2.0, cy + h / 2.0)


def extract_document(card, bg, luma_ratio=0.55):
    """从卡片中分离出居中的深色文档图形（含其内部的背页、折角、白色文字）。

    做法：以卡片底色的亮度为基准，亮度明显低于底色的像素判定为文档，量出包围盒后
    带一圈软边裁出。软边是为了保住抗锯齿像素，否则缩到 48dp 时文档边缘会出现锯齿。

    返回裁剪后的 RGBA（不含卡片底色）。
    """
    im = card.convert("RGB")
    w, h = im.size
    px = im.load()
    bg_luma = 0.299 * bg[0] + 0.587 * bg[1] + 0.114 * bg[2]
    thresh = bg_luma * luma_ratio

    minx, miny, maxx, maxy = w, h, -1, -1
    mask = Image.new("L", (w, h), 0)
    mpx = mask.load()
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            if 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2] < thresh:
                mpx[x, y] = 255
                if x < minx:
                    minx = x
                if x > maxx:
                    maxx = x
                if y < miny:
                    miny = y
                if y > maxy:
                    maxy = y

    if maxx < 0:
        raise SystemExit(
            "未能在卡片中识别出深色文档图形（阈值亮度 %.0f）。"
            "若文档与底色明度接近，请调整 luma_ratio。" % thresh)

    # 求文档剪影：把包围盒内「非文档」像素从边界做泛洪，未被触及的即为内部区域。
    # 白色文字比底色亮得多，会被判成非文档，直接按亮度取掩码会被挖空，
    # 必须靠这一步把内部空洞补回来。
    bw, bh = maxx - minx + 1, maxy - miny + 1
    outside = bytearray(bw * bh)
    queue = deque()

    def push_out(x, y):
        if not outside[y * bw + x] and not mpx[minx + x, miny + y]:
            outside[y * bw + x] = 1
            queue.append((x, y))

    for x in range(bw):
        push_out(x, 0)
        push_out(x, bh - 1)
    for y in range(bh):
        push_out(0, y)
        push_out(bw - 1, y)
    while queue:
        x, y = queue.popleft()
        if x > 0:
            push_out(x - 1, y)
        if x < bw - 1:
            push_out(x + 1, y)
        if y > 0:
            push_out(x, y - 1)
        if y < bh - 1:
            push_out(x, y + 1)

    for y in range(bh):
        for x in range(bw):
            if not outside[y * bw + x]:
                mpx[minx + x, miny + y] = 255

    # 外扩 1px 再轻微羽化，保住抗锯齿边缘，避免缩到 48dp 时出现锯齿
    pad = max(1, int(round(min(w, h) * 0.002)))
    alpha = mask.crop((minx, miny, maxx + 1, maxy + 1))
    if pad:
        padded = Image.new("L", (bw + pad * 2, bh + pad * 2), 0)
        padded.paste(alpha, (pad, pad))
        alpha = padded.filter(ImageFilter.GaussianBlur(pad * 0.6))

    doc_rgb = im.crop((minx - pad, miny - pad, maxx + 1 + pad, maxy + 1 + pad)).convert("RGBA")
    doc_rgb.putalpha(alpha)
    return doc_rgb


def render_adaptive_foreground(doc, size, aspect=None, height_dp=None):
    """自适应图标前景：只有文档图形，居中放在 108dp 画布上，其余透明。

    卡片底色交给 @color/app_ic_launcher_background 背景层，这样系统蒙版切出的
    外轮廓是干净的，不会出现前景卡片边缘与蒙版叠加形成的双层圆角。
    """
    if aspect is None:
        aspect = doc.size[0] / float(doc.size[1])
    if height_dp is None:
        height_dp = ADAPTIVE_DOC_HEIGHT_DP
    h = int(round(size * height_dp / float(ADAPTIVE_CANVAS_DP)))
    w = max(1, int(round(h * aspect)))
    doc = doc.resize((w, h), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(doc, ((size - w) // 2, (size - h) // 2))
    return canvas


def adaptive_badge_box(canvas_size, badge_aspect, doc_half_w, doc_half_h,
                       size_ratio=BADGE_ADAPTIVE_SIZE_RATIO):
    """自适应前景：角标水平居中放在文档**正下方**，不压文档也不被蒙版裁掉。

    为什么不用右下角：文档高 53dp、宽约 41dp，居中后其外接矩形角点到画布中心
    约 33.6dp，而系统可见圆半径只有 36dp。角标若要贴到右下 45° 方向，必须满足
    doc_diag + 2×half_diag ≤ 36dp——在角标尺寸可读的范围内无解，只能靠缩小文档
    腾地方，那会让 debug 图标的文档比 release 明显变小。

    改为放在文档下方：可见区在文档底边之下还有 36-26.5 = 9.5dp 空间，
    文档左右也各有约 15dp 余量。角标居中放在这里既能完整显示，也无需缩小文档，
    release 与 debug 的文档大小保持一致。
    """
    visible = canvas_size * 72.0 / ADAPTIVE_CANVAS_DP
    cx = cy = canvas_size / 2.0
    r_vis = visible / 2.0

    w = visible * size_ratio
    h = w * badge_aspect

    # 角标顶边紧贴文档底边，再整体下压到可见圆内壁（下面的偏移量都相对圆心）
    top = doc_half_h
    bottom = top + h
    if bottom > r_vis:
        shift = bottom - r_vis
        top -= shift
        bottom -= shift
    if top < doc_half_h * 0.35:
        raise SystemExit(
            "自适应前景里文档下方放不下 Dev 角标（需 %.1fdp，可用 %.1fdp）。"
            "请调小 BADGE_ADAPTIVE_SIZE_RATIO 或 ADAPTIVE_DOC_HEIGHT_DP。" % (h, r_vis - doc_half_h))
    return (cx - w / 2.0, cy + top, cx + w / 2.0, cy + bottom)


def save_icon(im, res_dir, folder, name):
    target_dir = os.path.join(res_dir, folder)
    os.makedirs(target_dir, exist_ok=True)
    path = os.path.join(target_dir, name)
    rgba = im.convert("RGBA")

    truecolor = _encode_png(rgba)
    indexed = None
    try:
        # 平涂 + 抗锯齿的图标用 256 色调色板（PNG-8）能满足需求，体积约为真彩的 1/5~1/10。
        # FASTOCTREE 对 RGBA 会连 alpha 一起量化，并以 tRNS 多级透明保存，因此圆形/自适应
        # 图标边缘的羽化仍然保留（实测 alpha 级数 110 -> 31，均值误差 0.72/255）。
        pal = rgba.quantize(colors=PALETTE_COLORS, method=Image.FASTOCTREE)
        candidate = _encode_png(pal)
        if len(candidate) < len(truecolor) and _palette_error_ok(rgba, pal):
            indexed = candidate
    except Exception:
        indexed = None

    chosen = indexed if indexed is not None else truecolor
    with open(path, "wb") as f:
        f.write(chosen)
    log("写入 %s (%dx%d, %s, %d 字节)" % (
        os.path.relpath(path, REPO_ROOT), im.size[0], im.size[1],
        "PNG-8" if indexed is not None else "PNG-32", len(chosen)))


def _encode_png(im):
    buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def _palette_error_ok(im, pal):
    """调色板化误差是否可接受。

    只看**可见像素**（alpha > 0）的 RGB 误差：全透明像素的 RGB 是填充色，量化后即使不同
    也完全不可见，若一并统计会把圆形图标的误差虚报得很大。同时限制 alpha 误差上限，
    避免渐变素材被压出色带。超出阈值就退回真彩。
    """
    back = pal.convert("RGBA")
    r, g, b = ImageChops.difference(im.convert("RGB"), back.convert("RGB")).split()
    max_rgb = ImageChops.lighter(ImageChops.lighter(r, g), b)

    visible = im.getchannel("A").point(lambda v: 255 if v > 0 else 0)
    visible_count = visible.histogram()[255]
    if visible_count == 0:
        return True
    hist = ImageChops.multiply(max_rgb, visible).histogram()
    cumulative = 0
    p99 = 255
    for value, count in enumerate(hist):
        cumulative += count
        if cumulative >= visible_count * 0.99:
            p99 = value
            break
    if p99 > PALETTE_P99_RGB_ERROR:
        return False
    return ImageChops.difference(
        im.getchannel("A"), back.getchannel("A")).getextrema()[1] <= PALETTE_MAX_ALPHA_ERROR


def main():
    parser = argparse.ArgumentParser(description="生成 NO PDF 的 Android 图标资源")
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="图标源图路径")
    parser.add_argument("--res", default=DEFAULT_RES_DIR, help="app/src/main/res 目录")
    parser.add_argument("--badge", default=DEFAULT_BADGE,
                        help="Dev 角标位图（默认 tools/dev_badge.png）")
    parser.add_argument("--extract-badge", action="store_true",
                        help="从旧图标中提取 Dev 角标位图并写入 --badge 后退出")
    parser.add_argument("--badge-source", default=DEFAULT_BADGE_SOURCE,
                        help="仅 --extract-badge 使用：原始 dev 图标")
    parser.add_argument("--badge-release", default=DEFAULT_BADGE_RELEASE,
                        help="仅 --extract-badge 使用：原始 release 图标")
    args = parser.parse_args()

    if args.extract_badge:
        badge, box = extract_badge(args.badge_source, args.badge_release)
        os.makedirs(os.path.dirname(args.badge), exist_ok=True)
        badge.save(args.badge, "PNG", optimize=True)
        log("已从 %s 的 %s 区域提取 Dev 角标 -> %s (%dx%d)" % (
            os.path.basename(args.badge_source), box, os.path.relpath(args.badge, REPO_ROOT),
            badge.size[0], badge.size[1]))
        return 0

    if not os.path.isfile(args.badge):
        raise SystemExit(
            "找不到 Dev 角标位图 %s。\n"
            "首次使用请先从原始图标中提取（要求 drawable-xxhdpi 下仍是原始图标）：\n"
            "    python tools/gen_icons.py --extract-badge" % args.badge)

    if not os.path.isfile(args.source):
        raise SystemExit("找不到源图：%s" % args.source)

    log("源图：%s" % os.path.relpath(args.source, REPO_ROOT))
    source = Image.open(args.source)
    if is_full_bleed(source):
        log("源图四角没有白色背景，判定为满幅设计稿：自动裁成圆角卡片（圆角 %.1f%%）并置于白底"
            % (CARD_CORNER_RATIO * 100))
        source = round_card_to_white(source)
    card, removed = strip_white_corners(source)
    bg, bg_hex = sample_card_color(card)
    log("已抠掉白色背景 %d 像素；采样卡片底色 %s %s" % (removed, bg_hex, bg))

    card = fill_transparent_with(card, bg)

    # 自适应前景只放文档图形；卡片底色由 @color/app_ic_launcher_background 承担。
    doc = extract_document(card, bg)
    log("已分离文档图形 %dx%d（宽高比 %.3f）" % (doc.size[0], doc.size[1],
                                                doc.size[0] / float(doc.size[1])))
    # 文档在前 108dp 画布上的半宽/半高（像素），供 Dev 角标定位避让文档
    doc_px_h = ADAPTIVE_DOC_HEIGHT_DP
    doc_px_w = doc_px_h * doc.size[0] / float(doc.size[1])

    badge = Image.open(args.badge).convert("RGBA")
    badge_aspect = badge.size[1] / float(badge.size[0])
    log("Dev 角标：%s (%dx%d)" % (
        os.path.relpath(args.badge, REPO_ROOT), badge.size[0], badge.size[1]))

    for folder, factor in DENSITIES:
        legacy_size = int(round(LEGACY_ICON_DP * factor))
        foreground_size = int(round(ADAPTIVE_CANVAS_DP * factor))
        # 各密度下文档在前景画布上的实际半宽/半高（像素）
        k = foreground_size / float(ADAPTIVE_CANVAS_DP)
        doc_half_w = doc_px_w * k / 2.0
        doc_half_h = doc_px_h * k / 2.0
        legacy = scale(card, legacy_size)
        foreground = render_adaptive_foreground(doc, foreground_size)

        # legacy 方形 / 圆形（API 21~25 兜底）
        save_icon(legacy, args.res, "mipmap-" + folder, "app_ic_launcher.png")
        save_icon(apply_circle_mask(legacy), args.res, "mipmap-" + folder,
                  "app_ic_launcher_round.png")

        # 自适应图标前景（Android 8+）
        save_icon(foreground, args.res, "mipmap-" + folder,
                  "app_ic_launcher_foreground.png")

        # debug 变体：方形角标贴右下角；圆形与自适应前景的角标须落在圆形可见区内
        dev_legacy = paste_badge(legacy, badge, corner_badge_box(legacy_size, badge_aspect))
        save_icon(dev_legacy, args.res, "mipmap-" + folder, "app_ic_launcher_dev.png")
        dev_round = paste_badge(legacy, badge, circle_badge_box(legacy_size, badge_aspect))
        save_icon(apply_circle_mask(dev_round), args.res, "mipmap-" + folder,
                  "app_ic_launcher_round_dev.png")
        dev_foreground = paste_badge(
                foreground, badge,
                adaptive_badge_box(foreground_size, badge_aspect,
                                   doc_half_w, doc_half_h,
                                   size_ratio=BADGE_ADAPTIVE_SIZE_RATIO))
        save_icon(dev_foreground, args.res, "mipmap-" + folder,
                  "app_ic_launcher_foreground_dev.png")

    # 「关于」页用图：drawable-<density>/app_ic_nopdf[_dev].png
    factor = dict(DENSITIES)[ABOUT_ICON_DENSITY]
    about_size = int(round(ABOUT_ICON_DP * factor))
    about = scale(card, about_size)
    save_icon(about, args.res, "drawable-" + ABOUT_ICON_DENSITY, "app_ic_nopdf.png")
    save_icon(paste_badge(about, badge, corner_badge_box(about_size, badge_aspect)),
              args.res, "drawable-" + ABOUT_ICON_DENSITY, "app_ic_nopdf_dev.png")

    print("")
    log("完成。请把 colors.xml 中 app_ic_launcher_background 设为 %s" % bg_hex)


if __name__ == "__main__":
    sys.exit(main())

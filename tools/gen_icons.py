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

源图特征（已实测）：RGB 无透明通道、满幅薄荷色圆角方块，仅四角为白底；
圆角半径约占边长 21.4%；深色文档图形宽约占卡片 47.4% 且居中。

Dev 角标素材固化在 tools/dev_badge.png，脚本不会读取自己生成的图标，
因此可以反复执行。首次使用（或需要更新角标样式）时，先从原始图标中提取一次：

    python tools/gen_icons.py --extract-badge   # 依赖 drawable-xxhdpi 下仍是原始图标

用法：
    python tools/gen_icons.py
    python tools/gen_icons.py --source path/to/icon.png
"""

import argparse
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
# 卡片在画布内的边长。取 86dp 是为了让深色文档图形（宽 47.4% / 高 61.1% 卡片）
# 完整落在系统蒙版的可见区（居中 72dp）内——若卡片铺满 108dp，文档图形的
# 左下/右下角会超出环形蒙版被裁掉。
ADAPTIVE_CONTENT_DP = 86
LEGACY_ICON_DP = 48            # legacy 启动图标基准尺寸（mdpi）
ABOUT_ICON_DP = 120            # 「关于」页 ImageView 尺寸
ABOUT_ICON_DENSITY = "xxhdpi"

WHITE_TOLERANCE = 12           # 判定「背景白」的容差
BADGE_WIDTH_RATIO = 0.254      # Dev 角标宽度 / 图标宽度（沿用旧图实测比例）
BADGE_CORNER_MARGIN = 0.0      # 角标贴右下角，无额外内边距
BADGE_CIRCLE_CENTER_RATIO = 0.74   # 圆形/自适应可见区内，角标中心相对位置
BADGE_CIRCLE_SIZE_RATIO = 0.20     # 圆形/自适应可见区内，角标宽度占比


def log(msg):
    print("[gen_icons] %s" % msg)


def is_near_white(p, tol=WHITE_TOLERANCE):
    limit = 255 - tol
    return p[0] >= limit and p[1] >= limit and p[2] >= limit


def strip_white_corners(im, tol=WHITE_TOLERANCE):
    """把与四角相连的白色背景置为透明，返回 (RGBA 图, 被扣掉的像素数)。"""
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

    count = 0
    for y in range(h):
        base = y * w
        for x in range(w):
            if removed[base + x]:
                count += 1

    if count == 0 or count > w * h * 0.5:
        raise SystemExit(
            "源图四角没有可识别的白色背景（抠掉 %d / %d 像素）。"
            "请确认源图为「圆角图形 + 白色底」，或调整 WHITE_TOLERANCE。" % (count, w * h))

    alpha = Image.frombytes("L", (w, h), bytes(255 if not v else 0 for v in removed))
    # 轻微羽化，避免缩放到小尺寸时出现硬边
    alpha = alpha.filter(ImageFilter.GaussianBlur(0.6))
    im.putalpha(alpha)
    return im, count


def sample_mint(im):
    """在图形区域之外、卡片之内采样薄荷底色，返回 HEX 字符串与 RGB 元组。"""
    im = im.convert("RGB")
    w, h = im.size
    px = im.load()
    ring = int(w * 0.06)
    samples = []
    for y in range(h):
        if y < ring or y > h - 1 - ring:
            continue
        for x in (ring, w - 1 - ring):
            samples.append(px[x, y])
    for x in range(ring, w - ring):
        samples.append(px[x, ring])
        samples.append(px[x, h - 1 - ring])

    steps = max(1, len(samples) // 4000)
    samples = samples[::steps]
    r = sum(p[0] for p in samples) // len(samples)
    g = sum(p[1] for p in samples) // len(samples)
    b = sum(p[2] for p in samples) // len(samples)
    return (r, g, b)


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


def render_adaptive_foreground(card, size):
    """自适应图标前景：卡片居中放在 108dp 画布上，四周留白（位于蒙版之外，不会显示）。"""
    content = int(round(size * ADAPTIVE_CONTENT_DP / float(ADAPTIVE_CANVAS_DP)))
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(scale(card, content), ((size - content) // 2, (size - content) // 2))
    return canvas


def adaptive_badge_box(canvas_size, badge_aspect):
    """自适应前景：角标必须落在系统蒙版可见区（居中 72dp 见方）内，否则会被裁掉。"""
    visible = canvas_size * 72.0 / ADAPTIVE_CANVAS_DP
    offset = (canvas_size - visible) / 2.0
    box = circle_badge_box(visible, badge_aspect)
    return (box[0] + offset, box[1] + offset, box[2] + offset, box[3] + offset)


def save_icon(im, res_dir, folder, name):
    target_dir = os.path.join(res_dir, folder)
    os.makedirs(target_dir, exist_ok=True)
    path = os.path.join(target_dir, name)
    im.convert("RGBA").save(path, "PNG", optimize=True)
    log("写入 %s (%dx%d)" % (os.path.relpath(path, REPO_ROOT), im.size[0], im.size[1]))


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
    card, removed = strip_white_corners(Image.open(args.source))
    mint = sample_mint(card)
    mint_hex = "#%02X%02X%02X" % mint
    log("已抠掉白色背景 %d 像素；采样薄荷底色 %s %s" % (removed, mint_hex, mint))

    card = fill_transparent_with(card, mint)
    badge = Image.open(args.badge).convert("RGBA")
    badge_aspect = badge.size[1] / float(badge.size[0])
    log("Dev 角标：%s (%dx%d)" % (
        os.path.relpath(args.badge, REPO_ROOT), badge.size[0], badge.size[1]))

    for folder, factor in DENSITIES:
        legacy_size = int(round(LEGACY_ICON_DP * factor))
        foreground_size = int(round(ADAPTIVE_CANVAS_DP * factor))
        legacy = scale(card, legacy_size)
        foreground = render_adaptive_foreground(card, foreground_size)

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
        dev_foreground = paste_badge(foreground, badge,
                                     adaptive_badge_box(foreground_size, badge_aspect))
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
    log("完成。请把 colors.xml 中 app_ic_launcher_background 设为 %s" % mint_hex)


if __name__ == "__main__":
    sys.exit(main())

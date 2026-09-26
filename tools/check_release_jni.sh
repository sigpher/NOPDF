#!/usr/bin/env bash
#
# 断言 release APK 里 JNI 绑定该有的名字都还在。
#
# 为什么需要这个脚本：libmupdf_java.so 用硬编码的类名/方法名/签名做 JNI 查找
# （FindClass / GetMethodID）。R8 一旦改名或删除其中任何一个，运行时就是
# UnsatisfiedLinkError / NoSuchMethodError，而
#     sh gradlew :app:assembleRelease
# 照样 BUILD SUCCESSFUL，debug 包也照样正常——这类问题**编译期完全查不出来**。
#
# 实际的翻车现场（2026-09，0.5.0 换引擎时）：因为漏了 keep 规则，release dex 里
# Document 只剩 <clinit> + openNativeWithStream，loadPage / countPages /
# needsPassword / authenticatePassword / loadOutline / getMetaData /
# resolveLinkDestination 全部消失，Matrix 被改名成 c.c.a.a.a、
# SeekableInputStream 被改名成 c.c.a.a.b。app 一进阅读页就崩。
#
# 现有的 16 个单元测试挡不住这类问题（ContentTree 刻意不依赖引擎类型），
# 所以这里是唯一的自动化关卡。改动 android-pdf-viewer 的依赖、sourceSets、
# 混淆配置或换 MuPDF 版本后，都请重跑本脚本。
#
# 用法：
#   sh tools/check_release_jni.sh              # 默认检查 arm64 release 包
#   ABI=armeabi-v7a sh tools/check_release_jni.sh
#
# 退出码：0 全部通过；1 有缺失（并列出缺了哪些）。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABI="${ABI:-arm64-v8a}"
APK="$REPO_ROOT/app/build/outputs/apk/release/app-$ABI-release.apk"

die() { echo "错误：$*" >&2; exit 1; }

[ -f "$APK" ] || die "找不到 $APK
先跑：sh gradlew :app:assembleRelease"

# class 名字（用内部 '/' 形式，dex 字符串池里存的就是这个）和方法名字（源码里的标识符）。
# 缺任何一个都会让 native 侧的查找失败。
CLASSES=(
    com/artifex/mupdf/fitz/Document
    com/artifex/mupdf/fitz/Page
    com/artifex/mupdf/fitz/Matrix
    com/artifex/mupdf/fitz/Rect
    com/artifex/mupdf/fitz/Link
    com/artifex/mupdf/fitz/LinkDestination
    com/artifex/mupdf/fitz/Outline
    com/artifex/mupdf/fitz/SeekableInputStream
    com/artifex/mupdf/fitz/SeekableStream
    com/artifex/mupdf/fitz/Context
    com/artifex/mupdf/fitz/Cookie
    com/artifex/mupdf/fitz/Device
    com/artifex/mupdf/fitz/NativeDevice
    com/artifex/mupdf/fitz/android/AndroidDrawDevice
)
METHODS=(
    openNativeWithStream      # Document 唯一的 open 入口（本项目走 SeekableInputStream）
    needsPassword             # 密码分支
    authenticatePassword
    countPages                # 页数 / 目录换算
    loadPage
    loadOutline               # 目录树
    resolveLinkDestination    # 目录树 + 页内链接的目标页
    getMetaData               # 文档元数据
    destroy                   # 释放（Document/Page/NativeDevice 共用这个名字）
    finalize
    getBounds                 # 页尺寸
    getLinks                  # 页内链接
    run                       # 渲染
    getLinks
    newNative                 # AndroidDrawDevice
    initNative                # Context
)

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "检查 $APK"
python3 - "$APK" "$TMP" <<'PY'
import sys, zipfile
apk, tmp = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(apk)
dexes = [n for n in z.namelist() if n.endswith('.dex')]
if not dexes:
    sys.exit('APK 里没有 .dex')
blob = b''
for d in dexes:
    data = z.read(d)
    open('%s/%s' % (tmp, d.replace('/', '_')), 'wb').write(data)
    blob += data
open('%s/ALL' % tmp, 'wb').write(blob)
print('  dex: %s（合计 %d 字节）' % (', '.join(dexes), len(blob)))
PY

ALL="$TMP/ALL"
[ -f "$ALL" ] || die "解包 dex 失败"

missing=0
echo "  类名："
for c in "${CLASSES[@]}"; do
    if grep -qF -- "$c" "$ALL"; then
        printf '    ok       %s\n' "$c"
    else
        printf '    缺失!!!  %s\n' "$c"
        missing=$((missing + 1))
    fi
done

echo "  方法名："
for m in "${METHODS[@]}"; do
    if grep -qF -- "$m" "$ALL"; then
        printf '    ok       %s\n' "$m"
    else
        printf '    缺失!!!  %s\n' "$m"
        missing=$((missing + 1))
    fi
done

echo
if [ "$missing" -ne 0 ]; then
    echo "失败：$missing 项缺失。JNI 绑定被 R8 改名或删掉了，release 包运行时会崩。" >&2
    echo "检查 android-pdf-viewer/consumer-proguard-rules.pro 是否仍被" >&2
    echo "defaultConfig 的 consumerProguardFiles 引用。" >&2
    exit 1
fi
echo "通过：JNI 绑定类名与方法名齐全，release 包的 JNI 查找不会失败。"

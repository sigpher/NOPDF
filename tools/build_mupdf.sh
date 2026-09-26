#!/usr/bin/env bash
#
# 编译 MuPDF 的 Android 原生库，供 android-pdf-viewer 的 MupdfEngine 使用。
#
# 为什么需要这个脚本，而不是用 Gradle 的 externalNativeBuild：
# 本项目冻结在 AGP 3.4.1（Gradle 5.4.1 / Kotlin 1.3.61），而 NDK r27 采用统一工具链
# 布局、**不再带 platforms/ 目录**，AGP 3.4.1 的 NDK 集成无法识别该布局。因此这里用
# 裸 ndk-build 编出 .so，Gradle 只负责把产物目录接进 jniLibs，完全不碰 NDK。
#
# 产物体积较大（两个 ABI 合计约 18 MB），故不入 git：源码路径与产物路径都由
# local.properties 的 mupdf.dir 指定，构建目录随源码树落在其内部。
#
# 用法：
#   sh tools/build_mupdf.sh            # 编译 arm64-v8a + armeabi-v7a
#   sh tools/build_mupdf.sh arm64-v8a  # 只编某一个 ABI
#
# 前置条件（一次性）：
#   1. 解压 MuPDF 源码到任意目录，例如 ~/mupdf-build/mupdf-1.28.5-source
#   2. 在 local.properties 里加一行 mupdf.dir=/绝对路径/mupdf-1.28.5-source
#   3. 装好 NDK r21+（本脚本用 ndk.dir 指向的版本；实测 r27 可用）

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$REPO_ROOT/local.properties"

die() { echo "错误：$*" >&2; exit 1; }

[ -f "$PROPS" ] || die "缺少 $PROPS（该文件是本项目构建的必需文件，见 AGENTS.md）"

# 从 local.properties 读取键值；忽略注释与空行，容忍行首空格与 CRLF。
prop() {
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$PROPS" \
        | head -1 | tr -d '\r'
}

MUPDF_DIR="${MUPDF_DIR:-$(prop mupdf.dir)}"
[ -n "$MUPDF_DIR" ] || die "local.properties 里没有 mupdf.dir。\
请先解压 MuPDF 源码，再在该文件中加一行：\
    mupdf.dir=/绝对路径/mupdf-1.28.5-source"
[ -d "$MUPDF_DIR" ] || die "mupdf.dir 指向的目录不存在：$MUPDF_DIR"

# MuPDF 1.x 用 platform/（单数），不是 platforms/。
ANDROID_MK="$MUPDF_DIR/platform/java/Android.mk"
[ -f "$ANDROID_MK" ] || die "在 $MUPDF_DIR 下找不到 platform/java/Android.mk\
（确认这是 MuPDF 1.28.x 源码树）"

NDK_DIR="$(prop ndk.dir)"
[ -n "$NDK_DIR" ] || die "local.properties 里没有 ndk.dir"
NDK_BUILD="$NDK_DIR/ndk-build"
[ -x "$NDK_BUILD" ] || die "ndk.dir 指向的目录里没有可执行的 ndk-build：$NDK_DIR"

# minSdk 21，与 app/build.gradle 的 minSdkVersion 一致；改动时两处需同步。
APP_PLATFORM="${APP_PLATFORM:-android-21}"

if [ $# -gt 0 ]; then
    ABIS=("$@")
else
    ABIS=(arm64-v8a armeabi-v7a)
fi

# MuPDF 的内置字体是 make generate 的产物，源码包里没有；没生成过 Android.mk 里的
# $(wildcard ...) 会匹配到空，编出来的 .so 缺字体。这里用 Android.mk 真正引用的那个
# 目录做哨兵。
if ! ls "$MUPDF_DIR"/generated/resources/fonts/urw/*.c >/dev/null 2>&1; then
    echo "未找到生成的字体 C 源，先跑 'make generate'（在 $MUPDF_DIR 下）"
    ( cd "$MUPDF_DIR" && make generate )
fi

for ABI in "${ABIS[@]}"; do
    echo "=== 编译 $ABI ==="
    # ndk-build 不读环境变量，这些必须作为 make 参数传入。
    ( cd "$MUPDF_DIR" && "$NDK_BUILD" -j"$(nproc)" \
        APP_BUILD_SCRIPT=platform/java/Android.mk \
        APP_PROJECT_PATH=build/android \
        APP_PLATFORM="$APP_PLATFORM" \
        APP_OPTIM=release \
        APP_ABI="$ABI" )
done

LIBS="$MUPDF_DIR/build/android/libs"
echo
echo "完成。产物位于："
for ABI in "${ABIS[@]}"; do
    SO="$LIBS/$ABI/libmupdf_java.so"
    if [ -f "$SO" ]; then
        printf '    %-14s %s bytes\n' "$ABI" "$(stat -c %s "$SO")"
    else
        printf '    %-14s 缺失：%s\n' "$ABI" "$SO"
        exit 1
    fi
done
echo
echo "Gradle 会自动把上面这个 libs 目录接进 jniLibs，无需其他配置。"

# AGENTS.md

Android PDF reader ("NO PDF"), package `com.sigpher.nopdf`, forked from [YESPDF](https://github.com/aaronzzx/YESPDF). Two modules: `app` (the product) and `android-pdf-viewer` (local fork of barteksc/AndroidPdfViewer). Single `master` branch. No CI, no lint/test gate, no formatter. Commit messages are Chinese: `内容：<desc>` for normal work, `release <versionCode>-v<versionName>` for releases.

## Build

- Toolchain is frozen: Gradle 5.4.1 wrapper, AGP 3.4.1, Kotlin 1.3.61, `compileSdk 29`, `targetSdk 28`, Java 8 source/target. **Requires JDK 8.** Do not upgrade casually — the whole build depends on these.
- **Top gotcha — `local.properties`.** `app/build.gradle:52` does an unguarded `file('local.properties').newDataInputStream()` at *configuration* time, so *every* Gradle invocation fails if the file is missing — even `./gradlew clean` or `:app:testDebugUnitTest`. It is gitignored and **absent from a fresh checkout**. It needs `sdk.dir` **plus** `STORE_FILE_NAME`, `KEYSTORE_PASSWORD`, `STORE_ALIAS`, `KEY_PASSWORD`; both variants sign with the release keystore, so debug builds fail too.
- Commands:
  - `./gradlew :app:assembleDebug` — primary build check
  - `./gradlew :app:assembleRelease` — `minifyEnabled` + `shrinkResources`, and since 0.2.2 **ABI-split** into two APKs (`splits.abi`, `universalApk false`): `app/build/outputs/apk/release/app-arm64-v8a-release.apk` + `app-armeabi-v7a-release.apk`. There is no longer an `app-release.apk`; a stale one left over from an older build will linger in that dir until cleaned.
  - `./gradlew :app:testDebugUnitTest` — JVM unit tests
  - one class: `./gradlew :app:testDebugUnitTest --tests "com.sigpher.nopdf.preview.ContentTreeTest"`
- `jcenter()` / `dl.bintray.com` are still listed in the root `build.gradle` but are dead. `mavenCentral()` + `maven.aliyun.com/repository/public` mirrors are present in both `buildscript` and `allprojects`, so deps (incl. Umeng) do resolve. README also claims a `plugins.gradle.org/m2` mirror — it is **not** in any build file. Trust the build files over the README.
- Release mapping lands in `app/build/outputs/mapping/release/mapping.txt` (AGP writes it from the `-verbose` flag). Do **not** re-add `-printmapping` — `app/proguard-rules.pro:108` explains it dirties the worktree. `proguardMapping.txt` is gitignored.
- Debug variant: `applicationId` + `.dev`, icon/label swapped via `manifestPlaceholders` (`app_icon`, `app_name`). Release uses `app_ic_launcher` / `app_name_en`. Because `AppConfig.AUTHORITY` is `BuildConfig.APPLICATION_ID + ".fileprovider"`, the FileProvider authority differs per variant (`…nopdf.dev.fileprovider` in debug) — don't hardcode it.
- `android-pdf-viewer` is a **deliberately patched** fork, not vanilla: `PDFView` exposes `MutableLiveData` (hence the explicit `api androidx.lifecycle:lifecycle-livedata-core`), and `core-ktx` is pinned to `1.3.0` because a dynamic `+` resolved to a class-file version AGP 3.4.1's Jetifier could not process. Keep it pinned. Its bintray block is dead — ignore it. PDF rendering itself now goes through the local `engine/` abstraction onto MuPDF — see the rendering-engine section above.
- **「取字」类功能（选词 / 复制 / 全文搜索）目前仍未实现，但这不再是引擎的限制。** 历史上受限于
  pdfium：`com.github.barteksc:pdfium-android:1.9.0` 的 Java 层完全没有取字能力（无 `TextPage` /
  `loadTextPage` / `getTextBounded`），其 `libmodpdfium.so` 虽导出 `FPDFText_*`（含
  `FPDFText_GetCharIndexAtPos`）但 `libjniPdfium.so` 未做 JNI 绑定。曾引入
  `com.tom-roush:pdfbox-android` 做「选词查词」，该功能在 0.2.0 整体移除，**PDFBox 依赖及其传递
  依赖 BouncyCastle 也已一并删除**（省掉 fontbox 的 cmap 资源与约 1MB dex）。
  **换成 MuPDF 后这个障碍消失了**：绑定层直接提供 `Page.toStructuredText()`、`StructuredText` /
  `TextWalker` / `Text`、`Page.search()`、`textAsHtml()`。要做选词/搜索时，在 `PdfEngine` 上加取字
  方法、由 `MupdfEngine` 转发即可——抽象层已经是为此准备的。
- `com.blankj:utilcode` (`SPStaticUtils`, `PathUtils`, `StringUtils`, `FileUtils`, `GsonUtils`, …) is used across ~32 files but **never declared** — it arrives transitively via `com.aaron:base`. Be careful when touching the `exclude` block at `app/build.gradle:121-128`.

## 渲染引擎：MuPDF（不再是 pdfium）

**引擎层已收敛为 `com.github.barteksc.pdfviewer.engine.PdfEngine`**，位于 fork 模块
`android-pdf-viewer/src/main/java/.../engine/`：

| 文件 | 作用 |
| --- | --- |
| `PdfEngine.java` | 引擎面：打开/关闭文档、页数、页尺寸、渲染、元数据、目录树、页面链接、坐标映射 |
| `MupdfEngine.java` | **唯一的实现**，对接 MuPDF 1.28.5 |
| `PdfEngines.java` | 工厂，`create(Context)` 是**唯一**决定用哪个引擎的地方 |
| `EngineDocument` / `EngineSize` / `EngineSizeF` / `EngineBookmark` / `EngineLink` / `EngineMeta` | 引擎中立的值类型，形状对齐原 pdfium 对应类型，因此换引擎是纯改名 |
| `PasswordRequiredException` | 密码错误/缺失的统一表示，替代 pdfium 的 `PdfPasswordException` |

**`com.shockwave.*` 已从代码与依赖中彻底移除**（`pdfium-android:1.9.0` 依赖已删，`PdfiumEngine`
已删）。规则：引擎面之上不得出现任何引擎专有类型。

### pdfium → MuPDF 的四处语义差异（不是改名，改前必读）

1. **页生命周期**：MuPDF 每次 `Document.loadPage(i)` 都**新分配**一个 `Page`，而 pdfium 模型是
   「open 一次、反复 render」。`MupdfEngine` 用 `MuDoc.pages`（`SparseArray<Page>`）缓存桥接，
   由 `closeDocument` 统一 `destroy()`。`PdfFile` 的 `openedPages` 与 part 缓存（按张数计）
   仍是上层的驻留上限，引擎自身不淘汰。
2. **局部渲染**：`AndroidDrawDevice` 要的是**设备空间** patch（`bbox = xOrigin+patchX0 …`），
   不是 pdfium 的页空间子矩形。`MupdfEngine.renderPageBitmap` 改用 CTM 表达：按
   `scale = min(bw/regionW, bh/regionH)` 缩放，再平移使区域左上角落在 bitmap 原点。
   缩放/分块渲染的坐标语义依赖于此，**改这里会直接导致画面错位**。
3. **密码**：MuPDF 用返回值而非异常（`needsPassword()` + `authenticatePassword()`），
   在 `finishOpen` 里翻译成 `PasswordRequiredException`，以保持
   `loadError → onError → showError` 链路与 `PreviewActivity` 的 `is` 判断不变。
4. **目录树**：MuPDF 的 `Outline` **不携带页码**，只有 `title` + 目标 `uri`。页码要靠
   `document.resolveLinkDestination(outline)` 拿到 `LinkDestination`（它继承 `Location`，
   有 `chapter`/`page`），再按 `chapter` 累加 `countPages(c)` 换算成扁平页号。解析不出来的
   记为 0（保留节点而非丢弃）。MuPDF 直接给树，app 侧 `ContentTree` 继续消费即可。
5. **页尺寸不进缓存**：`PdfFile.setup()` 会遍历**每一页**调 `getPageSize`。pdfium 那边的
   `nativeGetPageSizeByIndex` 是按索引取、不打开任何页；MuPDF 没有等价入口，只能
   `loadPage` → 量 → `destroy()`。若图省事走缓存（`page()`），打开 N 页文档就会常驻 N 个
   MuPDF `Page`，在 `largeHeap` + 30MB part 缓存之外又是一笔常驻内存。故 `getPageSize`
   只在页面**已因渲染而驻留**时才读缓存，否则量完立刻释放。
6. **`MuDoc.pages` 有锁**：`SparseArray` 非线程安全，而这张表同时被渲染线程
   （`RenderingHandler` 开页/渲染）与主线程（`getPageSize`、`mapRectToDevice` 命中链接）访问。
   `MuDoc` 的方法都 `synchronized`，但**只在读写表时持锁，绝不跨 `page.run()` 持锁**，
   否则渲染会被测量串行化。

`MupdfEngine` 另有两处适配：`ParcelFileDescriptor` 需包成 `SeekableInputStream`（MuPDF 无 fd
入口；自带 `FitzInputStream` 构造器是 private，用不了，自实现那 3 个方法，且避免整份 PDF 读进
内存）；`minSdk` 与 `APP_PLATFORM` 均为 21（fork 原先声明 16，已同步改为 21）。

### 构建 MuPDF：为什么必须用裸 ndk-build

**`externalNativeBuild` 在本项目不可用**：AGP 3.4.1（2019）早于 NDK 统一工具链改造，
NDK r27 **不再带 `platforms/` 目录**，AGP 3.4.1 的 NDK 集成无法识别该布局。反方向走
（装带 `platforms/` 的旧 NDK）也不行——MuPDF 1.28.5 需要现代 clang。

因此 `tools/build_mupdf.sh` 用裸 `ndk-build` 预编译，Gradle 只把 `libs` 目录接进
`jniLibs.srcDirs`，**全程不碰 NDK**。Java 绑定（64 个 `com.artifex.mupdf.fitz` 类）以
`java.srcDirs` 参与编译。

**两者都不入库**（源码树 68 MB、`.so` 合计 18 MB），路径写在 `local.properties` 的
`mupdf.dir` / `ndk.dir`（该文件本就是构建必需项，不额外增加克隆负担）。
`android-pdf-viewer/build.gradle` 在**配置阶段**校验 `mupdf.dir` 存在且两个 ABI 的 `.so`
都已编译，缺失即报错——因为 `splits.abi` 关了 `universalApk`，缺一个 ABI 时 AGP 仍会产出
那个 APK，只是里面没有原生库，装上一进阅读页就 `UnsatisfiedLinkError`。

`ndk-build` 不读环境变量，`APP_BUILD_SCRIPT` / `APP_PLATFORM` / `APP_ABI` 必须作为
**make 参数**传入。首次编译前需 `make generate` 生成内置字体的 C 源码（脚本会自动做）。

### 许可：AGPL-3.0

MuPDF 是 AGPL-3.0，本项目自行编译并随应用分发，因此仓库 `LICENSE` 已从 Apache-2.0 改为
AGPL-3.0。**AGPL 第 13 条（网络使用）不适用**（本应用不提供网络服务），但**分发即构成
「携带」，必须提供完整对应源码**——发布时别忘了。

官方其实**有**发布 Android 预编译产物：`maven.ghostscript.com` 上的
`com.artifex.mupdf:fitz`（以及 `:viewer` / `:mini`），版本 1.11.0 – 1.28.5，AGPL-3.0。
已核实 `fitz:1.28.5` 的 AAR 对本项目**完全可用**：`minCompileSdk=1`、
`minAndroidGradlePluginVersion=1.0.0`（都没有抬高要求）、class 文件版本 52（Java 8）、
`minSdkVersion 21`、4 个 ABI、内含 87 个绑定类，POM 无任何传递依赖。
也就是说它本来可以直接用，**不需要 NDK、不需要在本地编 18MB 产物、
`local.properties` 里也不必写 `mupdf.dir` / `ndk.dir`**。本项目目前仍走自行 `ndk-build`，
理由只剩「能通过 `MUPDF_EXTRA_CFLAGS` 裁掉 mujs / cmarkgfm / openjpeg 等组件」（见「包体」）。
若要改回预编译产物：根 `build.gradle` 加 `maven { url 'https://maven.ghostscript.com' }`，
`android-pdf-viewer` 去掉 `java.srcDirs` / `jniLibs.srcDirs` 与那段校验、换成
`api 'com.artifex.mupdf:fitz:1.28.5'`，删掉 `tools/build_mupdf.sh`。
**下面的 R8 规则与 `-keep` 的必要性不会因此改变**（JNI 名字仍会被改名/删除）。

### R8：JNI 绑定必须整包 keep（release 曾整个不可用）

`libmupdf_java.so` 用**硬编码的类名/方法名/签名**做 JNI 查找
（`FindClass("com/artifex/mupdf/fitz/Document")`、`GetMethodID(..., "loadPage", ...)`）。
R8 只要改名或删掉其中任何一个，运行时就是 `UnsatisfiedLinkError` / `NoSuchMethodError`。

**开发期实测：没有 keep 规则时 release APK 是彻底坏的，而 debug 完全正常**——所以
「debug 能跑」根本不能证明 release 能跑。`assembleRelease` 照样 BUILD SUCCESSFUL。
`classes.dex` 里 `Document` 只剩 `<clinit>` + `openNativeWithStream`，
`loadPage` / `countPages` / `needsPassword` / `authenticatePassword` / `loadOutline` /
`getMetaData` / `resolveLinkDestination` 全部消失；`Matrix` 被改名成 `c.c.a.a.a`、
`SeekableInputStream` 被改名成 `c.c.a.a.b`。

原因是 AGP 内置的 `proguard-android-optimize.txt` 只带一条
`-keepclasseswithmembernames class * { native <methods>; }`，它有两个洞：
**没有 `includedescriptorclasses`**（只出现在 native 方法签名里的类照样被改名），
且**只保名字不保存在**（方法可以直接被删掉）。换 pdfium 时仓库里那条
`-keep class com.shockwave.**` 正是为此，迁到 MuPDF 后若不补回来就会重蹈覆辙。

现由 `android-pdf-viewer/consumer-proguard-rules.pro` 声明
（`-keep class com.artifex.mupdf.fitz.** { *; }`），在 `defaultConfig` 里用
`consumerProguardFiles` 挂上——**必须由提供绑定类的模块声明**，写进
`app/proguard-rules.pro` 影响不到它们。代价约 +38 KB/包。修好后 87 个绑定类全部保留、
无一改名。

**改动 `android-pdf-viewer` 的依赖、sourceSets 或混淆配置后，务必重新核对 release dex：**

```sh
sh tools/check_release_jni.sh          # 见该脚本，逐个断言 JNI 名字还在 dex 里
```

现有的 16 个测试**检测不到**这一类问题（`ContentTree` 刻意不依赖引擎类型），
所以这个检查是唯一能挡住它的自动化关卡。

### 渲染位图必须是 ARGB_8888（换引擎踩过的最大的坑）

`AndroidDrawDevice` 的 JNI 绑定把 `Bitmap` 的**裸内存**直接当 `fz_pixmap` 用，
`platform/java/jni/android/androiddrawdevice.c` 里硬性要求 4 字节/像素：

```c
if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
    jni_throw_run(env, "new DrawDevice failed as bitmap format is not RGBA_8888");
if (info.stride != info.width * 4)
    jni_throw_run(env, "new DrawDevice failed as bitmap width != stride");
```

pdfium 那边随便什么 config 都能画，所以 `RenderingHandler.proceed` 一直按清晰度选
`ARGB_8888 : RGB_565` 省一半内存（`bestQuality ? ARGB_8888 : RGB_565`）。换 MuPDF 后这条
路直接走不通。而 `useBestQuality(...)` **全项目从未被调用**，`bestQuality` 恒为默认的
`false`——于是**每一次**渲染都传 `RGB_565` 进去，每一页都抛异常。

**实际表现是「一打开 PDF 就闪退」，而且是被杀进程而不是报错**，原因有两层叠加：

1. MuPDF 抛的是**裸 `RuntimeException`**，不是 `PageRenderingException`；
2. `RenderingHandler.handleMessage` 当时**只**捕获 `PageRenderingException`。

于是异常逃出 `handleMessage` → 逃出渲染线程的 `Looper.loop()` → 成为未捕获异常 →
Android 默认处理器终止整个进程。连带效果是「打不开任何 PDF」，且**没有任何提示**
（既没有 `onError` 对话框，也没有 `onPageError` 的 toast）。

现已修：位图恒为 `ARGB_8888`；`handleMessage` 额外兜住 `Throwable` 并包成
`PageRenderingException` 走 `onPageError`（渲染失败是可跳过的单页故障，不该杀进程）；
`MupdfEngine.renderPageBitmap` 再加一道 `ARGB_8888` 断言，把「调用方传错了 config」
变成一句能直接读懂的报错，而不是一条来自 JNI 的天书。

**记忆不因此回退**：`Constants.Cache` 的容量估算（`CACHE_SIZE` 120 × `PART_SIZE` 256
× 4B ≈ 30MB）本来就是按 4 字节/像素算的。**别再把 `Bitmap.Config` 和清晰度挂钩。**

`PdfEngine.renderPageBitmap` 的 javadoc 已写明该约束（引擎中立接口层，pdfium 能容忍、
MuPDF 不能的差异都记在这里或紧邻的注释里）。

### 包体

| ABI | pdfium（旧） | MuPDF（新） | 增量 |
| --- | --- | --- | --- |
| `arm64-v8a` | 6,142,542 B | 8,556,813 B | +2,414,271 B（+39.3%） |
| `armeabi-v7a` | 6,003,851 B | 7,700,380 B | +1,696,529 B（+28.3%） |

（含 JNI keep 规则，比不 keep 时各多约 38 KB。）压缩后 `lib/`：arm64 5,492,229 B /
v7a 4,635,758 B。MuPDF 的 `.so` 压缩比约 0.51，比 pdfium 的 0.44 略差（CJK 字体数据
本身不易压缩）。

仍有回收空间：MuPDF 默认打包了 mujs（JS 引擎）、extract、cmarkgfm（markdown）、
openjpeg（JPEG2000）等本应用完全不用的组件，可用 `MUPDF_EXTRA_CFLAGS` 关掉。

**换引擎仍未做真机渲染验证**（本机无 emulator / system-image / 真机，只做到了 dex 级校验）。
上面那条 `ARGB_8888` 的坑是用户在真机上撞出来的第一处渲染问题，但**渲染效果本身
（分块、缩放、翻页、页面间隔、书签、目录）依旧没人核对过**，升级前请手动过一遍。

## 性能与稳定性（已修 / 仍存在）

- **`PdfUtils` 曾泄漏文件描述符**：旧实现 `return` 写在 `finally` 里，且从不 close
  `PdfRenderer` / `ParcelFileDescriptor`。导入流程对每本书都会调用它渲染封面，
  累积会到「too many open files」。现已改为 try-with-resources。
- **`PdfUtils` 曾用 `MODE_READ_WRITE` 打开 PDF**（只读场景），在只读介质/分区存储下打不开。
  现为 `MODE_READ_ONLY`。
- **外部 Intent 打开 PDF 的入库曾阻塞主线程**：`getData()` 在 `onCreate` 里同步调用
  `DBHelper.insert`，而入库要渲染封面。现改为记录 `pendingImportPath`，
  由 `importThenOpen()` 在 `Dispatchers.IO` 完成后再 `initPdf`；
  记账逻辑抽成 `onPdfLoaded()` 供两条路径共用，延后打开需重跑 `initScaleFactor()`。
- **Native 库已按 ABI 拆包（0.2.2 起）**：`defaultConfig.ndk.abiFilters` 已移除，改用
  `android.splits.abi`（`enable true` + `reset()` + `include 'armeabi-v7a','arm64-v8a'`、
  `universalApk false`）。此前 abiFilters 只决定"哪些 ABI 进包"，两个 ABI 仍塞在同一个 APK 里；
  现在每个 APK 只带一份 native 库。`armeabi`(ARMv5) / `x86` / `x86_64` 均不产出。
- **包体现状（0.5.1 release，换 MuPDF 后）**：`arm64-v8a` 包 **8,556,813 B ≈ 8.2MB**、
  `armeabi-v7a` 包 **7,700,380 B ≈ 7.3MB**，即 0.2.2（6,142,542 / 6,003,851 B）的基础上
  分别 +2.41MB / +1.70MB，换引擎是包体变大的主因（详见「渲染引擎」一节的对比表）。
  相对 0.5.0（8,517,261 / 7,660,782 B）各 +39,552 / +39,598 B，即补 JNI keep 规则的代价。
  压缩后 `lib/` 为 5,492,229 / 4,635,758 B，**native 占压缩后体积约 60%**。
  两包用**同一签名与同一 versionCode**，安装时按设备 ABI 选包。
  剩余可压缩空间主要在 MuPDF 本身（可关掉 mujs/extract/cmarkgfm/openjpeg），
  其次是 PNG 调色板化（见 Icons 一节）与 R8 规则；`assets/` 已无内容，
  语言资源已用 `resConfigs` 白名单过滤过。改动构建配置后请重新量一次再下结论。
- **已修：读页时的两处逐帧开销。** `drawBookmark` 原先挂在 `PDFView.onDrawAll` 上，且每次
  调用都 `BitmapFactory.decodeResource(resources, R.drawable.app_img_bookmark)`——只要当前页
  有书签，**每一帧都会重新解码一张 PNG**；现改为 `by lazy` 缓存一次（`bookmarkBitmap`）。
  `GreyUI.grey` 原先在灰度**关闭**时也会 `setLayerType(LAYER_TYPE_HARDWARE, null)`，而
  `LAYER_TYPE_HARDWARE` 即使不带 paint 也会让 decorView 先渲染进全屏离屏缓冲——由于灰度默认
  关闭，等于**每个界面每帧都多一次全屏拷贝**；现关闭时显式回到 `LAYER_TYPE_NONE`。
- **`AndroidManifest` 已开 `largeHeap="true"`**：阅读页要分配整页位图，且 fork 的 part 缓存按
  **张数**计（`Constants.Cache.CACHE_SIZE = 120` × `PART_SIZE` 256 × 4B ≈ 30MB 上限），
  小堆设备上大页 PDF 会因 GC 抖动而掉帧。
- **部分已修：书架刷新的主线程成本。** 封面列表的构建已从 `DataManager.updateCoverList()`
  的「按分组逐个全量扫描 + 排序」抽成纯函数 `CoverBuilder.build()`（一次 `groupBy`，总体
  O(PDF 数 + 每组 k log k)），且 `getPdfList()` 不再复用会被下一次调用清空的 static `tempList`。
  **但仍存在**：`updatePDFs()` / `updateAll()` 依旧是**主线程全量查库 + 重建内存列表**，在
  `PreviewActivity.onPause` 的 `updateDB` 观察者里会跑一次，书库大时会让「退出阅读页」掉帧；
  书架各处也仍是 `notifyDataSetChanged()` 全量重绑——`common/AdapterDiff.kt` 里的 `ListDiffer`
  正是为替代它而写，但**目前没有任何调用方**（release 会被 R8 整个裁掉），尚未接入。
- **仍存在：Overdraw 12 处**（5 个 Activity 布局）。阅读类应用对低端机影响较大，
  但改背景层需要逐屏核对，本次未动（`app_activity_preview.xml` 里 `app_pdfview_bg` 的
  `@color/base_white` 底、与 PDFView 自身 `@color/base_transparent` 背景都是候选）。

## Lint

- 项目**没有 lint 门禁**（release 的 `lintOptions` 是 `checkReleaseBuilds false` +
  `abortOnError false`），所以 lint 只作参考。可用 `./gradlew :app:lintRelease` 手动跑，
  报告在 `app/build/reports/lint-results-release.xml`。
- 已修：`HardcodedText` 8 → 0；`NewApi` 2 → 0（在 `ScanActivity`/`SelectActivity` 的
  `onCreate` 上加了带说明的 `@SuppressLint`）；错误 4 → 0。
- **Error 已经清零**。原先剩下的 2 个 Error 是 BouncyCastle 引用 `javax.naming` 触发的
  `InvalidPackage`；BouncyCastle 只由 PDFBox 传递引入，0.2.0 移除 PDFBox 后连它一起消失，
  因此**不再需要** `lintOptions { disable 'InvalidPackage' }`。若日后重新引入 PDFBox，
  这 2 个 Error 会回来。
- **误报要当心**：`UnusedResources` 会漏报——`app_name_dev` 与 `*_dev` 图标是被
  `app/build.gradle` 的 `manifestPlaceholders` 引用，lint 看不到 build.gradle。
  同理 `TrustAllX509TrustManager` 只在 Umeng（`com.umeng.analytics.pro.*`），不是本项目代码。

## Database (GreenDAO, schemaVersion 3)

- Codegen output is **committed** at `app/src/main/java/com/sigpher/nopdf/common/greendao/` (`targetGenDir 'src/main/java'`). Bump `greendao { schemaVersion }` (`app/build.gradle:98-104`) and regenerate; never hand-edit `DaoMaster`/`*Dao`.
- **Two independent migration layers — a schema change usually needs both:**
  1. Schema: `UpdateOpenHelper.onUpgrade` → `MigrationHelper.migrate`. Its `onDropAllTables` calls `DaoMaster.dropAllTables`, which drops **every** table, so all DAOs (`PDFDao`, `CollectionDao`, `RecentPDFDao`) must stay in the vararg list at `UpdateOpenHelper.java:45` — an omitted DAO is recreated empty (data loss).
  2. Data backfill: `App.onCreate` calls `DBHelper.migrate()` only when `!isFirstInstall()` (compares `firstInstallTime == lastUpdateTime`) *and* a `DB_VERSION` pref is below `DaoMaster.SCHEMA_VERSION`. `migrate()` renumbers `position` and resets `scaleFactor`. A schema bump alone leaves this stale; the first-install guard also means the backfill never runs on a clean device.
- `DBHelper` is a Kotlin `object` holding a single `DaoSession`; `DataManager` is the in-memory cache over it. Persist through `DBHelper`, then refresh `DataManager` (`DataManager.updateAll()`).
- `DBHelper.getName(path)` (private) strips the last 4 characters — it assumes a `.pdf` extension and does no validation.
- Import is not just DB rows: `DBHelper.insertPDFs` renders page 0 and writes a cover bitmap to `${PathUtils.getInternalAppDataPath()}/$name.jpg`, synchronously on the import path. Renaming a PDF orphans its cover file.
- Backup/restore is a hand-rolled format in `MainActivity`: one Base64-encoded Gson `Backup` JSON per group, written as `$groupName.txt` into `${PathUtils.getExternalAppDataPath()}/files`. Restore reads *every* `.txt` in that dir. Changing the `Backup`/`PDF` bean shape silently breaks existing user backups — treat it as a data format, not an implementation detail. **No automated coverage; verify by hand.**

## Architecture

- Hand-written MVP. Feature packages: `main` (bookshelf), `preview` (reader), `filepicker`, `settings`, `about`; shared code in `common` (`DBHelper`, `DataManager`, `Settings`, `bean/`, `greendao/`, `event/`, `statistic/`, `utils/`, `widgets/`). `common/MVP.kt` holds the `IModel`/`IView`/`IPresenter` bases; each feature has `XxxPresenter` + `I*Contract`.
- `BaseActivity` / `BaseFragment` / `IContract` / `ImageLoader` / `ToastUtils` come from external `com.aaron:base:1.1.5-beta9` (not in this repo) — decompile the AAR if you need their behavior. **`UiManager` and `DialogManager` are _local_** (`common/UiManager.java`, `common/DialogManager.kt`), not from the AAR.
- **所有提示统一走 `UiManager`，不要直接用 `com.blankj:utilcode` 的 `ToastUtils`。** utilcode 的
  `ToastFactory.newToast` 会在 `NotificationManagerCompat.areNotificationsEnabled()` 为 false 时
  切换到 `ToastUtils$ToastWithoutNotification`，后者用 `WindowManager type = 2005`（`TYPE_TOAST`）
  自绘窗口；**`TYPE_TOAST` 在 Android 11+ 已被系统禁止**，`addView` 失败且不抛异常，结果是
  **所有提示彻底不可见**——而调用方无从感知，表现为「功能按了没反应」。`UiManager` 现直接用
  系统 `Toast.makeText`（`app_toast` 自定义卡片布局已随之删除）。同理，任何「失败只弹提示」的
  代码路径都会因此变成静默失败，排查时先确认提示真的能显示。
- View binding is `kotlinx.android.synthetic` (20 files). Deprecated, but it is the convention — don't migrate a file to ViewBinding as a drive-by. Layouts do not use `android:onClick`.
- **Settings 的 item 布局刻意复用同名 id**（`app_tv_title` / `app_tv_count` / `app_spinner` 在
  `app_recycler_item_settings_switch` / `_recent_count` / `_page_spacing` 里重名），这样多个
  holder 能共用一段绑定逻辑；代价是 `kotlinx.android.synthetic` 的星号导入会让这些名字产生
  **重载歧义**，必须在 `SettingsAdapter` 顶部用**显式导入**把它定到其中一个布局（合成视图最终
  都是对同一 id 做 `findViewById`，且这几个 id 在各布局里类型一致，定到哪个都等价）。
  新增同类 item 布局时照此办理，不要再加 `page_spacing.view.*` 之类的星号导入。
- Cross-component messaging is EventBus (event POJOs in `common/event/`, plus `common/LiveDataBus.kt` for sticky LiveData). Add a new event object per feature rather than reusing another feature's.
- Kotlin-dominant; the Java is mostly generated GreenDAO code, `App`/`DataManager`/`AppConfig`/`PdfUtils`, and a few holders. Match the style of the file you edit.
- Entrypoints: `main/MainActivity` (LAUNCHER, `singleTask`, splash theme) and `preview/PreviewActivity` (`exported=true`, handles `application/pdf` VIEW intents and imports the file through `DBHelper.insert`).
- `preview/PreviewActivity.kt` is ~1447 lines mixing rendering, gestures, bookmarks, TOC and page export — read it in sections. TOC expand/collapse is extracted into `preview/ContentTree.kt`, which (with its test) is the only extracted-and-tested piece.
- Legacy names `AllFragment2` / `AllAdapter2` / `CollectionFragment2` / `CollectionAdapter2` are upstream YESPDF leftovers with no `…1` counterpart. Don't rename or "fix" them.
- `resourcePrefix 'app'` (`app/build.gradle:18`): every new resource must be `app_…`. Locales: `values` (en), `values-zh-rCN`, `values-zh-rHK/rMO/rSG/rTW`.
- **`common/Settings.kt` 的几个默认值是刻意选定的，不要「顺手改回」**：
  `swipeHorizontal = false`（默认纵向滚动阅读）、`clickFlipPage = false`（点击只收放菜单、不翻页）、
  `linearLayout = true`（书架默认列表布局）、`pageSpacing = 0`（页面间隔默认关闭，保持原紧密排版）。
  `SPStaticUtils.getBoolean/getInt(key, default)` 的默认值
  只在**没写过该 key 时**生效，所以改动会影响「装了新版但从未手动设置过该项」的用户；
  已在 SP 中留下键值的用户不受影响。
- `pageSpacing` 是 **`PDFView` 的加载期参数**（`configurator.spacing(...)`），改后必须重新载入
  文档才生效；`PreviewActivity` 仅在 `!swipeHorizontal`（纵向）时传入，并在
  `onActivityResult(REQUEST_CODE_SETTINGS)` 里对比 `appliedPageSpacing` 决定是否重载。
- `PreviewActivity` 调用了 `disableLongpress()`：长按没有任何消费者（选词查词已在 0.2.0 移除），
  关掉它能省掉 `GestureDetector` 的长按识别。该开关不影响滚动/缩放/翻页。`onTap` 被菜单收起
  与自动滚动暂停占用。
- `CommonActivity` 的协程 scope 是 **普通 `Job`，不是 `SupervisorJob`**，因此任何一个 `launch`
  子协程抛未捕获异常都会取消父 Job，**此后该 Activity 上所有 `launch` 都会静默失效**。凡是会抛
  异常的协程体都必须自己 try/catch（并放行 `CancellationException`）；不要再依赖「失败了弹个
  提示」这种兜底——提示本身也可能不可见（见上面 Toast 那条）。
- `targetSdk 28` — no scoped storage, no `android:exported` enforcement. Themes are hardcoded `Theme.AppCompat.Light*`; there is no dark mode. Native libs are ARM-only (`armeabi-v7a` + `arm64-v8a`; `armeabi`/ARMv5 dropped) and release is ABI-split into two APKs — see above.
- Umeng analytics is live (`common/statistic/Statistic.kt`, hardcoded `APP_KEY`); LeakCanary is debug-only. Bugly/Tinker are fully commented out — `AppConfig.BUGLY_APPID` is dead.

## Testing

- Only `junit:junit:4.12` is on the test classpath: **no Robolectric, no Mockito, and no `testOptions { unitTests.returnDefaultValues }`** anywhere. Any Android API touched from a unit test throws, so new unit tests must be pure JVM (extract the logic first, as `ContentTree` does).
- Real suites (both pure-JVM, Chinese backtick method names): `app/src/test/.../preview/ContentTreeTest.kt` (8 cases, TOC expand/collapse) and `app/src/test/.../common/CoverBuilderTest.kt` (7 cases, bookshelf cover grouping). `ExampleUnitTest`/`ExampleInstrumentedTest` are placeholders. Total 16 tests, 0 failures.

## Icons

- The launcher icon is generated, not hand-drawn: replace the root `NoPDF.png` and run `python tools/gen_icons.py` (needs Pillow ≥ 8.2 for `rounded_rectangle`; `pip install` is PEP 668-blocked, so use a venv). It writes the legacy set, the `mipmap-anydpi-v26` adaptive set, and the `_dev` variants. The script is idempotent — re-running produces byte-identical PNGs.
- `NoPDF.png` accepts two shapes and the script auto-detects which: **rounded card on white corners** (old style) or a **full-bleed design render** (current `NoPDF.png`; corners are orange, so the script adds `CARD_CORNER_RATIO` corners itself and drops it on white before the normal pipeline).
- **The adaptive foreground holds only the document graphic** — the orange card is the `@color/app_ic_launcher_background` background layer. Putting the whole card in the foreground produces a visible "double rounded square" (the card's own edge showing inside the system mask). Legacy icons still use the full card.
- The document is separated from the card by luminance, then **hole-filled**: the white "NO." text is brighter than the card, so a plain threshold punches it out. Any new artwork with light text on a dark shape needs this fill.
- `sample_card_color` reads only the **left/right vertical edges** at `CARD_SAMPLE_INSET` (0.24), skipping near-white and taking a median. It must sit between the corner radius (0.214) and the document half-width (~0.24) — a smaller inset averages in white, a larger one lands on the document. Current value `#FE7C26`.
- The Dev badge goes **horizontally centred below the document** in the adaptive foreground. Corner placement is geometrically impossible: the document's bbox corner sits 33.6dp from centre while the visible circle radius is only 36dp, and a corner badge needs `doc_diag + 2·half_diag ≤ 36dp`. The script raises a clear error rather than silently overlapping.
- `tools/dev_badge.png` is a hand-repaired asset: the original was extracted from the old mint icon and carried an opaque **red** patch in its top-left corner (78px, now recoloured to grey `#8B8B8B`). `--extract-badge` only works while `drawable-xxhdpi/app_ic_nopdf_dev.png` is still the original — that is no longer true, so treat `dev_badge.png` as the source of truth.
- `shrinkResources` stubs the `_dev` icons to 67 bytes in the **release** APK (unreferenced there); they are real in the debug APK. Not a bug.

## Traps

- The 24 `*.plantuml` files (root + every package, generated by SketchIt) are **stale**. `main/main.plantuml` still shows `AllFragment`/`CollectionAdapter`, ButterKnife `Unbinder`, and GridDecorations in the wrong package; the root diagram lists a `[bugly]` module that doesn't exist. Never use them to understand structure — read the code.
- `app/release/v2.2.0/{mapping.txt,output.json,resources.txt}` are historical release artifacts, not build inputs.
- `README.md` numbers have drifted from the code (it claims ~30 synthetic files and a ~1800-line `PreviewActivity`; it is 20 and ~1447). Prefer the code.

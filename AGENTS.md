# AGENTS.md

Android PDF reader ("NO PDF"), package `com.sigpher.nopdf`, forked from [YESPDF](https://github.com/aaronzzx/YESPDF). Two modules: `app` (the product) and `android-pdf-viewer` (local fork of barteksc/AndroidPdfViewer). Single `master` branch. No CI, no lint/test gate, no formatter. Commit messages are Chinese: `内容：<desc>` for normal work, `release <versionCode>-v<versionName>` for releases.

## Build

- Toolchain is frozen: Gradle 5.4.1 wrapper, AGP 3.4.1, Kotlin 1.3.61, `compileSdk 29`, `targetSdk 28`, Java 8 source/target. **Requires JDK 8.** Do not upgrade casually — the whole build depends on these.
- **Top gotcha — `local.properties`.** `app/build.gradle:35-45` does an unguarded `file('local.properties').newDataInputStream()` at *configuration* time, so *every* Gradle invocation fails if the file is missing — even `./gradlew clean` or `:app:testDebugUnitTest`. It is gitignored and **absent from a fresh checkout** (it is also absent right now). It needs `sdk.dir` **plus** `STORE_FILE_NAME`, `KEYSTORE_PASSWORD`, `STORE_ALIAS`, `KEY_PASSWORD`; both variants sign with the release keystore, so debug builds fail too.
- Commands:
  - `./gradlew :app:assembleDebug` — primary build check
  - `./gradlew :app:assembleRelease` — `minifyEnabled` + `shrinkResources`
  - `./gradlew :app:testDebugUnitTest` — JVM unit tests
  - one class: `./gradlew :app:testDebugUnitTest --tests "com.sigpher.nopdf.preview.ContentTreeTest"`
- `jcenter()` / `dl.bintray.com` are still listed in the root `build.gradle` but are dead. `mavenCentral()` + `maven.aliyun.com/repository/public` mirrors are present in both `buildscript` and `allprojects`, so deps (incl. Umeng) do resolve. README also claims a `plugins.gradle.org/m2` mirror — it is **not** in any build file. Trust the build files over the README.
- Release mapping lands in `app/build/outputs/mapping/release/mapping.txt` (AGP writes it from the `-verbose` flag). Do **not** re-add `-printmapping` — `app/proguard-rules.pro:108` explains it dirties the worktree. `proguardMapping.txt` is gitignored.
- Debug variant: `applicationId` + `.dev`, icon/label swapped via `manifestPlaceholders` (`app_icon`, `app_name`). Release uses `app_ic_launcher` / `app_name_en`. Because `AppConfig.AUTHORITY` is `BuildConfig.APPLICATION_ID + ".fileprovider"`, the FileProvider authority differs per variant (`…nopdf.dev.fileprovider` in debug) — don't hardcode it.
- `android-pdf-viewer` is a **deliberately patched** fork, not vanilla: `PDFView` exposes `MutableLiveData` (hence the explicit `api androidx.lifecycle:lifecycle-livedata-core`), and `core-ktx` is pinned to `1.3.0` because a dynamic `+` resolved to a class-file version AGP 3.4.1's Jetifier could not process. Keep it pinned. Its bintray block is dead — ignore it.
- **pdfium 没有文本 API，所以「取字」类功能目前一律没有实现。** `com.github.barteksc:pdfium-android:1.9.0`
  的 Java 层完全没有取字能力（无 `TextPage` / `loadTextPage` / `getTextBounded`），而 1.9.0 已是该
  坐标下的最新版本，无法通过升级获得。其 `libmodpdfium.so` 虽导出了 `FPDFText_*`（含
  `FPDFText_GetCharIndexAtPos`），但 `libjniPdfium.so` 未做 JNI 绑定。历史上曾引入
  `com.tom-roush:pdfbox-android` 做「选词查词」，但该功能已在 0.2.0 整体移除，**PDFBox 依赖
  及其传递依赖 BouncyCastle 也已一并删除**（同时省掉了 fontbox 的 cmap 资源与约 1MB dex）。
  若日后要做选词 / 全文搜索 / 复制，两条路：给 fork 的 `libjniPdfium.so` 补 `FPDFText_*` 绑定
  （最干净，且能复用 pdfium 已打开的句柄），或重新引入 PDFBox（代价是与 pdfium 重复解析同一份文件）。
- `com.blankj:utilcode` (`SPStaticUtils`, `PathUtils`, `StringUtils`, `FileUtils`, `GsonUtils`, …) is used across ~32 files but **never declared** — it arrives transitively via `com.aaron:base`. Be careful when touching the `exclude` block at `app/build.gradle:113-119`.

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
- **`armeabi`(ARMv5) 已从 `abiFilters` 移除**：该 ABI 早已废弃，minSdk 21 设备不再搭载，
  约省 0.67MB。
- **包体现状（0.2.1 release APK 9,116,313 B ≈ 8.7MB）**：native 库占 6,089,499 B（**67%**，
  其中 pdfium 约 4.75MB、要同时带 `arm64-v8a` 与 `armeabi-v7a`），`classes.dex` 1,644,535 B，
  `res/` 552,678 B，`resources.arsc` 394,260 B，`assets/` 已完全为空。
  **进一步压缩的最大杠杆是按 ABI 分包**（每包可再省约 2.9MB），其次是 PNG 调色板化
  （见 Icons 一节）与 R8 规则；`lib/` 已无冗余 ABI，`assets/` 已无内容，语言资源已用
  `resConfigs` 白名单过滤过。改动构建配置后请重新量一次再下结论。
- **已修：读页时的两处逐帧开销。** `drawBookmark` 原先挂在 `PDFView.onDrawAll` 上，且每次
  调用都 `BitmapFactory.decodeResource(resources, R.drawable.app_img_bookmark)`——只要当前页
  有书签，**每一帧都会重新解码一张 PNG**；现改为 `by lazy` 缓存一次（`bookmarkBitmap`）。
  `GreyUI.grey` 原先在灰度**关闭**时也会 `setLayerType(LAYER_TYPE_HARDWARE, null)`，而
  `LAYER_TYPE_HARDWARE` 即使不带 paint 也会让 decorView 先渲染进全屏离屏缓冲——由于灰度默认
  关闭，等于**每个界面每帧都多一次全屏拷贝**；现关闭时显式回到 `LAYER_TYPE_NONE`。
- **`AndroidManifest` 已开 `largeHeap="true"`**：阅读页要分配整页位图，且 fork 的 part 缓存按
  **张数**计（`Constants.Cache.CACHE_SIZE = 120` × `PART_SIZE` 256 × 4B ≈ 30MB 上限），
  小堆设备上大页 PDF 会因 GC 抖动而掉帧。
- **仍存在（未动）**：`DataManager.updatePDFs()` / `updateAll()` 是**主线程全量查库 + 重建
  内存列表 + 重建封面列表**，在 `PreviewActivity.onPause` 的 `updateDB` 观察者里会跑一次，
  书库大时会让「退出阅读页」掉帧；书架各处也用 `notifyDataSetChanged()` 全量重绑。
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

- Codegen output is **committed** at `app/src/main/java/com/sigpher/nopdf/common/greendao/` (`targetGenDir 'src/main/java'`). Bump `greendao { schemaVersion }` (`app/build.gradle:90-96`) and regenerate; never hand-edit `DaoMaster`/`*Dao`.
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
- Cross-component messaging is EventBus (event POJOs in `common/event/`, plus `common/LiveDataBus.kt` for sticky LiveData). Add a new event object per feature rather than reusing another feature's.
- Kotlin-dominant; the Java is mostly generated GreenDAO code, `App`/`DataManager`/`AppConfig`/`PdfUtils`, and a few holders. Match the style of the file you edit.
- Entrypoints: `main/MainActivity` (LAUNCHER, `singleTask`, splash theme) and `preview/PreviewActivity` (`exported=true`, handles `application/pdf` VIEW intents and imports the file through `DBHelper.insert`).
- `preview/PreviewActivity.kt` is ~1447 lines mixing rendering, gestures, bookmarks, TOC and page export — read it in sections. TOC expand/collapse is extracted into `preview/ContentTree.kt`, which (with its test) is the only extracted-and-tested piece.
- Legacy names `AllFragment2` / `AllAdapter2` / `CollectionFragment2` / `CollectionAdapter2` are upstream YESPDF leftovers with no `…1` counterpart. Don't rename or "fix" them.
- `resourcePrefix 'app'` (`app/build.gradle:18`): every new resource must be `app_…`. Locales: `values` (en), `values-zh-rCN`, `values-zh-rHK/rMO/rSG/rTW`.
- **`common/Settings.kt` 的几个默认值是刻意选定的，不要「顺手改回」**：
  `swipeHorizontal = false`（默认纵向滚动阅读）、`clickFlipPage = false`（点击只收放菜单、不翻页）、
  `linearLayout = true`（书架默认列表布局）。`SPStaticUtils.getBoolean(key, default)` 的默认值
  只在**没写过该 key 时**生效，所以改动会影响「装了新版但从未手动设置过该项」的用户；
  已在 SP 中留下键值的用户不受影响。
- `PreviewActivity` 调用了 `disableLongpress()`：长按没有任何消费者（选词查词已在 0.2.0 移除），
  关掉它能省掉 `GestureDetector` 的长按识别。该开关不影响滚动/缩放/翻页。`onTap` 被菜单收起
  与自动滚动暂停占用。
- `CommonActivity` 的协程 scope 是 **普通 `Job`，不是 `SupervisorJob`**，因此任何一个 `launch`
  子协程抛未捕获异常都会取消父 Job，**此后该 Activity 上所有 `launch` 都会静默失效**。凡是会抛
  异常的协程体都必须自己 try/catch（并放行 `CancellationException`）；不要再依赖「失败了弹个
  提示」这种兜底——提示本身也可能不可见（见上面 Toast 那条）。
- `targetSdk 28` — no scoped storage, no `android:exported` enforcement. Themes are hardcoded `Theme.AppCompat.Light*`; there is no dark mode. Native libs are ARM-only and only `armeabi-v7a` + `arm64-v8a` (`armeabi`/ARMv5 was dropped — see above).
- Umeng analytics is live (`common/statistic/Statistic.kt`, hardcoded `APP_KEY`); LeakCanary is debug-only. Bugly/Tinker are fully commented out — `AppConfig.BUGLY_APPID` is dead.

## Testing

- Only `junit:junit:4.12` is on the test classpath: **no Robolectric, no Mockito, and no `testOptions { unitTests.returnDefaultValues }`** anywhere. Any Android API touched from a unit test throws, so new unit tests must be pure JVM (extract the logic first, as `ContentTree` does).
- `app/src/test/.../preview/ContentTreeTest.kt` (8 pure-JVM cases, Chinese backtick method names) is the only real suite; `ExampleUnitTest`/`ExampleInstrumentedTest` are placeholders.

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

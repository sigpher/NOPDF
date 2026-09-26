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
- **pdfium 没有文本 API。** `com.github.barteksc:pdfium-android:1.9.0` 的 Java 层完全没有
  取字能力（无 `TextPage` / `loadTextPage` / `getTextBounded`），而 1.9.0 已是该坐标下的最新版本，
  无法通过升级获得。其 `libmodpdfium.so` 虽导出了 `FPDFText_*`（含 `FPDFText_GetCharIndexAtPos`），
  但 `libjniPdfium.so` 未做 JNI 绑定。因此凡是需要「拿到文字或文字坐标」的需求（选词、搜索、
  复制）都只能走 `com.tom-roush:pdfbox-android`（已引入），代价是与 pdfium 重复解析同一份文件。
- `PDFView` 的 `pdfFile` 字段是包内可见、`swipeVertical` 与 `PdfFile.isVertical` 是 private，
  外部无法自行把触点换算成页面坐标；已新增 `PDFView.getPageOriginOnCanvas(int)` 封装该换算。
  页面原点的主轴随滚动方向变化（竖向主轴是 Y），不要想当然只用 `getPageOffset`。
- `com.blankj:utilcode` (`SPStaticUtils`, `PathUtils`, `StringUtils`, `FileUtils`, `GsonUtils`, …) is used across ~32 files but **never declared** — it arrives transitively via `com.aaron:base`. Be careful when touching the `exclude` block at `app/build.gradle:106-113`.

## Database (GreenDAO, schemaVersion 3)

- Codegen output is **committed** at `app/src/main/java/com/sigpher/nopdf/common/greendao/` (`targetGenDir 'src/main/java'`). Bump `greendao { schemaVersion }` (`app/build.gradle:84`) and regenerate; never hand-edit `DaoMaster`/`*Dao`.
- **Two independent migration layers — a schema change usually needs both:**
  1. Schema: `UpdateOpenHelper.onUpgrade` → `MigrationHelper.migrate`. Its `onDropAllTables` calls `DaoMaster.dropAllTables`, which drops **every** table, so all DAOs (`PDFDao`, `CollectionDao`, `RecentPDFDao`) must stay in the vararg list at `UpdateOpenHelper.java:45` — an omitted DAO is recreated empty (data loss).
  2. Data backfill: `App.onCreate` calls `DBHelper.migrate()` only when `!isFirstInstall()` (compares `firstInstallTime == lastUpdateTime`) *and* a `DB_VERSION` pref is below `DaoMaster.SCHEMA_VERSION`. `migrate()` renumbers `position` and resets `scaleFactor`. A schema bump alone leaves this stale; the first-install guard also means the backfill never runs on a clean device.
- `DBHelper` is a Kotlin `object` holding a single `DaoSession`; `DataManager` is the in-memory cache over it. Persist through `DBHelper`, then refresh `DataManager` (`DataManager.updateAll()`).
- `DBHelper.getName(path)` (private) strips the last 4 characters — it assumes a `.pdf` extension and does no validation.
- Import is not just DB rows: `DBHelper.insertPDFs` renders page 0 and writes a cover bitmap to `${PathUtils.getInternalAppDataPath()}/$name.jpg`, synchronously on the import path. Renaming a PDF orphans its cover file.
- Backup/restore is a hand-rolled format in `MainActivity`: one Base64-encoded Gson `Backup` JSON per group, written as `$groupName.txt` into `${PathUtils.getExternalAppDataPath()}/files`. Restore reads *every* `.txt` in that dir. Changing the `Backup`/`PDF` bean shape silently breaks existing user backups — treat it as a data format, not an implementation detail. **No automated coverage; verify by hand.**

## Architecture

- Hand-written MVP. Feature packages: `main` (bookshelf), `preview` (reader), `filepicker`, `settings`, `about`; shared code in `common` (`DBHelper`, `DataManager`, `Settings`, `bean/`, `greendao/`, `event/`, `statistic/`, `utils/`, `widgets/`). `common/MVP.kt` holds the `IModel`/`IView`/`IPresenter` bases; each feature has `XxxPresenter` + `I*Contract`.
- `BaseActivity` / `BaseFragment` / `IContract` / `ImageLoader` / `UiManager` / `DialogManager` / `ToastUtils` come from external `com.aaron:base:1.1.5-beta9` (not in this repo) — decompile the AAR if you need their behavior.
- View binding is `kotlinx.android.synthetic` (20 files). Deprecated, but it is the convention — don't migrate a file to ViewBinding as a drive-by. Layouts do not use `android:onClick`.
- Cross-component messaging is EventBus (event POJOs in `common/event/`, plus `common/LiveDataBus.kt` for sticky LiveData). Add a new event object per feature rather than reusing another feature's.
- Kotlin-dominant; the Java is mostly generated GreenDAO code, `App`/`DataManager`/`AppConfig`/`PdfUtils`, and a few holders. Match the style of the file you edit.
- Entrypoints: `main/MainActivity` (LAUNCHER, `singleTask`, splash theme) and `preview/PreviewActivity` (`exported=true`, handles `application/pdf` VIEW intents and imports the file through `DBHelper.insert`).
- `preview/PreviewActivity.kt` is ~1390 lines mixing rendering, gestures, bookmarks, TOC and page export — read it in sections. TOC expand/collapse is extracted into `preview/ContentTree.kt` and is the one thing with real test coverage.
- Legacy names `AllFragment2` / `AllAdapter2` / `CollectionFragment2` / `CollectionAdapter2` are upstream YESPDF leftovers with no `…1` counterpart. Don't rename or "fix" them.
- `resourcePrefix 'app'` (`app/build.gradle:18`): every new resource must be `app_…`. Locales: `values` (en), `values-zh-rCN`, `values-zh-rHK/rMO/rSG/rTW`.
- `PreviewActivity` 的长按已被选词查词占用（原先是 `disableLongpress()`）。该开关只影响
  `GestureDetector` 的长按识别，`DragPinchManager.onLongPress` 仅转发事件、不参与滚动缩放，
  所以打开它不影响既有手势。`onTap` 仍被菜单收起/自动滚动暂停占用。
- `targetSdk 28` — no scoped storage, no `android:exported` enforcement. Themes are hardcoded `Theme.AppCompat.Light*`; there is no dark mode. Native libs are ARM-only (`armeabi`, `armeabi-v7a`, `arm64-v8a`).
- Umeng analytics is live (`common/statistic/Statistic.kt`, hardcoded `APP_KEY`); LeakCanary is debug-only. Bugly/Tinker are fully commented out — `AppConfig.BUGLY_APPID` is dead.

## Testing

- Only `junit:junit:4.12` is on the test classpath: **no Robolectric, no Mockito, and no `testOptions { unitTests.returnDefaultValues }`** anywhere. Any Android API touched from a unit test throws, so new unit tests must be pure JVM (extract the logic first, as `ContentTree` does).
- `app/src/test/.../preview/ContentTreeTest.kt` (8 pure-JVM cases, Chinese backtick method names) is the only real suite; `ExampleUnitTest`/`ExampleInstrumentedTest` are placeholders.

## Icons

- The launcher icon is generated, not hand-drawn: replace the root `NoPDF.png` and run `python tools/gen_icons.py` (needs Pillow). It writes both the legacy and `mipmap-anydpi-v26` adaptive sets plus the `_dev` variants. The Dev badge comes from `tools/dev_badge.png`; re-extract with `--extract-badge` only while `drawable-xxhdpi/app_ic_nopdf_dev.png` is still original. The script is idempotent.

## Traps

- The 24 `*.plantuml` files (root + every package, generated by SketchIt) are **stale**. `main/main.plantuml` still shows `AllFragment`/`CollectionAdapter`, ButterKnife `Unbinder`, and GridDecorations in the wrong package; the root diagram lists a `[bugly]` module that doesn't exist. Never use them to understand structure — read the code.
- `app/release/v2.2.0/{mapping.txt,output.json,resources.txt}` are historical release artifacts, not build inputs.
- `README.md` numbers have drifted from the code (it claims ~30 synthetic files and a ~1800-line `PreviewActivity`; it is 20 and 1389). Prefer the code.

# AGENTS.md

Android PDF reader app ("NO PDF"), package `com.sigpher.nopdf`. Two Gradle modules: `app` (the product) and `android-pdf-viewer` (vendored fork of Bartosz Schiller's AndroidPdfViewer). Single `master` branch; commit messages are in Chinese.

## Build

- Toolchain is old and frozen: Gradle 5.4.1 wrapper, AGP 3.4.1, Kotlin 1.3.61, compileSdk 29, targetSdk 28 (app) / 29 (viewer), Java 8. Do not upgrade casually — everything depends on these versions.
- Repos include `jcenter()` and `dl.bintray.com`, both sunset. Clean builds may fail to resolve deps (e.g. Umeng SDK) unless Gradle/Maven caches are already warm.
- `./gradlew :app:assembleDebug` is the primary build check. Release build minifies via `minifyEnabled` + `shrinkResources`. Both build types use the release keystore.
- **Signing gotcha**: `app/build.gradle:36-46` reads `STORE_FILE_NAME`, `KEYSTORE_PASSWORD`, `STORE_ALIAS`, `KEY_PASSWORD` from root `local.properties` at configuration time — any build (debug or release) throws if they are missing. `local.properties` is gitignored and currently only contains `sdk.dir`.
- Debug variant appends `.dev` to `applicationId` and swaps icon/name via manifest placeholders (see `buildTypes` in `app/build.gradle`).
- `android-pdf-viewer` publishes via bintray config; ignore unless asked.

## Database (GreenDAO)

- DAO codegen is **committed** in `app/src/main/java/com/sigpher/nopdf/common/greendao/` (DaoMaster/DaoSession/PDFDao/CollectionDao/RecentPDFDao). Do not hand-edit generated files; regenerate after schema changes.
- Schema version is set in the `greendao { schemaVersion 3 }` block of `app/build.gradle`.
- Upgrades are non-destructive: `UpdateOpenHelper.onUpgrade` uses `MigrationHelper.migrate`. Its `onDropAllTables` drops **every** table in `DaoMaster`, so all DAOs must be passed to the migrate call (currently `PDFDao`, `CollectionDao`, `RecentPDFDao`) — any DAO left out is recreated as an empty table.

## Code conventions / architecture

- `resourcePrefix 'app'` is set in `app/build.gradle:19`: every new resource must be named `app_...`.
- Hand-written MVP: each feature package (`main`, `preview`, `filepicker`, `about`, `settings`) holds `XxxActivity`, `XxxFragment`, `XxxPresenter`, and a `I*Contract`. `common/` holds shared code (`DBHelper`, `DataManager`, settings, beans, events, widgets).
- `BaseActivity`/`BaseFragment`/`IContract` come from external dep `com.aaron:base:1.1.5-beta9` (not in this repo). View binding via `kotlinx.android.synthetic` (ButterKnife was removed); cross-component comms via EventBus; `DataManager` is the in-memory cache over `DBHelper`.
- Entrypoints: `main/MainActivity` (launcher); `preview/PreviewActivity` also handles `application/pdf` VIEW intents and imports files into the DB (`DBHelper.insert`).
- Mixed Java + Kotlin with the old `kotlin-android-extensions` synthetic-view syntax; follow the existing style in each file.
- NDK `abiFilters` in `app/build.gradle:29-33`: `armeabi`, `armeabi-v7a`, `arm64-v8a` only.

## Other

- The `.plantuml` files (root and module dirs) are stale generated class diagrams — they reference renamed/deleted classes. Do not use them as a source of truth for structure.
- Tests are trivial placeholders only (`app/src/test/.../ExampleUnitTest.java`, `app/src/androidTest/.../ExampleInstrumentedTest.java`). No CI, no lint/test gate.
- `app/release/v2.2.0/` contains historical release artifacts (mapping.txt, output.json) — not build inputs.
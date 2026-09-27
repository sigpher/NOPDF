# NO PDF

> 本项目基于 [YESPDF](https://github.com/aaronzzx/YESPDF) 二次开发（上游作者 [@aaronzzx](https://github.com/aaronzzx)）。

一款简洁、好用的 Android PDF 阅读器。

NO PDF 是一款专注于本地 PDF 阅读的 Android 应用：自动扫描并导入手机上的 PDF 文件，按文件夹或自定义方式分组管理书架，支持竖屏/横屏、"点击翻页、音量键翻页、自动滚动"等多种阅读方式，并内置书签、目录、进度记忆、全文搜索、备份恢复等实用功能。

- 包名：`com.sigpher.nopdf`
- 当前版本：0.5.4（versionCode 19）
- 支持系统：Android 5.0（API 21）及以上（targetSdk 28）
- 支持语言：英文、简体中文、繁体中文

## 功能特性

### 文件管理
- 自动扫描设备存储中的 PDF 文件（支持手动选择文件夹导入）
- 从文件管理器通过「打开方式」直接打开并导入 PDF
- 书架分组：按文件夹名自动分组，或自定义分组名；支持对已入库文件重新分组
- 分组封面色（每本书自动生成封面，分组展示封面拼图）
- 全局搜索：按文件名在所有库内/存储文件中检索
- 最近阅读：展示最近读过的书，可限制显示数量（3/6/9/12/15/不限）

### 阅读体验
- 竖屏与横屏阅读；横向阅读不支持自动滚动
- 点击翻页、音量键翻页两种翻页方式
- 自动滚动阅读（音量键呼出菜单、点击屏幕暂停/继续、拖拽进度条调节速度）
- 书签：添加、跳转、删除
- 目录/大纲：可展开的树形列表（RecyclerView 实现），点击行首图标展开/折叠、点击整行跳转到对应页
- 进度记忆：自动记录阅读进度与阅读时间，并排序最近阅读
- 缩放与布局：随意缩放、快速缩放、线性布局切换
- 页面间隔：纵向阅读时相邻两页可留 0 / 4 / 8 / 12 / 16 / 24 dp 间距（横向整页翻页不生效）
- 显示效果：全局灰度（实验性）、颜色反转、状态栏显示/隐藏、保持屏幕常亮
- 导出图片：将当前页导出为高清图片（保存至 `/Pictures/NOPDF/<书名>/第N页.png`）
- 桌面快捷方式：为指定 PDF 创建桌面快捷方式（Android 7.1+）

### 数据
- 备份 / 恢复：将分组数据备份到应用私有目录（`/storage/emulated/0/Android/data/com.sigpher.nopdf/files`），可迁移后恢复
- 删除本地文件：删除书中记录时可一并删除源文件

### 其他
- 密码保护 PDF 支持
- 捐赠（微信支付）

## 界面结构

| 模块 | 说明 |
| --- | --- |
| 首页书架 | 最近阅读、全部书籍、自动扫描三页，支持分组封面展示与批量操作 |
| 文件选择 | 存储浏览、扫描结果、查看全部，多选导入 |
| 阅读页 | 左右滑动翻页、书签/目录侧边栏、阅读设置 |
| 设置 | 阅读方式、翻页方式、自动滚动、灰度、布局等偏好 |
| 关于 | 版本信息、开源库列表、反馈、源码、评分、捐赠 |

## 技术架构

- 语言：Kotlin 为主 + Java（Kotlin 1.3.61，使用 `kotlin-android-extensions` 合成视图）；Java 侧仅剩 GreenDAO 生成代码、数据库迁移工具与少量实体/工具类
- 架构：手写 MVP（每个功能包包含 `XxxActivity`/`XxxFragment` + `XxxPresenter` + `I*Contract`）
- 数据层：GreenDAO（SQLite ORM，schemaVersion 3）、`DBHelper`（DAO 访问）+ `DataManager`（内存缓存）
- 视图绑定：Kotlin 合成视图（`kotlinx.android.synthetic`）；异步与定时：`kotlinx-coroutines`；跨组件通信：EventBus
- 渲染：模块 `android-pdf-viewer`（[AndroidPdfViewer](https://github.com/barteksc/AndroidPdfViewer) 的本地 fork）负责 UI 与手势，底层 PDF 引擎为 **MuPDF 1.28.5**
  - 引擎能力经 `com.github.barteksc.pdfviewer.engine.PdfEngine` 接口收敛，唯一实现是 `MupdfEngine`；其上（渲染、缩放、手势、目录树）与之下（MuPDF）互不直接依赖，因此换引擎只需改 `PdfEngines.create()`
  - 渲染路径用引擎中立类型（`EngineDocument` / `EngineSize` / `EngineSizeF` / `EngineBookmark` / `EngineLink` / `EngineMeta` / `PasswordRequiredException`）传递，pdfium 与 MuPDF 的类型不会外泄
- 基础组件：`com.aaron:base`（提供 `BaseActivity`/`BaseFragment`/`IContract`/`ImageLoader` 等）。该库自身的 http / webview / download / timer 能力簇（Retrofit、OkHttp、腾讯 Sonic、OkDownload、RxJava2、AutoDispose）在本项目中完全没有被引用，已在 `app/build.gradle` 中排除其传递依赖

### 模块划分

- `app`：主应用（`com.sigpher.nopdf`），核心功能所在
  - `main`：书架（最近/全部/自动扫描）
  - `preview`：PDF 阅读（含应用入口 `PreviewActivity`，处理 `application/pdf` VIEW Intent）
  - `filepicker`：文件选择与导入
  - `settings`：设置
  - `about`：关于页
  - `common`：共享代码（`DBHelper`、`DataManager`、`Settings`、数据库实体与 DAO、事件、自定义控件）
- `android-pdf-viewer`：AndroidPdfViewer 的本地 fork，负责 PDF 渲染

### 主要入口

- `main/MainActivity`：启动页（书架）
- `preview/PreviewActivity`：阅读页，同时作为系统 `application/pdf` 的打开方式，打开外部 PDF 时自动导入数据库

## 构建

> 注意：项目工具链较老且已冻结（Gradle 5.4.1 / AGP 3.4.1 / Kotlin 1.3.61 / Java 8 / compileSdk 29）。项目原本依赖的 `jcenter()`、`dl.bintray.com` 等仓库均已关停，根 `build.gradle` 中已补入 `mavenCentral()` 与阿里云 public 镜像作为替代源，依赖可正常解析。

### 环境要求

- JDK 8
- Android SDK（compileSdk 29，建议 Android 10 SDK）
- Android NDK（用于编译 MuPDF 原生库，r21 起可用，实测 r27 可用）
- 签名密钥信息写入根目录 `local.properties`
- MuPDF 1.28.5 源码（不入库，见下）

### 准备 MuPDF 原生库

PDF 引擎为 MuPDF，其 Java 绑定以源码形式参与编译，原生库需预先编译。两者都不随仓库分发
（源码树 68 MB、两个 ABI 的 `.so` 合计 18 MB）。

为什么不用 Gradle 的 `externalNativeBuild`：本项目冻结在 AGP 3.4.1，而 NDK r27 采用统一
工具链布局、**不再带 `platforms/` 目录**，AGP 3.4.1 的 NDK 集成无法识别该布局。因此改用裸
`ndk-build` 预编译，Gradle 只把产物目录接进 `jniLibs`，全程不碰 NDK。

```bash
# 1. 获取并解压 MuPDF 1.28.5 源码（Artifex 官方发布，不入库）
tar xzf mupdf-1.28.5-source.tar.gz

# 2. 在根目录 local.properties 中补充两行
#    ndk.dir=/path/to/android-sdk/ndk/27.x.x
#    mupdf.dir=/path/to/mupdf-1.28.5-source

# 3. 编译两个 ABI 的原生库（首次会跑 make generate 生成内置字体的 C 源码）
sh tools/build_mupdf.sh
```

产物落在 `mupdf.dir/build/android/libs/<abi>/libmupdf_java.so`，Gradle 会自动接入。
若 `local.properties` 缺少 `mupdf.dir`，或某个 ABI 的 `.so` 未编译，**构建会在配置阶段
直接报错并给出上述提示**——因为 `splits.abi` 关掉了 `universalApk`，缺一个 ABI 时 AGP
仍会产出那个 APK，只是里面没有原生库，装上后一进阅读页就崩。

### 签名配置

`local.properties` 中除 `sdk.dir` 外还需配置以下键用于签名（debug/release 均使用同一签名，缺失会直接构建失败）：

```properties
sdk.dir=D\:\\android_sdk
STORE_FILE_NAME=<keystore 路径>
KEYSTORE_PASSWORD=<密钥库密码>
STORE_ALIAS=<别名>
KEY_PASSWORD=<密钥密码>
```

### 编译命令

```bash
# Debug 构建（applicationId 追加 .dev 后缀，使用开发图标与 App 名）
./gradlew :app:assembleDebug

# Release 构建（开启 minify + shrinkResources，按 ABI 拆分出两个 APK）
./gradlew :app:assembleRelease

# 单元测试（目录树展开/折叠逻辑等）
./gradlew :app:testDebugUnitTest

# 清理
./gradlew clean
```

### 发布产物（按 ABI 拆分）

Release 使用 `splits.abi` 按 ABI 拆分，`assembleRelease` 会输出两个各自只带一份 native 库的 APK：

| APK | ABI | 体积 | 适用设备 |
| --- | --- | --- | --- |
| `app/build/outputs/apk/release/app-arm64-v8a-release.apk` | `arm64-v8a` | 约 8.1 MB | 绝大多数现代 64 位机型 |
| `app/build/outputs/apk/release/app-armeabi-v7a-release.apk` | `armeabi-v7a` | 约 7.3 MB | 老旧的 32 位机型 |

- 不再产出同时含两个 ABI 的通用包（`universalApk false`），`armeabi`(ARMv5)、`x86`、`x86_64` 均不产出。
- 两个 APK 使用**同一签名与同一 versionCode**，安装时按设备 ABI 选择对应包即可；换装另一个 ABI 的包不影响数据（签名一致）。
- 每个 APK 只含一个 native 库：`libmupdf_java.so`。

### 其他构建要点

- Debug 变体通过 manifest 占位符切换图标/名称；两个变体都使用 release 签名配置
- 桌面图标为自适应图标（`mipmap-anydpi-v26/*.xml` + 各密度前景与 legacy 位图）；更换图标只需替换根目录 `NoPDF.png`，再执行 `python tools/gen_icons.py` 重新生成（Dev 角标素材见 `tools/dev_badge.png`）
- Native 库只包含 ARM ABI（`armeabi-v7a` / `arm64-v8a`；`armeabi` 已移除），且 release 按 ABI 拆分为两个 APK（见「发布产物」）
- App 内所有资源必须使用 `app_` 前缀（`resourcePrefix 'app'`）
- 数据库升级为非破坏式迁移（`UpdateOpenHelper` + `MigrationHelper`），修改数据库结构需同时更新 `greendao { schemaVersion }` 与迁移监听列表

## 第三方开源库

| 库 | 作者 | 用途 |
| --- | --- | --- |
| AndroidPdfViewer | barteksc | PDF 渲染视图（本地 fork） |
| MuPDF | Artifex | PDF 解析渲染引擎（替代 PdfiumAndroid） |
| RealtimeBlurView | mmin18 | 实时模糊背景 |
| ParallaxBackLayout | anzewei | 滑动返回 |
| greenDAO | greenrobot | SQLite ORM |
| Glide | bumptech | 图片加载（经 `com.aaron:base` 的 `ImageLoader` 调用） |
| StatusBarUtil | Jaeger | 状态栏样式（经 `com.aaron:base` 调用） |
| EventBus | greenrobot | 组件通信 |
| kotlinx.coroutines | JetBrains | 协程与定时任务（自动滚动、文件扫描） |
| BaseRecyclerViewAdapterHelper | CymChad | 列表适配器（含拖拽排序） |
| LeakCanary | Square | 内存泄漏检测（仅 debug 变体） |
| Umeng SDK | 友盟 | 统计与崩溃上报 |

## 已知限制

- `targetSdk` 仍为 28：未适配 Android 10+ 的分区存储，也未处理 Android 12+ 对 `android:exported` 的强制要求
- 视图绑定仍使用已废弃的 `kotlinx.android.synthetic`（20 个文件）
- `preview/PreviewActivity.kt` 体量较大（约 1447 行），混合了渲染、手势、书签、目录与导出等职责
- 无深色模式（固定使用 `Theme.AppCompat.Light`）
- 单元测试覆盖有限：目前仅目录树展开/折叠（`ContentTreeTest`）与分组封面构建（`CoverBuilderTest`）有纯 JVM 回归测试；数据库迁移、备份/还原等高风险逻辑尚无自动化回归
- **0.2.0 起移除了「选词查词」（长按英文单词查词典）功能**，同时移除了 PDFBox 及其传递依赖 BouncyCastle 以缩减包体。若需要该功能，请在 issue 中反馈
- 换用 MuPDF 后包体变大：`arm64-v8a` 约 5.9 MB → 8.1 MB，`armeabi-v7a` 约 5.7 MB → 7.3 MB。MuPDF 的 `.so` 内含完整的排版与字体栈，而 pdfium 1.9.0 裁剪得更激进。若体积敏感，可考虑在 `tools/build_mupdf.sh` 的 `APP_OPTIM` 基础上追加 `MUPDF_EXTRA_CFLAGS` 关掉用不到的子系统（MuPDF 默认打包了 mujs、extract、cmarkgfm、openjpeg 等本应用完全不用的组件）
- **换引擎未经真机渲染验证**：MuPDF 具备取字能力（`Page.toStructuredText()`、全文搜索等），但本项目尚未启用；此外现有的 16 个单元测试**检测不到换引擎带来的渲染回归**（`ContentTree` 刻意不依赖引擎类型），升级前请手动核对渲染效果

## 贡献与反馈

- 反馈问题 / 建议：应用内「关于 → 反馈」或邮件 `aaronzzxup@gmail.com`
- 源码：本仓库 [sigpher/NOPDF](https://github.com/sigpher/NOPDF)；上游项目 [aaronzzx/YESPDF](https://github.com/aaronzzx/YESPDF)
- 提交规范：commit message 使用中文

## 许可证

[GNU Affero General Public License v3.0](LICENSE)

本项目基于 [YESPDF](https://github.com/aaronzzx/YESPDF) 修改而来。YESPDF 及其上游
[AndroidPdfViewer](https://github.com/barteksc/AndroidPdfViewer) 原为 Apache License 2.0；
自本项目改用 [MuPDF](https://mupdf.com/) 作为 PDF 引擎起，整体许可变更为 AGPL-3.0。

原因：MuPDF 采用 AGPL-3.0（源码树中的 `COPYING`），且 MuPDF 官方未发布 Android 预编译
产物，本项目自行编译其 `libmupdf_java.so` 并随应用分发。AGPL 的第 13 条要求向通过网络
使用本程序的用户提供对应源码，本应用不提供网络服务，故该条不适用；但**分发应用本身
即构成「携带」**，必须按 AGPL 提供完整对应源码。

### 第三方组件

| 组件 | 许可 | 用途 |
| --- | --- | --- |
| [MuPDF](https://mupdf.com/) 1.28.5 | AGPL-3.0 | PDF 解析与渲染引擎（`libmupdf_java.so`） |
| MuPDF 内置的 Noto / Source Han 字体 | OFL-1.1 / 各自许可 | CJK 及多语言文字渲染所需的嵌入字体 |
| AndroidPdfViewer（`android-pdf-viewer/`） | Apache-2.0 | 阅读器 UI 与手势/缩放逻辑 |

MuPDF 源码不随本仓库分发，需自行获取后按 `tools/build_mupdf.sh` 的说明编译。详见
[构建说明](#构建)。

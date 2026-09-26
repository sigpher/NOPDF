# MuPDF 的 Java 绑定（com.artifex.mupdf.fitz）必须整包保留，供 app 消费时套用。
#
# 为什么必须整包 keep：libmupdf_java.so 用硬编码的类名/方法名/签名做 JNI 查找
# （FindClass("com/artifex/mupdf/fitz/Document")、GetMethodID(..., "loadPage",
# "(II)Lcom/artifex/mupdf/fitz/Page;") 之类）。只要 R8 改名或删掉其中任何一个，
# 查找就失败，表现为运行时 UnsatisfiedLinkError / NoSuchMethodError。
#
# AGP 内置的 proguard-android-optimize.txt 只带
#     -keepclasseswithmembernames class * { native <methods>; }
# 两个洞：
#   1) 没有 includedescriptorclasses，于是只出现在 native 方法「签名」里的类照样被改名
#      （本项目实测 Matrix -> c.c.a.a.a、SeekableInputStream -> c.c.a.a.b）；
#   2) 该规则只保「名字」，不保「存在」——实测 Document.needsPassword /
#      authenticatePassword / countPages / loadPage / loadOutline / getMetaData /
#      resolveLinkDestination 在 release dex 里整个消失。
# 换 pdfium 时仓库里的 -keep class com.shockwave.** 正是为此，迁到 MuPDF 后不能丢。
-keep class com.artifex.mupdf.fitz.** { *; }

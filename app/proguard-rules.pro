# ============================================================
# 包体 / 冷启动相关
# ============================================================
# release 已开启 R8 + 资源压缩。Hilt / Room / Coil / Compose / Coroutines
# 都自带 consumer rules，正常情况下不需要在这里重复 keep。

# 崩溃栈保留行号，否则线上堆栈全是 "Unknown Source"
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 如果 release 包出现 ClassNotFoundException / NoSuchMethodError 这类 R8 误删问题：
# 先临时加 -dontobfuscate 定位到具体类，再补精确的 keep 规则。
# 不要直接 -keep class com.ink.gallery.** { *; } —— 那等于白开 R8。

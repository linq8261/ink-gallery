# 弄墨相册（ink-gallery）

Android 本地相册应用，Kotlin + Jetpack Compose，纯本地 MediaStore 读取，不联网。

## 功能（骨架阶段）

- 首页两个页面，左右滑动切换：**相册页**（按文件夹分组）、**相片页**（全部照片，4 列平铺）。
- 标题栏标题跟随页面切换，右侧为设置入口；标题栏下方为 4dp 滑动指示条。
- 相册页点击分组进入该相册的照片列表（4 列平铺）。
- 任意照片点击进入全屏查看：双击缩放、左右翻页。
- 设置页目前只有「关于」入口。

## 技术栈

Kotlin 2.0.21 / AGP 8.5.2 / Compose BOM 2024.12.01 / Material3 / Hilt / Navigation /
Coil（图片加载）/ Paging 3（大图库分页）。无 Room、无 Media3、无 DataStore。

## 目录

```
app/src/main/java/com/ink/gallery/
├── data/        MediaStore 查询与分页数据源
├── di/          Hilt 模块
├── feature/     home / buckets / bucketdetail / photos / viewer / settings
├── navigation/  路由
├── ui/          theme + 通用组件
└── util/        权限、格式化
```

## 构建

```
.\gradlew.bat assembleRelease
```

或直接执行 `build_apk.ps1`，APK 会复制到桌面并重命名为 `ink-gallery-<版本名>-<版本号>.apk`。

签名信息从根目录 `local.properties` 读取（`signing.storeFile` / `signing.storePassword` /
`signing.keyAlias` / `signing.keyPassword`），缺失时产出未签名包。

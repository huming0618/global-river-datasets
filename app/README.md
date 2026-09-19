# 离线河网地图（Android）

基于 **MapLibre Native** 的纯离线示例应用：深色底图 + 亮青色河网高亮，按 GPS 展示当前位置附近河流。

打开本目录（`app/`）即可用 Android Studio 导入工程。

## 功能

- **完全离线底图**：`assets/style-dark.json` 无网络瓦片 URL，纯背景/陆地填充。
- **捆绑示意河网**：`assets/rivers-chengdu.geojson`（成都周边约 30.67°N, 104.06°E），合成简化线，含岷江/锦江/府河等示意名称。
- **定位**：申请精确位置；拒绝或失败时默认成都中心，并用中文 Snackbar/Toast 提示。
- **空间过滤**：计算到各河段折线的最近距离，约 **40 km** 内河段以亮青色高亮，并在底部列出名称与距离；其余河段以半透明青色显示。

## 环境要求

- JDK 17+（推荐 21）
- Android SDK Platform 34、Build-Tools 34
- 设置 `ANDROID_HOME`（本机示例：`/workspace/android-sdk` 或 `/opt/android-sdk`）

## 构建

```bash
cd app   # 即本 README 所在目录（仓库内 app/）
export ANDROID_HOME=/workspace/android-sdk   # 按本机路径调整
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

调试 APK 输出路径：

本地可复制到 `artifacts/app-debug.apk`（默认不提交到 Git；体积约见下方构建结果）。

```
app/app/build/outputs/apk/debug/app-debug.apk
```

安装到设备/模拟器：

```bash
adb install -r app/app/build/outputs/apk/debug/app-debug.apk
```

## 权限

- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`：用于居中地图与筛选附近河流。
- **不需要**联网即可查看捆绑区域地图与河网（定位服务本身可能依赖系统定位，与地图瓦片无关）。

## 数据说明

| 文件 | 说明 |
|------|------|
| `app/src/main/assets/style-dark.json` | 深色离线 MapLibre 样式 |
| `app/src/main/assets/rivers-chengdu.geojson` | 成都周边**示意**河网（合成/简化，非测绘成果，体积很小） |

更换区域时：替换 GeoJSON，并视需要调整 `MainActivity` 中的 `DEFAULT_CENTER`、`NEARBY_KM` 与样式中的陆地范围。

## 技术栈

- Kotlin + AndroidX Material 深色主题
- MapLibre Android OpenGL（`org.maplibre.gl:android-sdk-opengl`）
- Google Play Services Location（`FusedLocationProvider`；不可用时回退默认点）

## 局限

- 示意河网覆盖成都周边样本区，非全球离线包。
- 未捆绑 MBTiles；无在线矢量瓦片，缩放时无详细道路/注记。
- 无 GMS 的设备上定位组件可能不可用，但仍可用默认成都中心浏览。

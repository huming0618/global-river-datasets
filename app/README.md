# 四川离线河网地图（Android）

基于 **MapLibre Native** 的纯离线示例：深色 OpenMapTiles 底图（MBTiles）+ **HydroRIVERS** 亮青色河网 + **OSM** 水色补线，按 GPS 与设备朝向筛选**前方锥形**内的河段，并可点选高亮。

打开本目录（`app/`）即可用 Android Studio 导入工程。

## 功能

- **完全离线底图**：`assets/sichuan-basemap.mbtiles`（四川省约 bbox，z6–z12，OpenMapTiles / Planetiler），运行时复制到应用私有目录，经 `mbtiles://` 加载；样式无网络瓦片 URL。
- **HydroRIVERS 河网**：`assets/hydrorivers_sichuan.geojson.gz（构建时 AGP 解压为 .geojson）`（Asia 裁剪至四川，**ORD_STRA ≥ 2**，适度简化）。
- **OSM 水道补层**：`assets/osm_waterways_sichuan.geojson.gz（构建时 AGP 解压为 .geojson）`（river/canal + 有名称 stream），更细线、略偏青绿。
- **朝向感知列表（v0.3.1）**：使用 `Sensor.TYPE_ROTATION_VECTOR`（或回退 `TYPE_ORIENTATION` / 游戏旋转矢量）读取设备方位角；顶部显示朝向度数 + 东南西北。列表仅保留用户朝向前方锥形（默认 **±50°**）且半径约 **40 km** 内的河段，按距离由近到远；**按河名合并河段**（同名如「岷江」多段只显示一行，元数据可带「N段」；无名河段仍按 HYRIV_ID / osm_id 独立列出）。朝向变化约 ≥8° 或位置更新时节流重算（约 0.5–1 s），空间查询在后台线程执行。
- **点选高亮**：点击列表项后，该河名在当前锥形/附近集合内的**全部河段**以**琥珀色**（`#FFB300` + 橙光晕）叠加显示，与默认青色/青色附近高亮明显区分；可「清除选中」；选中时地图会平移/缩放到覆盖当前位置与最近河段点。
- **罗盘缺失回退**：无磁力计/旋转矢量时提示，并按距离列出附近河段（不按锥形过滤）。
- 默认中心：成都 **30.67°N, 104.06°E**。

## 权限说明

| 能力 | 权限 | 说明 |
|------|------|------|
| GPS 定位 | `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 仍需用户授权；拒绝则回退成都默认点 |
| 罗盘 / 朝向 | **无需额外运行时权限** | 使用系统传感器；室内磁场干扰可能导致漂移 |

## 地理范围

```
W=97.3, S=26.0, E=108.6, N=34.4  (EPSG:4326，四川近似外包矩形)
```

## 环境要求

- JDK 17+（推荐 21）
- Android SDK Platform 34、Build-Tools 34
- 设置 `ANDROID_HOME`（本机示例：`/workspace/android-sdk` 或 `/opt/android-sdk`）
- 构建前需有 `app/src/main/assets/sichuan-basemap.mbtiles`（约 74 MB，**不进 Git**；见下方获取方式）

## 构建

```bash
cd app
export ANDROID_HOME=/workspace/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 若缺少 mbtiles，从 Release 拉取：
../scripts/fetch_sichuan_basemap.sh v0.2.0-sichuan-debug

./gradlew assembleDebug
```

调试 APK：

```
app/app/build/outputs/apk/debug/app-debug.apk
```

可复制到 `artifacts/`（默认不提交）。

```bash
adb install -r app/app/build/outputs/apk/debug/app-debug.apk
```

## 重新生成数据 / 底图

在仓库根目录（需 GDAL、osmium、Java、磁盘空间）：

```bash
# 完整管线（HydroRIVERS + OSM + Planetiler 底图）
./scripts/build_sichuan_offline.sh

# 仅河网、跳过底图
SKIP_BASEMAP=1 ./scripts/build_sichuan_offline.sh
```

临时下载目录默认 `/workspace/river-data/sichuan/`（可用 `SCRATCH_DIR` 覆盖）。**不要**把 Asia 全量 zip / `*.mbtiles` 提交进 Git。

### 底图参数（Planetiler）

| 项 | 值 |
|----|-----|
| 输入 | Geofabrik `sichuan-latest.osm.pbf` |
| 输出 | `sichuan-basemap.mbtiles`（vector / OpenMapTiles） |
| zoom | 6–12 |
| 体积 | 约 74 MB |

深色样式见 `assets/style-dark.json`（无 glyph 注记层，避免离线字体包体积）。占位符 `__MBTILES_URI__` 由 `MainActivity` 替换为本地 `mbtiles://…` 路径。

## 数据与许可

| 数据 | 来源 | 许可 / 注意 |
|------|------|-------------|
| HydroRIVERS v1.0 Asia | [HydroSHEDS HydroRIVERS](https://www.hydrosheds.org/products/hydrorivers) | 遵循 HydroSHEDS 许可与署名要求；本应用仅捆绑四川裁剪子集 |
| OSM 水道 / 底图 | [Geofabrik Sichuan](https://download.geofabrik.de/asia/china/sichuan.html) → Planetiler | © OpenStreetMap contributors，[ODbL](https://www.openstreetmap.org/copyright)；底图另见 © OpenMapTiles |
| 过滤 | HydroRIVERS `ORD_STRA >= 2`（约去掉一半最细支流以控体积与绘制量） | 文档化于本 README |

引用 HydroRIVERS 时请参考：Lehner, B., Grill G. (2013). *Hydrological Processes*, 27(15): 2171–2186.

## 图层与样式

| 图层 | 颜色 | 说明 |
|------|------|------|
| 底图道路 / 水域 / 边界 | 深色灰蓝 | MBTiles vector |
| OSM waterways | `#4DD0E1` 细线 | 地名补全、人工渠等 |
| HydroRIVERS | `#26C6DA` | 主河网 |
| 前方/附近高亮 | `#00E5FF` + glow | 锥形+距离筛选结果（青色） |
| **选中河段** | `#FFB300` + `#FF6D00` glow | 列表点选，琥珀色，与青色区分 |

## 技术栈

- Kotlin + AndroidX Material 深色主题
- MapLibre Android OpenGL（`org.maplibre.gl:android-sdk-opengl` 11.x）
- Google Play Services Location（不可用时回退成都默认点）
- Android `SensorManager`（旋转矢量 / 方位角）

## 局限

- 底图无文字注记（未捆绑 glyphs）；河名依赖 OSM `name` 或 HydroRIVERS 高序占位名。
- HydroRIVERS 本身无官方中文河名；列表对无名河段显示 `未命名河段 · #HYRIV_ID`。
- 首次启动会将 ~74 MB MBTiles 复制到内部存储（仅一次）。
- 无 GMS 设备仍可离线浏览默认成都视图。
- **室内或金属环境**下磁力计易漂移，朝向与前方列表可能不稳定；到户外空旷处校准更准。
- 距离为河段顶点采样近似，非严格垂足距离；锥形以最近点方位相对朝向判定。

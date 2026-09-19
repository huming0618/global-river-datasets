# 全球公开河流 / 水文地理数据资料库

本仓库整理**全球公开可获取**的河流、流域、流量与水面相关数据源的目录与使用说明，方便快速判断该用哪套数据、去哪里下载。

> **重要声明**：这里只收录链接、许可要点与整理笔记，**不存放** HydroSHEDS / MERIT / GRDC / Copernicus 等原始栅格或矢量数据包。原始数据请从各产品官方门户按各自许可获取；尤其注意 GRDC 禁止再分发原始序列、MERIT 需注册、部分产品为非商业许可。

## 怎么选

1. **全球一致河网线** → [HydroRIVERS](docs/datasets/hydrorivers.md)；更密小河 / 汇流建模 → [MERIT-Basins](docs/datasets/merit-basins.md)；本地沟渠与地名 → [OSM waterways](docs/datasets/osm-waterways.md)
2. **嵌套流域面** → [HydroBASINS](docs/datasets/hydrobasins.md)；河段单元集水区 → MERIT-Basins；命名大流域报表 → [FAO AQUAMAPS](docs/datasets/fao-aquamaps-basins.md)
3. **环境属性挂接** → [HydroATLAS](docs/datasets/hydroatlas.md)
4. **流量** → 实测站 [GRDC](docs/datasets/grdc.md)；格点再分析 [GloFAS Historical](docs/datasets/glofas-historical.md)；MERIT 河段估计 [GRADES-hydroDL](docs/datasets/grades-hydrodl.md)
5. **水面变化 / 河宽** → [GSW](docs/datasets/gsw.md) + [GRWL](docs/datasets/grwl.md) / [SWORD](docs/datasets/sword.md)

更完整的导览见 [docs/guide.md](docs/guide.md)。

## 目录结构

```
docs/guide.md           # 完整实用导览
docs/datasets/          # 各数据集短页
data/datasets.json      # 结构化目录
scripts/validate_catalog.py
```

## 按类型浏览

| 类型 | 数据集 |
|------|--------|
| 河网 | [HydroRIVERS](docs/datasets/hydrorivers.md) · [MERIT-Basins](docs/datasets/merit-basins.md) · [OSM](docs/datasets/osm-waterways.md) · [SWORD](docs/datasets/sword.md) · [HydroSHEDS v2](docs/datasets/hydrosheds-v2.md) |
| 流域 | [HydroBASINS](docs/datasets/hydrobasins.md) · [FAO AQUAMAPS](docs/datasets/fao-aquamaps-basins.md) |
| 湖泊 / 属性 | [HydroLAKES](docs/datasets/hydrolakes.md) · [HydroATLAS](docs/datasets/hydroatlas.md) |
| 栅格水文 | [MERIT Hydro](docs/datasets/merit-hydro.md) |
| 实测流量 | [GRDC](docs/datasets/grdc.md) |
| 模拟流量 | [GloFAS](docs/datasets/glofas-historical.md) · [GRADES-hydroDL](docs/datasets/grades-hydrodl.md) |
| 水面 / 河宽 | [GSW](docs/datasets/gsw.md) · [GRWL](docs/datasets/grwl.md) |
| 区域（非全球） | [EU-Hydro](docs/datasets/eu-hydro.md) · [USGS NHD](docs/datasets/usgs-nhd.md) |

## 校验目录

```bash
python3 scripts/validate_catalog.py
```

## 扩展一条数据集

1. 在 `data/datasets.json` 的 `datasets` 数组中追加条目（需含 `id`, `name`, `category`, `provider`, `coverage`, `url`, `license_note`, `formats`, `summary_zh`）。
2. 在 `docs/datasets/<id>.md` 增加对应短页。
3. 运行校验脚本确认通过。
4. 如需，更新 `docs/guide.md` 与本 README 的分类表。

## Android 离线河网示例应用

仓库内 [`app/`](app/) 提供基于 MapLibre 的 **四川离线 Android 示例**：

- 真实 **HydroRIVERS**（四川裁剪，`ORD_STRA ≥ 2`）+ **OSM** 水道补层
- 深色 **离线 MBTiles** 底图（OpenMapTiles / Planetiler，z6–z12，约 74 MB，不进 Git）
- 说明、许可与再生管线见 [app/README.md](app/README.md)；脚本：`scripts/build_sichuan_offline.sh`、`scripts/fetch_sichuan_basemap.sh`
- 构建：`cd app && ./gradlew assembleDebug`（需先准备 `assets/sichuan-basemap.mbtiles`）

## 许可

本仓库文档与目录元数据采用 [CC BY 4.0](LICENSE)。第三方原始数据的权利仍归各提供方，本仓库不二次分发。

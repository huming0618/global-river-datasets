# 全球公开河流 / 水文地理数据实用导览（简体中文）

**整理目的**：帮助中文用户快速判断「河网 / 流域 / 实测流量 / 模拟流量 / 水面范围」该选哪套数据。信息已对照官方页面核对（约 2024–2026）；个别产品仍在滚动更新，下载前请再看官网说明。

---

## 怎么选（3–5 条）

1. **只要全球一致的河网线制图 / GIS 拓扑** → 先下 **HydroRIVERS**；要更密的小河与建模河段 → **MERIT-Basins**；要本地沟渠/地名 → 再叠 **OSM**。
2. **要嵌套流域面（大流域→子流域）** → **HydroBASINS**；要每个河段对应的小单元集水区 → **MERIT-Basins** 的 `cat_*`；只要命名大流域报表 → **FAO AQUAMAPS basins**。
3. **要环境属性（气候、土地利用等）挂在河段/流域上** → **HydroATLAS**（RiverATLAS / BasinATLAS）。
4. **要流量时间序列** → 有站用 **GRDC**（实测，登记+禁再分发）；无站或全球格点气候态用 **GloFAS Historical**；要挂在 MERIT 河段上的日流量估计用 **GRADES-hydroDL**（模型，非实测）。
5. **要水面范围变化 / 河宽，而不是河网拓扑** → **GSW** + **GRWL/SWORD**；中国精细业务还需结合国内公开数据与实地/高分影像验证。

---

## A. 全球河网（矢量线）

### 1. HydroRIVERS（HydroSHEDS 家族）
- **提供方**：HydroRIVERS v1 · WWF / HydroSHEDS
- **内容**：全球河流矢量线网；含河段长度、河序、到源头/河口距离、长期平均流量估计；与 HydroBASINS 通过共享 ID 关联
- **覆盖**：全球；基于 HydroSHEDS 15 角秒（约 500 m）；约 850 万河段、总长约 3590 万 km；入选阈值：集水面积 ≥10 km² 或 平均流量 ≥0.1 m³/s
- **许可**：科研、教育、商用均可；遵循 HydroSHEDS 许可协议
- **下载**：https://www.hydrosheds.org/products/hydrorivers
- **格式**：Esri File Geodatabase、Shapefile（全球或按大洲）
- **适用**：全球一致河网、制图、拓扑分析的默认起点
- **中国/亚洲**：有 Asia / Siberia 分区；山区、平原、三角洲等复杂区精度可能偏低，建议与 MERIT / OSM 交叉验证

### 2. MERIT-Basins
- **提供方**：ReachHydro（基于东京大学 MERIT Hydro）
- **内容**：河段线 + 单元集水区面；含拓扑、面积、坡度、Strahler 河序等；约 294 万河段
- **覆盖**：近全球（约 60°S–90°N）；源于 MERIT Hydro 约 90 m；河道化阈值约 25 km²
- **许可**：CC BY-NC 4.0 或 ODbL 1.0；中国用户可走 TPDC 镜像
- **下载**：https://www.reachhydro.org/home/params/merit-basins
- **格式**：Shapefile（按 Pfafstetter L1/L2 分区）
- **注意**：基于 MERIT-Hydro v0.0 的版本已弃用；优先用 v0.7/v1.0 或 bugfix1

### 3. OpenStreetMap（OSM）waterways
- **提供方**：OpenStreetMap 社区；常用提取：Geofabrik
- **内容**：waterway=* 等矢量；无统一全球拓扑/流量
- **许可**：ODbL 1.0
- **下载**：https://download.geofabrik.de/
- **适用**：补充人工河道、本地地名；不适合单独做全球一致水文拓扑

### 4. SWORD（SWOT River Database）
- **提供方**：SWOT 科学团队 / UNC 等（Version 15）
- **内容**：面向 SWOT 的河段/节点框架（节点约 200 m，河段约 10 km）
- **下载**：http://gaia.geosci.unc.edu/SWORD/
- **格式**：NetCDF、GeoPackage、Shapefile
- **适用**：SWOT 水位/水面宽/流量研究、宽河中心线

### 5. HydroSHEDS v2 河网（RIV）— 区域性新品，尚未全球
- **覆盖（当前）**：主要为美洲；亚洲等后续发布
- **许可**：CC BY 4.0
- **下载**：https://www.hydrosheds.org/products/hydrosheds-v2
- **注意**：不是 HydroRIVERS v1 的全球替代品

---

## B. 流域 / 集水区

### 6. HydroBASINS
- **提供方**：HydroSHEDS / WWF
- **内容**：全球嵌套子流域面；Pfafstetter 1–12 级
- **下载**：https://www.hydrosheds.org/products/hydrobasins
- **格式**：Shapefile（按大洲）

### 7. FAO AQUAMAPS — Hydrological basins of the world
- **提供方**：FAO AQUASTAT / AQUAMAPS
- **内容**：世界大流域及子流域面（偏命名与汇总）
- **下载**：https://data.apps.fao.org/catalog/dataset/f2615a41-6383-4aa4-aa21-743330eb03ae
- **入口**：https://www.fao.org/aquastat/en/geospatial-information/aquamaps/

---

## C. 湖泊 / 水体 & 属性库

### 8. HydroLAKES
- **内容**：全球湖泊/水库岸线（面积 ≥10 ha）；约 140 万个
- **许可**：CC BY 4.0
- **下载**：https://www.hydrosheds.org/products/hydrolakes

### 9. HydroATLAS
- **内容**：在 HydroBASINS / HydroRIVERS / HydroLAKES 同一几何上挂接气候、地形、土地利用等约 56 变量 / 281 属性
- **许可**：CC BY 4.0
- **下载**：https://www.hydrosheds.org/hydroatlas

---

## D. 栅格水文地理

### 10. MERIT Hydro
- **提供方**：东京大学 Yamazaki 实验室等
- **内容**：流向、水文校正高程、上游面积、河宽、HAND 等
- **覆盖**：90°N–60°S；3 角秒（~90 m）；v1.0.1
- **许可**：CC BY-NC 4.0 或 ODbL 1.0；需 Google Form 注册
- **下载**：https://global-hydrodynamics.github.io/MERIT_Hydro/

---

## E. 实测流量

### 11. GRDC（Global Runoff Data Centre）
- **提供方**：德国联邦水文局 BfG，代表 WMO
- **内容**：全球站网日流量时间序列
- **许可**：免费但需身份登记；非商用；禁止再分发原始数据
- **门户**：https://grdc.bafg.de/
- **中国注意**：不能假设全国密网都在 GRDC

---

## F. 模拟 / 再分析流量

### 12. GloFAS Historical（CEMS / Copernicus）
- **内容**：LISFLOOD + ERA5 驱动的格点日流量等
- **覆盖**：全球（除南极）；0.05°；约 1979 至今（v5）
- **获取**：需 Copernicus 账户；https://ewds.climate.copernicus.eu/datasets/cems-glofas-historical
- **格式**：GRIB2、NetCDF-4

### 13. GRADES-hydroDL
- **提供方**：ReachHydro / UCSD 等
- **内容**：MERIT-Basins 约 294 万河段上的日流量（约 1980–近实时）
- **许可**：多为 CC BY-NC-SA 4.0（以站点为准）
- **下载**：https://www.reachhydro.org/home/records/grades-hydrodl

---

## G. 卫星衍生水面 / 河宽

### 14. JRC Global Surface Water（GSW）
- **内容**：Landsat 衍生全球地表水 Occurrence、Seasonality 等
- **覆盖**：全球；约 30 m；1984–2024（v1.5）
- **下载**：https://global-surface-water.appspot.com/download

### 15. GRWL
- **内容**：光学影像提取的全球河宽、中心线（较宽河道）
- **下载**：https://doi.org/10.5281/zenodo.1297434

---

## H. 区域产品（非全球）

### 16. EU-Hydro River Network Database
- **范围**：仅欧洲
- **入口**：https://land.copernicus.eu/en/products/eu-hydro/eu-hydro-river-network-database

### 17. USGS NHD / NHDPlus
- **范围**：仅美国
- **入口**：https://www.usgs.gov/national-hydrography/national-hydrography-dataset

---

## 主要官方链接清单

| 产品 | URL |
|---|---|
| HydroSHEDS 产品总览 | https://www.hydrosheds.org/products |
| HydroRIVERS | https://www.hydrosheds.org/products/hydrorivers |
| HydroBASINS | https://www.hydrosheds.org/products/hydrobasins |
| HydroLAKES | https://www.hydrosheds.org/products/hydrolakes |
| HydroATLAS | https://www.hydrosheds.org/hydroatlas |
| HydroSHEDS v2 | https://www.hydrosheds.org/products/hydrosheds-v2 |
| MERIT Hydro | https://global-hydrodynamics.github.io/MERIT_Hydro/ |
| MERIT-Basins | https://www.reachhydro.org/home/params/merit-basins |
| GRADES-hydroDL | https://www.reachhydro.org/home/records/grades-hydrodl |
| GRDC | https://grdc.bafg.de/ |
| GRDC Data Portal | https://grdc.bafg.de/data/data_portal/index.html |
| GloFAS Historical (EWDS) | https://ewds.climate.copernicus.eu/datasets/cems-glofas-historical |
| Global Surface Water | https://global-surface-water.appspot.com/download |
| SWORD | http://gaia.geosci.unc.edu/SWORD/ |
| GRWL (Zenodo) | https://doi.org/10.5281/zenodo.1297434 |
| Geofabrik OSM | https://download.geofabrik.de/ |
| FAO AQUAMAPS | https://www.fao.org/aquastat/en/geospatial-information/aquamaps/ |
| FAO 世界大流域目录 | https://data.apps.fao.org/catalog/dataset/f2615a41-6383-4aa4-aa21-743330eb03ae |
| EU-Hydro | https://land.copernicus.eu/en/products/eu-hydro/eu-hydro-river-network-database |

---

## 重要注意事项

1. **登记类**：MERIT Hydro（表单+密码）、GRDC（身份与用途）、GloFAS/Copernicus（账户）、GRADES 大文件（Globus/Drive）。
2. **商用敏感**：GRDC 禁止商用与再分发原始序列；MERIT 系商用多走 ODbL；GRADES-hydroDL 多为非商业共享类。
3. **勿混用「全球」标签**：EU-Hydro、USGS NHD 为区域产品；HydroSHEDS v2 当前非全球。
4. **版本**：全球分析仍以 HydroSHEDS v1 衍生矢量为主流；MERIT 系是更高分辨率并行体系。
5. **中国/亚洲**：青藏高原、干旱内流区、三角洲分汊、冰川区误差更常见；国内法定水系需另查国内公开平台。

---

**研究说明**：以上条目依据各产品官方站点整理；未在官网写明的数字未编造。使用前请再打开链接核对最新版本与许可。

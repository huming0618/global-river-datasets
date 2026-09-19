# 数据集索引

按 `data/datasets.json` 生成。完整导览见 [../guide.md](../guide.md)。


## 河网（矢量线）

- [HydroRIVERS](hydrorivers.md) — 全球一致河网矢量，默认制图与拓扑起点
- [MERIT-Basins](merit-basins.md) — 高分辨率河段+单元流域，适合汇流建模
- [OpenStreetMap waterways](osm-waterways.md) — 众包水道，补人工河道与地名
- [SWORD](sword.md) — 面向 SWOT 的宽河河段/节点框架
- [HydroSHEDS v2 RIV](hydrosheds-v2.md) — 约30m新品；当前非全球

## 流域 / 集水区

- [HydroBASINS](hydrobasins.md) — 全球嵌套子流域 Pfafstetter 1–12 级
- [FAO AQUAMAPS hydrological basins](fao-aquamaps-basins.md) — 命名大流域汇总，对接 FAO 统计

## 湖泊 / 水体

- [HydroLAKES](hydrolakes.md) — 全球湖库岸线约140万个

## 属性库

- [HydroATLAS](hydroatlas.md) — 河网/流域几何上挂环境属性

## 栅格水文地理

- [MERIT Hydro](merit-hydro.md) — 约90m流向/累积等栅格

## 实测流量

- [GRDC](grdc.md) — 全球实测站流量金标准之一

## 模拟 / 再分析流量

- [GloFAS Historical](glofas-historical.md) — 全球格点日流量再分析
- [GRADES-hydroDL](grades-hydrodl.md) — MERIT河段日流量模型估计

## 卫星衍生水面 / 河宽

- [JRC Global Surface Water](gsw.md) — Landsat衍生全球水面Occurrence等
- [GRWL](grwl.md) — 光学影像全球河宽与中心线

## 区域产品（非全球）

- [EU-Hydro](eu-hydro.md) — 仅欧洲河网，非全球
- [USGS NHD / NHDPlus](usgs-nhd.md) — 仅美国，勿与HydroBASINS混淆

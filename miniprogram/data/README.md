# 小程序河网小样

## 成都周边
- 文件：`chengdu-rivers.geojson`
- 范围：成都周边约 `103.7–104.4E, 30.4–30.95N`（WGS84）
- 字段：`id`, `name`, `source`（hydrorivers|osm）, `waterway?`, `ord_stra?`, `length_km?`

## 宝成线廊道（广元→宝鸡）
- 清单：`baoji-chengdu-corridor-rivers.json`（按里程排序的 OSM 具名河 + Hydro 统计）
- 几何：`baoji-corridor-rivers.geojson`（OSM 具名线 + 高阶 HydroRIVERS 骨干）
- 方法：简化中心线约 12km 缓冲 ∩ HydroRIVERS `ORD_STRA≥3` + OSM 具名 waterway
- 注意：中心线非精确铁路线形；走廊相交 ≠ 桥梁跨越次数

用法：直接替换小程序里的占位 GeoJSON / 离线包数据。

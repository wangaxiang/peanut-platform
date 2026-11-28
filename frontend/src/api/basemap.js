const token = import.meta.env.VITE_TIANDITU_TOKEN?.trim()
export const basemapName = token ? '天地图影像' : '卫星影像'
export const imagery = {
  type: 'raster',
  tiles: token
    ? ['https://t0.tianditu.gov.cn/img_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=' + encodeURIComponent(token)]
    : ['https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],
  tileSize: 256,
  maxzoom: token ? 18 : 19,
  attribution: token ? '© 天地图' : 'Esri, Maxar, Earthstar Geographics, and the GIS User Community',
}

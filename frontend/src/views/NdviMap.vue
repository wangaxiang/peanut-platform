<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import maplibregl from 'maplibre-gl'
import { imagery } from '../api/basemap'
import { formatTime, getFlight, requestMessage } from '../api/patrol'

const props = defineProps({ flightId: { type: String, required: true } })
const grades = [
  { key: 'red_pct', label: '胁迫', range: '< 0.3', color: '#dc3a35' },
  { key: 'yellow_pct', label: '中等', range: '0.3–0.6', color: '#f8c630' },
  { key: 'green_pct', label: '良好', range: '≥ 0.6', color: '#309e58' },
]
const flight = ref(null)
const element = ref(null)
const error = ref('')
const opacity = ref(90)
let map
let disposed = false

async function load() {
  try {
    const record = await getFlight(props.flightId)
    if (disposed) return
    if (!record.ndviTileUrl || !record.ndviStats) {
      error.value = '该飞行的 NDVI 影像尚未生成。'
      return
    }
    const response = await fetch(record.ndviTileUrl)
    if (!response.ok) throw new Error('TileJSON unavailable')
    const tilejson = await response.json()
    flight.value = record
    await nextTick()
    if (disposed) return
    const [west, south, east, north] = tilejson.bounds
    map = new maplibregl.Map({
      container: element.value,
      bounds: [[west, south], [east, north]],
      fitBoundsOptions: { padding: 34, maxZoom: 21 },
      attributionControl: { compact: true },
      style: {
        version: 8,
        sources: {
          imagery,
          ndvi: { type: 'raster', tileSize: 256, url: new URL(record.ndviTileUrl, location.origin).href },
        },
        layers: [
          { id: 'background', type: 'background', paint: { 'background-color': '#425747' } },
          { id: 'imagery', type: 'raster', source: 'imagery' },
          { id: 'ndvi', type: 'raster', source: 'ndvi', paint: {
            'raster-opacity': opacity.value / 100, 'raster-resampling': 'nearest', 'raster-fade-duration': 0,
          } },
        ],
      },
    })
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }))
    map.on('error', event => {
      if (event.sourceId === 'ndvi') error.value = 'NDVI 切片加载失败，请刷新页面。'
    })
  } catch (cause) {
    if (!disposed) error.value = requestMessage(cause, 'NDVI 地图加载失败')
  }
}

function adjustOpacity(event) {
  opacity.value = Number(event.target.value)
  if (map?.getLayer('ndvi')) map.setPaintProperty('ndvi', 'raster-opacity', opacity.value / 100)
}

onMounted(load)
onBeforeUnmount(() => { disposed = true; map?.remove() })
</script>

<template>
  <main class="monitor-page">
    <header class="monitor-header">
      <div><h1>NDVI 监测地图</h1>
        <p v-if="flight">{{ flight.fieldName }} · {{ flight.taskName }} · 飞行 #{{ flight.id }}</p></div>
      <a href="#/patrol/flights">返回飞行记录</a>
    </header>
    <p v-if="error" class="monitor-error" role="alert">{{ error }}</p>
    <div v-if="flight" class="ndvi-layout">
      <div ref="element" class="ndvi-map" aria-label="NDVI 地图叠加层"></div>
      <aside class="monitor-panel ndvi-stats">
        <h2>植被指数分布</h2>
        <dl class="ndvi-numbers">
          <div><dt>均值</dt><dd>{{ flight.ndviStats.mean.toFixed(3) }}</dd></div>
          <div><dt>标准差</dt><dd>{{ flight.ndviStats.std.toFixed(3) }}</dd></div>
        </dl>
        <div v-for="grade in grades" :key="grade.key" class="ndvi-grade">
          <div><span>{{ grade.label }} <small>{{ grade.range }}</small></span>
            <strong>{{ flight.ndviStats[grade.key].toFixed(1) }}%</strong></div>
          <progress :aria-label="grade.label" :style="{ '--grade-color': grade.color }"
            :value="flight.ndviStats[grade.key]" max="100" />
        </div>
        <label class="ndvi-opacity">图层不透明度 <span>{{ opacity }}%</span>
          <input type="range" min="0" max="100" :value="opacity" @input="adjustOpacity">
        </label>
        <p class="monitor-muted">飞行结束于 {{ formatTime(flight.endTime) }}</p>
      </aside>
    </div>
    <p v-else-if="!error" class="monitor-muted" role="status">正在加载地图…</p>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import maplibregl from 'maplibre-gl'
import MapboxDraw from '@mapbox/mapbox-gl-draw'
import { createEvent, createField, getEvents, getField, updateField } from '../api/fields'
import { useFields } from '../stores/fields'
import FarmEventForm from '../components/FarmEventForm.vue'
import FieldDetail from '../components/FieldDetail.vue'
import FieldForm from '../components/FieldForm.vue'
import FieldList from '../components/FieldList.vue'

const fields = useFields()
const mapElement = ref(null)
const mapReady = ref(false)
const mapFailure = ref('')
const drawing = ref(false)
const keyword = ref('')
const page = ref(0)
const selected = ref(null)
const events = ref([])
const eventTotal = ref(0)
const eventPage = ref(0)
const eventsLoading = ref(false)
const eventLoadError = ref('')
const fieldForm = ref(false)
const editing = ref(null)
const fieldSaving = ref(false)
const fieldError = ref('')
const redrawingBoundary = ref(false)
const eventForm = ref(false)
const eventSaving = ref(false)
const eventError = ref('')

const token = import.meta.env.VITE_TIANDITU_TOKEN?.trim()
const basemapName = token ? '天地图影像' : '卫星影像'
const tiles = token
  ? ['https://t0.tianditu.gov.cn/img_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=' + encodeURIComponent(token)]
  : ['https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}']
const formattedArea = computed(() => fields.stats
  ? Number(fields.stats.totalAreaMu).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  : '—')
const listError = ref('')
let map
let draw
let draft
let eventRequest = 0
let keywordTimer

const fieldCollection = () => ({
  type: 'FeatureCollection',
  features: fields.mapRows.map(field => ({
    id: field.id,
    type: 'Feature',
    geometry: field.geometry,
    properties: { id: field.id },
  })),
})
const selectedFilter = () => ['==', ['get', 'id'], selected.value?.id ?? -1]

onMounted(() => {
  refreshFields()
  try {
    Object.assign(MapboxDraw.constants.classes, {
      CANVAS: 'maplibregl-canvas',
      CONTROL_BASE: 'maplibregl-ctrl',
      CONTROL_PREFIX: 'maplibregl-ctrl-',
      CONTROL_GROUP: 'maplibregl-ctrl-group',
      ATTRIBUTION: 'maplibregl-ctrl-attrib',
    })
    map = new maplibregl.Map({
      container: mapElement.value,
      center: [115.6107, 36.1367],
      zoom: 14.8,
      attributionControl: { compact: true },
      style: {
        version: 8,
        sources: {
          imagery: {
            type: 'raster', tiles, tileSize: 256, maxzoom: token ? 18 : 19,
            attribution: token ? '© 天地图' : 'Esri, Maxar, Earthstar Geographics, and the GIS User Community',
          },
        },
        layers: [
          { id: 'map-background', type: 'background', paint: { 'background-color': '#425747' } },
          { id: 'imagery', type: 'raster', source: 'imagery',
            paint: { 'raster-saturation': -0.16, 'raster-brightness-max': 0.9 } },
        ],
      },
    })
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right')
    draw = new MapboxDraw({ displayControlsDefault: false })
    map.addControl(draw, 'top-right')
    map.on('load', () => {
      map.addSource('fields', { type: 'geojson', data: fieldCollection() })
      map.addLayer({ id: 'field-fill', type: 'fill', source: 'fields',
        paint: { 'fill-color': '#8ae9a2', 'fill-opacity': 0.28 } })
      map.addLayer({ id: 'field-line', type: 'line', source: 'fields',
        paint: { 'line-color': '#9bffad', 'line-width': 2 } })
      map.addLayer({ id: 'field-selected', type: 'fill', source: 'fields', filter: selectedFilter(),
        paint: { 'fill-color': '#ffe77d', 'fill-opacity': 0.39 } })
      map.addLayer({ id: 'field-selected-line', type: 'line', source: 'fields', filter: selectedFilter(),
        paint: { 'line-color': '#ffe77d', 'line-width': 3 } })
      map.on('mouseenter', 'field-fill', () => { if (!drawing.value) map.getCanvas().style.cursor = 'pointer' })
      map.on('mouseleave', 'field-fill', () => { if (!drawing.value) map.getCanvas().style.cursor = '' })
      map.on('click', 'field-fill', event => {
        if (drawing.value || fieldForm.value) return
        const row = fields.mapRows.find(field => field.id === event.features[0].properties.id)
        if (row) selectField(row, false)
      })
      mapReady.value = true
    })
    map.on('draw.create', event => {
      draft = event.features[0]
      drawing.value = false
      editing.value = null
      fieldError.value = ''
      fieldForm.value = true
    })
    map.on('draw.modechange', event => {
      if (event.mode !== 'draw_polygon') {
        drawing.value = false
        if (!draft) redrawingBoundary.value = false
      }
    })
  } catch {
    mapFailure.value = '地图无法初始化，请检查浏览器是否支持 WebGL。'
  }
})

watch(() => fields.mapRows, () => map?.getSource('fields')?.setData(fieldCollection()))
watch(() => selected.value?.id, () => {
  if (!mapReady.value) return
  for (const layer of ['field-selected', 'field-selected-line']) map.setFilter(layer, selectedFilter())
})
watch(keyword, () => {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => {
    page.value = 0
    loadList()
  }, 230)
})
onBeforeUnmount(() => {
  clearTimeout(keywordTimer)
  map?.remove()
})

function message(error, fallback) {
  return error.response?.data?.msg || fallback
}

async function refreshFields() {
  try {
    await Promise.all([loadList(), fields.loadOverview()])
  } catch (error) {
    listError.value = message(error, '无法获取地块统计，请重试。')
  }
}

async function loadList() {
  listError.value = ''
  try {
    await fields.loadList(keyword.value, page.value)
  } catch (error) {
    listError.value = message(error, '无法读取地块，请检查后端连接。')
  }
}

function changePage(value) {
  page.value = value
  loadList()
}

function startDrawing() {
  if (!mapReady.value || fieldForm.value) return
  closeDetails()
  draw.changeMode('draw_polygon')
  drawing.value = true
  map.getCanvas().focus()
}

function finishDrawing() {
  const ring = draw.getAll().features[0]?.geometry.coordinates[0]
  if (!ring || ring.length < 5) {
    ElMessage.warning('请至少标记三个边界点。')
    return
  }
  draw.changeMode('simple_select')
  drawing.value = false
}

function cancelDrawing() {
  draw.trash()
  drawing.value = false
  redrawingBoundary.value = false
}

function locate(field) {
  if (!mapReady.value) return
  const ring = field.geometry.coordinates[0]
  const bounds = ring.reduce((box, point) => box.extend(point), new maplibregl.LngLatBounds(ring[0], ring[0]))
  const camera = map.cameraForBounds(bounds, {
    padding: { top: 90, left: 45, bottom: 80, right: mapElement.value.clientWidth > 740 ? 375 : 45 },
    maxZoom: 17,
  })
  if (camera) map.flyTo({ ...camera, duration: 750 })
}

async function selectField(field, center = true) {
  if (drawing.value) cancelDrawing()
  selected.value = field
  if (center) locate(field)
  events.value = []
  eventTotal.value = 0
  loadEventPage(0)
  try {
    const detail = await getField(field.id)
    if (selected.value?.id === field.id) selected.value = detail
  } catch (error) {
    if (selected.value?.id === field.id) ElMessage.error(message(error, '无法读取地块详情。'))
  }
}

function closeDetails() {
  selected.value = null
  eventRequest++
}

async function loadEventPage(value) {
  const id = selected.value?.id
  if (!id) return
  const request = ++eventRequest
  eventPage.value = value
  eventsLoading.value = true
  eventLoadError.value = ''
  try {
    const result = await getEvents(id, value)
    if (request !== eventRequest) return
    events.value = result.content
    eventTotal.value = result.totalElements
  } catch (error) {
    if (request === eventRequest) eventLoadError.value = message(error, '无法读取农事记录。')
  } finally {
    if (request === eventRequest) eventsLoading.value = false
  }
}

function editField() {
  if (!selected.value) return
  editing.value = selected.value
  fieldError.value = ''
  fieldForm.value = true
}

function discardDraft() {
  if (!draft) return
  draw.delete(draft.id)
  draft = null
}

function setFieldForm(open) {
  if (fieldForm.value === open) return
  fieldForm.value = open
  if (!open) {
    discardDraft()
    redrawingBoundary.value = false
  }
}

function redrawBoundary() {
  fieldForm.value = false
  discardDraft()
  redrawingBoundary.value = true
  startDrawing()
}

async function saveField(values) {
  fieldSaving.value = true
  fieldError.value = ''
  try {
    const previous = editing.value
    const body = {
      ...values,
      geometry: JSON.stringify(previous?.geometry ?? draft.geometry),
      ownerName: previous?.ownerName ?? null,
      remark: previous?.remark ?? null,
    }
    const saved = previous ? await updateField(previous.id, body) : await createField(body)
    fields.mapRows = [saved, ...fields.mapRows.filter(field => field.id !== saved.id)]
    setFieldForm(false)
    if (!previous) {
      keyword.value = ''
      page.value = 0
    }
    await Promise.all([selectField(saved, !previous), refreshFields()])
    ElMessage.success(previous ? '地块档案已更新' : '地块已登记')
  } catch (error) {
    fieldError.value = message(error, '保存地块失败，请重试。')
  } finally {
    fieldSaving.value = false
  }
}

function openEventForm() {
  eventError.value = ''
  eventForm.value = true
}

async function saveEvent(values) {
  const id = selected.value?.id
  if (!id) return
  eventSaving.value = true
  eventError.value = ''
  try {
    const record = await createEvent(id, values)
    eventForm.value = false
    if (selected.value?.id === id) {
      await loadEventPage(0)
      if (events.value.every(event => event.id !== record.id)) {
        ElMessage.success('农事记录已保存，可按日期翻页查看')
        return
      }
    }
    ElMessage.success('农事记录已保存')
  } catch (error) {
    eventError.value = message(error, '保存农事记录失败，请重试。')
  } finally {
    eventSaving.value = false
  }
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true"><i></i><b></b></span>
        <div><strong>花生种植精准服务平台</strong><span>农田管理</span></div>
      </div>
      <div class="overview" aria-label="农田统计">
        <div><span>地块总数</span><strong>{{ fields.stats?.totalCount ?? '—' }} <small>块</small></strong></div>
        <div><span>种植面积</span><strong>{{ formattedArea }} <small>亩</small></strong></div>
      </div>
      <button class="draw-button" type="button" :disabled="!mapReady || drawing || fieldForm" @click="startDrawing">
        <span aria-hidden="true">＋</span> 绘制地块
      </button>
    </header>

    <main class="workspace">
      <FieldList :fields="fields.rows" :selected-id="selected?.id" :loading="fields.loading"
        :total="fields.total" :page="page" v-model:keyword="keyword"
        @update:page="changePage" @select="selectField" />

      <section class="map-workspace" aria-label="地块地图">
        <div ref="mapElement" class="map-canvas"></div>
        <div class="map-caption"><span class="map-dot"></span>{{ basemapName }}</div>
        <div v-if="listError" class="map-notice map-error" role="alert">
          {{ listError }} <button type="button" @click="refreshFields">重试</button>
        </div>
        <div v-if="mapFailure" class="map-notice map-error" role="alert">{{ mapFailure }}</div>
        <div v-if="drawing" class="map-notice drawing-notice" role="status">
          <span>沿地块边缘依次点击拐点，避免边界交叉。</span>
          <button type="button" @click="finishDrawing">完成绘制</button>
          <button type="button" @click="cancelDrawing">取消绘制</button>
        </div>
        <FieldDetail :field="selected" :events="events" :total="eventTotal" :page="eventPage"
          :loading="eventsLoading" :error="eventLoadError" @close="closeDetails" @edit="editField"
          @add-event="openEventForm" @change-page="loadEventPage" />
        <div class="map-key" aria-label="地图图例">
          <span><i></i> 已登记地块</span><span><i></i> 已选中</span>
        </div>
      </section>
    </main>
    <FieldForm :model-value="fieldForm" :field="editing" :saving="fieldSaving" :error="fieldError"
      :preserve-values="redrawingBoundary" @update:model-value="setFieldForm" @submit="saveField" @redraw="redrawBoundary" />
    <FarmEventForm v-model="eventForm" :saving="eventSaving" :error="eventError" @submit="saveEvent" />
  </div>
</template>

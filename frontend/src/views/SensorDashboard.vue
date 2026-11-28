<script setup>
import { computed, ref, watch } from 'vue'
import PatrolFieldSelect from '../components/PatrolFieldSelect.vue'
import SoilChart from '../components/SoilChart.vue'
import { formatTime, getSensorHistory, getSensors, requestMessage } from '../api/patrol'
const props = defineProps({ initialSerial: String })

const climate = [
  { key: 'soil_temp', label: '土壤温度', unit: '°C', color: '#bd5c32' },
  { key: 'soil_moisture', label: '土壤湿度', unit: '%', color: '#247ca3', axis: 'right' },
]
const nutrients = [
  { key: 'soil_n', label: '氮（N）', unit: 'mg/kg', color: '#28734e' },
  { key: 'soil_p', label: '磷（P）', unit: 'mg/kg', color: '#994bb4' },
  { key: 'soil_k', label: '钾（K）', unit: 'mg/kg', color: '#a76a19' },
]
const metrics = [...climate, ...nutrients]
const ranges = {
  '6h': { hours: 6, interval: '1m', label: '最近 6 小时' },
  '24h': { hours: 24, interval: '5m', label: '最近 24 小时' },
  '7d': { hours: 168, interval: '1h', label: '最近 7 天' },
}
const fieldId = ref()
const serial = ref()
const range = ref('24h')
const devices = ref([])
const readings = ref([])
const latestError = ref('')
const historyError = ref('')
const loadingDevices = ref(false)
const loadingHistory = ref(false)
const latest = computed(() => devices.value.find(device => device.deviceSn === serial.value))
let deviceRequest = 0
let historyRequest = 0

async function refreshDevices() {
  const current = ++deviceRequest
  loadingDevices.value = true
  latestError.value = ''
  const previous = serial.value ?? props.initialSerial
  try {
    const result = await getSensors(fieldId.value)
    if (current !== deviceRequest) return
    devices.value = result
    serial.value = devices.value.some(device => device.deviceSn === previous) ? previous : devices.value[0]?.deviceSn
    if (serial.value === previous) await refreshHistory()
  } catch (cause) {
    if (current !== deviceRequest) return
    latestError.value = requestMessage(cause, '传感器列表加载失败')
  } finally {
    if (current === deviceRequest) loadingDevices.value = false
  }
}

async function refreshHistory() {
  const current = ++historyRequest
  readings.value = []
  historyError.value = ''
  if (!serial.value) {
    loadingHistory.value = false
    return
  }
  loadingHistory.value = true
  const selected = ranges[range.value]
  const end = new Date()
  const start = new Date(end.getTime() - selected.hours * 3600000)
  try {
    const result = await getSensorHistory(serial.value, { start: start.toISOString(), end: end.toISOString(), interval: selected.interval })
    if (current === historyRequest) readings.value = result
  } catch (cause) {
    if (current === historyRequest) historyError.value = requestMessage(cause, '传感器历史加载失败')
  } finally {
    if (current === historyRequest) loadingHistory.value = false
  }
}

watch([serial, range], refreshHistory)
watch(fieldId, () => { devices.value = []; serial.value = undefined; refreshDevices() }, { immediate: true })
</script>

<template>
  <main class="monitor-page">
    <header class="monitor-header">
      <div><h1>土壤监测</h1><p>查看地块传感器的最新读数和历史趋势。</p></div>
      <el-button :loading="loadingDevices || loadingHistory" @click="refreshDevices">刷新数据</el-button>
    </header>
    <section class="monitor-panel sensor-summary">
      <div class="monitor-toolbar sensor-filters">
        <h2>传感器读数</h2>
        <PatrolFieldSelect v-model="fieldId" />
        <el-select v-model="serial" aria-label="传感器" placeholder="选择传感器" :loading="loadingDevices">
          <el-option v-for="device in devices" :key="device.deviceSn"
            :label="`${device.deviceName} · ${device.online ? '在线' : '离线'}`" :value="device.deviceSn" />
        </el-select>
        <el-select v-model="range" aria-label="时间范围">
          <el-option v-for="(option, value) in ranges" :key="value" :value="value" :label="option.label" />
        </el-select>
      </div>
      <p v-if="latestError" class="monitor-error" role="alert">{{ latestError }}</p>
      <template v-if="latest">
        <div class="sensor-time sensor-device">
          <a :href="`#/patrol/devices/${latest.id}`">{{ latest.deviceName }}</a>
          <span class="flight-tag" :class="{ done: latest.online }">{{ latest.online ? '在线' : '离线' }}</span>
          <span class="monitor-muted">{{ latest.deviceSn }} · {{ latest.fieldName }} · 最近上报 {{ formatTime(latest.lastSeenAt) }}</span>
        </div>
        <dl class="sensor-metrics">
          <div v-for="metric in metrics" :key="metric.key">
            <dt>{{ metric.label }}</dt>
            <dd>{{ latest[metric.key] ?? '—' }} <small>{{ metric.unit }}</small></dd>
          </div>
        </dl>
      </template>
      <p v-else-if="!latestError" class="monitor-empty">{{ loadingDevices ? '正在加载…' : '暂无传感器上报记录' }}</p>
    </section>
    <p v-if="historyError" class="monitor-error" role="alert">{{ historyError }}</p>
    <p v-else-if="serial && !readings.length" class="monitor-empty" role="status">
      {{ loadingHistory ? '正在加载历史读数…' : '所选时间范围内暂无读数' }}
    </p>
    <div v-if="readings.length" class="sensor-charts">
      <section class="monitor-panel"><h2>土壤温湿度</h2>
        <SoilChart :readings="readings" :metrics="climate" label="土壤温度和湿度趋势折线图" /></section>
      <section class="monitor-panel"><h2>氮磷钾养分</h2>
        <SoilChart :readings="readings" :metrics="nutrients" label="土壤氮磷钾趋势折线图" /></section>
    </div>
  </main>
</template>

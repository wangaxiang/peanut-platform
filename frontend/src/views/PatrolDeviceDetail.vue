<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import PatrolDeviceMaintenance from '../components/PatrolDeviceMaintenance.vue'
import IotAccessPanel from '../components/IotAccessPanel.vue'
import { deviceStatuses, deviceTypes, getDevice } from '../api/devices'
import { canEditIot, iotRole } from '../api/iot'
import { formatTime, requestMessage } from '../api/patrol'

const props = defineProps({ deviceId: { type: String, required: true } })
const row = ref(null)
const error = ref('')
let request = 0
let timer

async function refresh() {
  const current = ++request
  try {
    const result = await getDevice(props.deviceId)
    if (current !== request) return
    row.value = result
    error.value = ''
  } catch (cause) {
    if (current === request) error.value = requestMessage(cause, '设备详情加载失败')
  }
}

onMounted(() => {
  refresh()
  timer = setInterval(() => { if (!document.hidden) refresh() }, 5000)
})
onBeforeUnmount(() => { clearInterval(timer); request++ })
watch(iotRole, refresh)
</script>

<template>
  <main class="monitor-page">
    <header class="monitor-header">
      <div><h1>{{ row?.deviceName || '设备详情' }}</h1><p v-if="row">
        {{ row.deviceTypeName || deviceTypes[row.deviceType] }} · {{ row.deviceSn }}
        <span v-if="row.accessMode==='simulated'" class="iot-sim-badge">模拟数据</span>
      </p></div>
      <div class="iot-actions"><a href="#/patrol/devices">返回设备管理</a><IotAccessPanel /></div>
    </header>
    <p v-if="error" class="monitor-error" role="alert">{{ error }}</p>
    <template v-if="row">
      <section class="monitor-panel device-detail">
        <h2>设备信息与状态</h2>
        <dl class="device-facts">
          <div><dt>在线状态</dt><dd><span class="flight-tag" :class="{ done: row.online }">{{ row.online ? (row.accessMode==='simulated'?'模拟上报中':'在线') : '离线' }}</span></dd></div>
          <div><dt>业务状态</dt><dd>{{ deviceStatuses[row.status] }}</dd></div>
          <div><dt>最后上报</dt><dd>{{ formatTime(row.lastSeenAt) }}</dd></div>
          <div><dt>电量</dt><dd>{{ row.batteryPct == null ? '—' : row.batteryPct + '%' }}</dd></div>
          <div><dt>信号</dt><dd>{{ row.signalStrengthPct == null ? '—' : row.signalStrengthPct + '%' }}</dd></div>
          <div><dt>最近遥测</dt><dd>{{ formatTime(row.lastTelemetryAt) }}</dd></div>
          <div><dt>所属地块</dt><dd>{{ row.fieldName || '共享设备' }}</dd></div>
          <div v-if="row.deviceType === 'drone'"><dt>关联机库</dt><dd>
            <a v-if="row.parentDeviceId" :href="`#/patrol/devices/${row.parentDeviceId}`">{{ row.parentDeviceName }}</a>
            <span v-else>—</span><span v-if="row.parentDeviceId"> · {{ row.parentOnline ? '在线' : '离线' }}</span>
          </dd></div>
          <div><dt>品牌／型号</dt><dd>{{ [row.brand, row.model].filter(Boolean).join(' / ') || '—' }}</dd></div>
          <div><dt>经纬度</dt><dd>{{ row.longitude == null || row.latitude == null ? '—' : `${row.longitude}, ${row.latitude}` }}</dd></div>
          <div v-if="row.deviceType === 'drone'"><dt>高度</dt><dd>{{ row.altitudeM == null ? '—' : row.altitudeM + ' m' }}</dd></div>
          <div><dt>当前阶段</dt><dd>{{ row.currentPhase || '—' }}</dd></div>
          <div><dt>固件</dt><dd>{{ row.firmwareVersion || '—' }}</dd></div>
          <div><dt>安装时间</dt><dd>{{ formatTime(row.installedAt) }}</dd></div>
          <div><dt>最近维护</dt><dd>{{ formatTime(row.lastMaintenanceAt) }}</dd></div>
          <div><dt>下次维护</dt><dd>{{ formatTime(row.nextMaintenanceAt) }}</dd></div>
          <div><dt>故障</dt><dd :class="{ 'device-fault': row.faultCode || row.faultMessage }">{{ [row.faultCode, row.faultMessage].filter(Boolean).join(' · ') || '暂无故障' }}</dd></div>
          <div v-if="row.remark"><dt>备注</dt><dd>{{ row.remark }}</dd></div>
        </dl>
        <div class="device-detail-extras">
          <details><summary>设备能力</summary><pre>{{ JSON.stringify(row.capabilities, null, 2) }}</pre></details>
          <details><summary>最近 MQTT 上报</summary><pre>{{ JSON.stringify(row.lastStatus, null, 2) }}</pre></details>
          <a v-if="row.deviceType === 'sensor' && row.status !== 'retired'" :href="`#/sensors/${row.deviceSn}`">查看传感器趋势</a>
          <a v-if="row.accessMode==='simulated'" href="#/patrol/simulator">打开模拟设备实验室</a>
        </div>
        <dl v-if="row.metricCodes.length" class="device-facts" aria-label="最近指标">
          <div v-for="code in row.metricCodes" :key="code"><dt>{{ code }}</dt>
            <dd>{{ row.latestMetrics[code]?.value ?? '暂无数据' }} {{ row.latestMetrics[code]?.unit }}</dd></div>
        </dl>
      </section>
      <PatrolDeviceMaintenance :key="iotRole" :device-id="row.id" :readonly="row.status === 'retired'||!canEditIot" @saved="refresh" />
    </template>
  </main>
</template>

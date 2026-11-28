<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PatrolFieldSelect from '../components/PatrolFieldSelect.vue'
import PatrolDeviceForm from '../components/PatrolDeviceForm.vue'
import { changeDeviceStatus, deviceStatuses, deviceTypes, getDevices, getDeviceStats, retireDevice } from '../api/devices'
import { formatTime, requestMessage } from '../api/patrol'

const filters = reactive({ keyword: '', deviceType: undefined, fieldId: undefined, online: undefined, status: undefined })
const page = ref(1)
const rows = ref([])
const total = ref(0)
const stats = ref(null)
const edit = ref(null)
const formOpen = ref(false)
const error = ref('')
const loading = ref(false)
const busy = reactive(new Set())
let request = 0
let timer
let debounce

async function refresh() {
  const current = ++request
  loading.value = true
  try {
    const [devices, totals] = await Promise.all([
      getDevices({ ...filters, page: page.value - 1, size: 20 }), getDeviceStats(),
    ])
    if (current !== request) return
    rows.value = devices.content
    total.value = devices.totalElements
    stats.value = totals
    error.value = ''
  } catch (cause) {
    if (current === request) error.value = requestMessage(cause, '设备台账加载失败')
  } finally {
    if (current === request) loading.value = false
  }
}

function openForm(device = null) {
  edit.value = device
  formOpen.value = true
}

async function updateStatus(device, status) {
  busy.add(device.id)
  try {
    await changeDeviceStatus(device.id, status)
    await refresh()
  } catch (cause) {
    ElMessage.error(requestMessage(cause, '状态修改失败'))
  } finally {
    busy.delete(device.id)
  }
}

async function retire(device) {
  try {
    await ElMessageBox.confirm(`确认退役「${device.deviceName}」？设备与维护历史将保留。`, '退役设备',
      { confirmButtonText: '确认退役', cancelButtonText: '取消' })
  } catch {
    return
  }
  busy.add(device.id)
  try {
    await retireDevice(device.id)
    await refresh()
  } catch (cause) {
    ElMessage.error(requestMessage(cause, '设备退役失败'))
  } finally {
    busy.delete(device.id)
  }
}

watch(filters, () => {
  clearTimeout(debounce)
  debounce = setTimeout(() => { if (page.value === 1) refresh(); else page.value = 1 }, 250)
})
watch(page, refresh)
onMounted(() => {
  refresh()
  timer = setInterval(() => { if (!document.hidden && !loading.value) refresh() }, 10000)
})
onBeforeUnmount(() => { clearInterval(timer); clearTimeout(debounce); request++ })
</script>

<template>
  <main class="monitor-page">
    <header class="monitor-header">
      <div><h1>设备管理</h1><p>登记巡田设备，查看连接状态、地块绑定与维护历史。</p></div>
      <el-button type="primary" @click="openForm()">添加设备</el-button>
    </header>
    <dl class="device-stat-grid">
      <div v-for="(label, key) in { total: '设备总数', online: '在线', offline: '离线', maintenance: '维护中' }" :key="key" class="monitor-panel">
        <dt>{{ label }}</dt><dd>{{ stats?.[key] ?? '—' }}</dd>
      </div>
    </dl>
    <section class="monitor-panel">
      <div class="monitor-toolbar device-filters">
        <el-input v-model="filters.keyword" aria-label="搜索设备" placeholder="搜索名称或序列号" clearable />
        <PatrolFieldSelect v-model="filters.fieldId" />
        <el-select v-model="filters.deviceType" aria-label="筛选设备类型" placeholder="全部类型" clearable>
          <el-option v-for="(label, value) in deviceTypes" :key="value" :label="label" :value="value" />
        </el-select>
        <el-select v-model="filters.online" aria-label="筛选在线状态" placeholder="在线状态" clearable>
          <el-option label="在线" :value="true" /><el-option label="离线" :value="false" />
        </el-select>
        <el-select v-model="filters.status" aria-label="筛选业务状态" placeholder="业务状态" clearable>
          <el-option v-for="(label, value) in deviceStatuses" :key="value" :label="label" :value="value" />
        </el-select>
      </div>
      <p v-if="error" class="monitor-error" role="alert">{{ error }} <button class="text-button" @click="refresh">重试</button></p>
      <div class="monitor-table-wrap" :aria-busy="loading">
        <table class="monitor-table">
          <thead><tr><th>设备</th><th>类型</th><th>地块／机库</th><th>连接与电量</th><th>业务状态</th><th>故障</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="device in rows" :key="device.id">
              <td><a :href="`#/patrol/devices/${device.id}`">{{ device.deviceName }}</a><small>{{ device.deviceSn }}</small></td>
              <td>{{ deviceTypes[device.deviceType] }}</td>
              <td>{{ device.fieldName || '共享设备' }}<small v-if="device.parentDeviceName">{{ device.parentDeviceName }}</small></td>
              <td><span class="flight-tag" :class="{ done: device.online }">{{ device.online ? '在线' : '离线' }}</span>
                {{ device.batteryPct == null ? '—' : device.batteryPct + '%' }}
                <small>{{ formatTime(device.lastSeenAt) }}</small></td>
              <td>{{ deviceStatuses[device.status] }}</td>
              <td>{{ device.faultCode || (device.faultMessage ? '有故障' : '—') }}</td>
              <td><div v-if="device.status !== 'retired'" class="device-row-actions">
                <el-button size="small" :disabled="busy.has(device.id)" @click="openForm(device)">编辑</el-button>
                <el-button size="small" :disabled="busy.has(device.id)"
                  @click="updateStatus(device, device.status === 'active' ? 'inactive' : 'active')">
                  {{ device.status === 'active' ? '停用' : '启用' }}</el-button>
                <el-button v-if="device.status !== 'maintenance'" size="small" :disabled="busy.has(device.id)"
                  @click="updateStatus(device, 'maintenance')">标为维护中</el-button>
                <el-button size="small" :disabled="busy.has(device.id)" @click="retire(device)">退役</el-button>
              </div><span v-else>—</span></td>
            </tr>
            <tr v-if="!rows.length"><td colspan="7" class="monitor-empty">{{ loading ? '正在加载…' : '暂无符合条件的设备' }}</td></tr>
          </tbody>
        </table>
      </div>
      <div v-if="total > 20" class="monitor-footer">
        <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" />
      </div>
    </section>
    <PatrolDeviceForm v-model="formOpen" :device="edit" @saved="refresh" />
  </main>
</template>

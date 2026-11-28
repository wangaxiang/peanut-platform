<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PatrolFieldSelect from '../components/PatrolFieldSelect.vue'
import { formatTime, getFlights, requestMessage, triggerFlight, uploadFlightImage } from '../api/patrol'

const props = defineProps({ ndviOnly: Boolean })
const statuses = { pending: '待下发', dispatching: '下发中', flying: '飞行中', processing: '处理中', done: '已完成', failed: '失败' }
const fieldId = ref()
const page = ref(1)
const result = ref({ content: [], totalElements: 0 })
const loading = ref(false)
const error = ref('')
const busy = reactive(new Set())
let request = 0
let timer

async function refresh() {
  const current = ++request
  loading.value = true
  try {
    const data = await getFlights({ fieldId: fieldId.value, ndviOnly: props.ndviOnly || undefined, page: page.value - 1, size: 20 })
    if (current === request) {
      result.value = data
      error.value = ''
    }
  } catch (cause) {
    if (current === request) error.value = requestMessage(cause, '飞行记录加载失败')
  } finally {
    if (current === request) loading.value = false
  }
}

async function dispatch(flight) {
  try {
    await ElMessageBox.confirm(`将下发飞行 #${flight.id} 的起飞指令。`, '手动触发飞行',
      { confirmButtonText: '确认下发', cancelButtonText: '取消' })
  } catch {
    return
  }
  busy.add(flight.id)
  try {
    await triggerFlight(flight.id)
    ElMessage.success('飞行指令已下发')
  } catch (cause) {
    ElMessage.error(requestMessage(cause, '飞行指令下发失败'))
  } finally {
    await refresh()
    busy.delete(flight.id)
  }
}

function canUpload(flight) {
  return flight.status === 'flying' || (flight.status === 'processing' && !flight.rawImagePath)
    || (flight.status === 'failed' && flight.errorMessage?.startsWith('NDVI'))
}

async function upload(flight, event) {
  const file = event.target.files[0]
  event.target.value = ''
  if (!file) return
  if (!/\.tiff?$/i.test(file.name) || file.size > 20 * 1024 * 1024) {
    ElMessage.error('请选择不超过 20 MiB 的 TIFF 文件')
    return
  }
  busy.add(flight.id)
  try {
    const current = await uploadFlightImage(flight.id, file)
    ElMessage.success(current.status === 'flying' ? '影像已上传，飞行结束后将处理' : '影像已提交处理')
  } catch (cause) {
    ElMessage.error(requestMessage(cause, '上传影像失败'))
  } finally {
    await refresh()
    busy.delete(flight.id)
  }
}

watch(fieldId, () => { page.value = 1 })
watch([fieldId, page], refresh, { immediate: true })
onMounted(() => {
  timer = window.setInterval(() => {
    if (!document.hidden && !loading.value) refresh()
  }, 5000)
})
onBeforeUnmount(() => { clearInterval(timer); request++ })
</script>

<template>
  <main class="monitor-page">
    <header class="monitor-header">
      <div><h1>{{ ndviOnly ? 'NDVI 监测' : '飞行记录' }}</h1><p>{{ ndviOnly ? '按地块查看已生成的植被指数地图。' : '跟踪任务下发、影像上传和 NDVI 处理进度。' }}</p></div>
      <el-button :loading="loading" @click="refresh">刷新记录</el-button>
    </header>
    <section class="monitor-panel">
      <div class="monitor-toolbar"><h2>巡田飞行</h2><PatrolFieldSelect v-model="fieldId" /></div>
      <p v-if="error" class="monitor-error" role="alert">{{ error }}</p>
      <div class="monitor-table-wrap" :aria-busy="loading">
        <table class="monitor-table">
          <thead><tr><th>飞行</th><th>地块</th><th>开始时间</th><th>结束时间</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="flight in result.content" :key="flight.id">
              <td><strong>{{ flight.taskName || '巡田任务' }}</strong><small>#{{ flight.id }} · {{ flight.scheduledFor ? '定时' : '手动' }}</small>
                <small v-if="flight.droneSn">
                  <a v-if="flight.droneDeviceId" :href="`#/patrol/devices/${flight.droneDeviceId}`">{{ flight.droneName }} · {{ flight.droneSn }}</a>
                  <span v-else>{{ flight.droneSn }}</span>
                </small>
              </td>
              <td>{{ flight.fieldName }}</td><td>{{ formatTime(flight.startTime) }}</td><td>{{ formatTime(flight.endTime) }}</td>
              <td><span class="flight-tag" :class="flight.status">{{ statuses[flight.status] ?? flight.status }}</span>
                <small v-if="flight.errorMessage">{{ flight.errorMessage }}</small>
                <small v-else-if="flight.status === 'processing' && !flight.rawImagePath">等待影像</small></td>
              <td>
                <a v-if="flight.ndviTileUrl" :href="`#/patrol/ndvi/${flight.id}`">查看 NDVI</a>
                <el-button v-else-if="flight.status === 'pending'" size="small" :loading="busy.has(flight.id)"
                  @click="dispatch(flight)">手动触发</el-button>
                <label v-else-if="canUpload(flight)" class="monitor-upload" :class="{ disabled: busy.has(flight.id) }">
                  {{ busy.has(flight.id) ? '正在上传…' : '上传多光谱影像' }}
                  <input type="file" :aria-label="`上传飞行 ${flight.id} 的多光谱影像`"
                    accept=".tif,.tiff" :disabled="busy.has(flight.id)" @change="upload(flight, $event)">
                </label>
                <span v-else>—</span>
              </td>
            </tr>
            <tr v-if="!result.content.length"><td colspan="6" class="monitor-empty">{{ loading ? '正在加载…' : '暂无飞行记录' }}</td></tr>
          </tbody>
        </table>
      </div>
      <div class="monitor-footer">
        <a href="#/patrol">返回巡田任务</a>
        <el-pagination v-if="result.totalElements > 20" v-model:current-page="page" :page-size="20"
          :total="result.totalElements" layout="prev, pager, next" small />
      </div>
    </section>
  </main>
</template>

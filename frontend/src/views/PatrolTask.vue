<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, ElSwitch } from 'element-plus'
import PatrolFieldSelect from '../components/PatrolFieldSelect.vue'
import { createTask, formatTime, getTasks, newFlight, requestMessage, setTaskStatus, triggerFlight } from '../api/patrol'

const fieldId = ref()
const page = ref(1)
const result = ref({ content: [], totalElements: 0 })
const error = ref('')
const loading = ref(false)
const editing = ref(false)
const saving = ref(false)
const formError = ref('')
const busy = reactive(new Set())
const emptyForm = () => ({
  fieldId: undefined, taskName: '', scheduleMode: 'date', scheduledAt: '', scheduleCron: '', droneSn: '', route: '',
})
const form = reactive(emptyForm())
let request = 0

async function refresh() {
  const current = ++request
  loading.value = true
  error.value = ''
  try {
    const data = await getTasks({ fieldId: fieldId.value, page: page.value - 1, size: 20 })
    if (current === request) result.value = data
  } catch (cause) {
    if (current === request) error.value = requestMessage(cause, '巡田任务加载失败')
  } finally {
    if (current === request) loading.value = false
  }
}

function openForm() {
  Object.assign(form, emptyForm())
  formError.value = ''
  editing.value = true
}

function disablePastDate(date) {
  return date.getTime() < new Date().setHours(0, 0, 0, 0)
}

async function save() {
  formError.value = ''
  if (!form.fieldId || !form.taskName.trim() || !form.droneSn.trim()) {
    formError.value = '请填写地块、任务名称和设备序列号'
    return
  }
  let schedule
  if (form.scheduleMode === 'date') {
    const date = new Date(form.scheduledAt)
    if (!form.scheduledAt || Number.isNaN(date.getTime()) || date.getTime() <= Date.now()) {
      formError.value = '请选择将来的执行日期和时间'
      return
    }
    schedule = { scheduledAt: date.toISOString() }
  } else {
    const cron = form.scheduleCron.trim()
    if (cron.split(/\s+/).length !== 5) {
      formError.value = '请填写五段 cron，例如 0 6 * * *'
      return
    }
    schedule = { scheduleCron: cron }
  }
  let routeGeojson
  try {
    routeGeojson = JSON.parse(form.route)
  } catch {
    formError.value = '请粘贴有效的 GeoJSON 航点集合'
    return
  }
  saving.value = true
  try {
    await createTask({ fieldId: form.fieldId, taskName: form.taskName.trim(),
      ...schedule, droneSn: form.droneSn.trim(), routeGeojson })
    editing.value = false
    ElMessage.success('已创建巡田任务')
    const resetFilter = fieldId.value && fieldId.value !== form.fieldId
    if (resetFilter) fieldId.value = undefined
    if (page.value !== 1 || resetFilter) page.value = 1
    else await refresh()
  } catch (cause) {
    formError.value = requestMessage(cause, '创建巡田任务失败')
  } finally {
    saving.value = false
  }
}

async function changeStatus(task, enabled) {
  busy.add(task.id)
  try {
    Object.assign(task, await setTaskStatus(task.id, enabled ? 'active' : 'paused'))
  } catch (cause) {
    ElMessage.error(requestMessage(cause, '切换任务状态失败'))
  } finally {
    busy.delete(task.id)
  }
}

async function dispatch(task) {
  try {
    await ElMessageBox.confirm(`将向设备 ${task.droneSn} 发送「${task.taskName}」的起飞指令。`,
      '手动触发飞行', { confirmButtonText: '确认下发', cancelButtonText: '取消' })
  } catch {
    return
  }
  busy.add(task.id)
  let flight
  try {
    flight = await newFlight(task.id)
    await triggerFlight(flight.id)
    ElMessage.success(`飞行 #${flight.id} 的指令已下发`)
  } catch (cause) {
    ElMessage.error(requestMessage(cause, '飞行指令下发失败'))
  } finally {
    busy.delete(task.id)
    if (flight) window.location.hash = '/patrol/flights'
  }
}

watch(fieldId, () => { page.value = 1 })
watch([fieldId, page], refresh, { immediate: true })
</script>

<template>
  <main class="monitor-page">
    <header class="monitor-header">
      <div><h1>智能巡田</h1><p>安排定时巡田，管理无人机航线和飞行记录。</p></div>
      <el-button type="primary" @click="openForm">新建巡田任务</el-button>
    </header>
    <section class="monitor-panel">
      <div class="monitor-toolbar">
        <h2>巡田任务</h2>
        <PatrolFieldSelect v-model="fieldId" />
      </div>
      <p v-if="error" class="monitor-error" role="alert">{{ error }} <button class="text-button" @click="refresh">重试</button></p>
      <div class="monitor-table-wrap" :aria-busy="loading">
        <table class="monitor-table">
          <thead><tr><th>任务</th><th>地块</th><th>设备序列号</th><th>执行时间</th><th>已启用</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="task in result.content" :key="task.id">
              <td><strong>{{ task.taskName || '巡田任务' }}</strong><small>{{ formatTime(task.createdAt) }} 创建</small></td>
              <td>{{ task.fieldName }}</td><td>{{ task.droneSn }}</td>
              <td>
                <template v-if="task.scheduledAt">{{ formatTime(task.scheduledAt) }}<small>单次执行</small></template>
                <code v-else>{{ task.scheduleCron || '—' }}</code>
              </td>
              <td><el-switch :model-value="task.status === 'active'" :disabled="busy.has(task.id)"
                :aria-label="`启用 ${task.taskName}`" @change="changeStatus(task, $event)" /></td>
              <td><el-button size="small" :disabled="task.status !== 'active' || busy.has(task.id)"
                @click="dispatch(task)">手动触发</el-button></td>
            </tr>
            <tr v-if="!result.content.length"><td colspan="6" class="monitor-empty">{{ loading ? '正在加载…' : '暂无巡田任务' }}</td></tr>
          </tbody>
        </table>
      </div>
      <div class="monitor-footer">
        <a href="#/patrol/flights">查看飞行记录</a>
        <el-pagination v-if="result.totalElements > 20" v-model:current-page="page" :page-size="20"
          :total="result.totalElements" layout="prev, pager, next" small />
      </div>
    </section>
    <el-dialog v-model="editing" title="新建巡田任务" width="min(560px, 94vw)" top="6vh"
      class="patrol-task-dialog" :before-close="done => { if (!saving) done() }">
      <form class="dialog-form" @submit.prevent="save">
        <label>地块</label><PatrolFieldSelect v-if="editing" v-model="form.fieldId" label="选择地块" :clearable="false" />
        <label for="patrol-name">任务名称</label><el-input id="patrol-name" v-model="form.taskName" maxlength="100" />
        <label for="patrol-drone">机库或无人机序列号</label><el-input id="patrol-drone" v-model="form.droneSn" maxlength="64" />
        <fieldset class="patrol-schedule">
          <legend>执行计划</legend>
          <div class="patrol-schedule-options">
            <label><input v-model="form.scheduleMode" type="radio" name="patrol-schedule" value="date">选择日期和时间</label>
            <label><input v-model="form.scheduleMode" type="radio" name="patrol-schedule" value="cron">手动填写 cron</label>
          </div>
          <template v-if="form.scheduleMode === 'date'">
            <el-date-picker v-if="editing" id="patrol-scheduled-at" v-model="form.scheduledAt" type="datetime"
              aria-label="执行日期和时间" placeholder="选择或输入执行日期和时间" :disabled-date="disablePastDate"
              format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm" />
            <small>按当前设备时区，选定时间只执行一次。</small>
          </template>
          <template v-else>
            <el-input id="patrol-cron" v-model="form.scheduleCron" aria-label="五段 cron"
              placeholder="例如 0 6 * * 1,4" maxlength="50" />
            <small>按分、时、日、月、星期填写。示例为每周一和周四 06:00，平台默认为北京时间。</small>
          </template>
        </fieldset>
        <label for="patrol-route">航线 GeoJSON</label>
        <el-input id="patrol-route" v-model="form.route" type="textarea" :rows="5" placeholder='{"type":"FeatureCollection","features":[…]}' />
        <small>粘贴已规划的航线，至少包含两个 Point 航点。坐标顺序为经度、纬度。</small>
        <p v-if="formError" class="monitor-error" role="alert">{{ formError }}</p>
        <div class="dialog-actions"><el-button :disabled="saving" @click="editing = false">取消</el-button>
          <el-button native-type="submit" type="primary" :loading="saving">创建并启用</el-button></div>
      </form>
    </el-dialog>
  </main>
</template>

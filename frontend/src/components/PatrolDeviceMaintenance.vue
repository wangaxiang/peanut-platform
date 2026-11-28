<script setup>
import { reactive, ref, watch } from 'vue'
import { createMaintenance, getMaintenance } from '../api/devices'
import { formatTime, requestMessage } from '../api/patrol'

const props = defineProps({ deviceId: { type: Number, required: true }, readonly: Boolean })
const emit = defineEmits(['saved'])
const types = { inspection: '巡检', repair: '维修', firmware: '固件更新', battery: '电池维护', calibration: '校准', other: '其他' }
const statuses = { planned: '计划中', in_progress: '进行中', completed: '已完成', cancelled: '已取消' }
const form = reactive({})
const page = ref(1)
const total = ref(0)
const records = ref([])
const error = ref('')
const formError = ref('')
const open = ref(false)
const saving = ref(false)
let request = 0

async function refresh() {
  const current = ++request
  try {
    const result = await getMaintenance(props.deviceId, page.value - 1)
    if (current !== request) return
    records.value = result.content
    total.value = result.totalElements
    error.value = ''
  } catch (cause) {
    if (current === request) error.value = requestMessage(cause, '维护记录加载失败')
  }
}

function start() {
  Object.assign(form, { maintenanceType: 'inspection', status: 'completed', description: '', operatorName: '',
    startedAt: null, completedAt: null, nextMaintenanceAt: null })
  formError.value = ''
  open.value = true
}

async function save() {
  if (!form.description.trim()) {
    formError.value = '请填写维护内容'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    await createMaintenance(props.deviceId, { ...form, startedAt: form.startedAt?.toISOString() ?? null,
      completedAt: form.status === 'completed' ? form.completedAt?.toISOString() ?? null : null,
      nextMaintenanceAt: form.nextMaintenanceAt?.toISOString() ?? null })
    open.value = false
    if (page.value === 1) await refresh()
    else page.value = 1
    emit('saved')
  } catch (cause) {
    formError.value = requestMessage(cause, '保存维护记录失败')
  } finally {
    saving.value = false
  }
}

watch(page, refresh, { immediate: true })
</script>

<template>
  <section class="monitor-panel">
    <div class="monitor-toolbar">
      <h2>维护记录</h2><el-button v-if="!readonly" type="primary" plain @click="start">新增维护记录</el-button>
    </div>
    <p v-if="error" class="monitor-error" role="alert">{{ error }} <button class="text-button" @click="refresh">重试</button></p>
    <ol v-if="records.length" class="device-maintenance">
      <li v-for="record in records" :key="record.id">
        <strong>{{ types[record.maintenanceType] }} · {{ statuses[record.status] }}</strong>
        <time>{{ formatTime(record.completedAt || record.startedAt || record.createdAt) }}</time>
        <p>{{ record.description }}</p>
        <p v-if="record.operatorName || record.nextMaintenanceAt" class="monitor-muted">
          {{ record.operatorName || '操作人未填' }}<template v-if="record.nextMaintenanceAt"> · 下次维护 {{ formatTime(record.nextMaintenanceAt) }}</template>
        </p>
      </li>
    </ol>
    <p v-else-if="!error" class="monitor-empty">暂无维护记录</p>
    <div v-if="total > 10" class="monitor-footer">
      <el-pagination v-model:current-page="page" :page-size="10" :total="total" layout="prev, pager, next" />
    </div>
    <el-dialog v-model="open" title="新增维护记录" width="min(540px, 94vw)" top="6vh" class="patrol-task-dialog"
      :before-close="done => { if (!saving) done() }">
      <form class="dialog-form" @submit.prevent="save">
        <div class="form-pair">
          <div><label for="maint-type">维护类型</label><el-select id="maint-type" v-model="form.maintenanceType">
            <el-option v-for="(label, value) in types" :key="value" :label="label" :value="value" /></el-select></div>
          <div><label for="maint-status">记录状态</label><el-select id="maint-status" v-model="form.status">
            <el-option v-for="(label, value) in statuses" :key="value" :label="label" :value="value" /></el-select></div>
          <div><label for="maint-start">开始时间</label><el-date-picker id="maint-start" v-model="form.startedAt" type="datetime" placeholder="可选" /></div>
          <div v-if="form.status === 'completed'"><label for="maint-end">完成时间</label>
            <el-date-picker id="maint-end" v-model="form.completedAt" type="datetime" placeholder="默认当前时间" /></div>
          <div><label for="maint-next">下次维护</label><el-date-picker id="maint-next" v-model="form.nextMaintenanceAt" type="datetime" placeholder="可选" /></div>
          <div><label for="maint-operator">操作人</label><el-input id="maint-operator" v-model="form.operatorName" maxlength="80" /></div>
        </div>
        <label for="maint-description">维护内容</label>
        <el-input id="maint-description" v-model="form.description" type="textarea" :rows="4" maxlength="4000" />
        <p v-if="formError" class="monitor-error" role="alert">{{ formError }}</p>
        <div class="dialog-actions">
          <el-button :disabled="saving" @click="open = false">取消</el-button>
          <el-button native-type="submit" type="primary" :loading="saving">保存维护记录</el-button>
        </div>
      </form>
    </el-dialog>
  </section>
</template>

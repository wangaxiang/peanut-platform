<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { createDevice, deviceTypes, getDevices, updateDevice } from '../api/devices'
import { requestMessage } from '../api/patrol'
import PatrolFieldSelect from './PatrolFieldSelect.vue'

const props = defineProps({ modelValue: Boolean, device: Object })
const emit = defineEmits(['update:modelValue', 'saved'])
const form = reactive({})
const saving = ref(false)
const error = ref('')
const docks = ref([])
const loading = ref(false)
let dockRequest = 0

watch(() => props.modelValue, async open => {
  if (!open) return
  const device = props.device
  Object.assign(form, {
    deviceType: device?.deviceType ?? 'drone',
    deviceSn: device?.deviceSn ?? '',
    deviceName: device?.deviceName ?? '',
    brand: device?.brand ?? '',
    model: device?.model ?? '',
    fieldId: device?.fieldId ?? undefined,
    parentDeviceId: device?.parentDeviceId ?? undefined,
    capabilities: device?.capabilities ? JSON.stringify(device.capabilities, null, 2) : '',
    installedAt: device?.installedAt ? new Date(device.installedAt) : null,
    remark: device?.remark ?? '',
  })
  docks.value = device?.parentDeviceId
    ? [{ id: device.parentDeviceId, deviceName: device.parentDeviceName, deviceSn: device.parentDeviceSn, fieldId: null }]
    : []
  error.value = ''
  if (form.deviceType === 'drone') await searchDocks()
})
const visibleDocks = computed(() => docks.value.filter(dock => !form.fieldId || !dock.fieldId || dock.fieldId === form.fieldId))

async function searchDocks(keyword = '') {
  const current = ++dockRequest
  loading.value = true
  try {
    const data = await getDevices({ deviceType: 'dock', keyword, size: 50 })
    if (current !== dockRequest) return
    const selected = docks.value.find(dock => dock.id === form.parentDeviceId)
    const available = data.content.filter(dock => dock.status !== 'retired')
    docks.value = selected && !available.some(dock => dock.id === selected.id) ? [selected, ...available] : available
  } catch (cause) {
    if (current === dockRequest) error.value = requestMessage(cause, '机库列表加载失败')
  } finally {
    if (current === dockRequest) loading.value = false
  }
}

function switchType(type) {
  form.parentDeviceId = undefined
  if (type === 'drone') searchDocks()
}

async function save() {
  error.value = ''
  let capabilities
  try {
    capabilities = form.capabilities.trim() ? JSON.parse(form.capabilities) : {}
    if (!capabilities || Array.isArray(capabilities) || typeof capabilities !== 'object') throw new Error()
  } catch {
    error.value = '设备能力请填写 JSON 对象'
    return
  }
  if (!form.deviceName.trim() || !/^[A-Za-z0-9_-]{1,64}$/.test(form.deviceSn.trim())) {
    error.value = '填写设备名称和有效序列号（字母、数字、短横线或下划线）'
    return
  }
  if (form.deviceType === 'sensor' && !form.fieldId) {
    error.value = '请选择传感器所属地块'
    return
  }
  saving.value = true
  const body = { ...form, deviceName: form.deviceName.trim(), deviceSn: form.deviceSn.trim(), capabilities,
    parentDeviceId: form.deviceType === 'drone' ? form.parentDeviceId || null : null,
    installedAt: form.installedAt?.toISOString() ?? null }
  try {
    const device = props.device ? await updateDevice(props.device.id, body) : await createDevice(body)
    emit('saved', device)
    emit('update:modelValue', false)
  } catch (cause) {
    error.value = requestMessage(cause, '保存设备失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="device ? '编辑设备' : '添加设备'" width="min(580px, 94vw)" top="5vh"
    class="patrol-task-dialog" :before-close="done => { if (!saving) done() }" @update:model-value="emit('update:modelValue', $event)">
    <form class="dialog-form" @submit.prevent="save">
      <label for="device-type">设备类型</label>
      <el-select id="device-type" v-model="form.deviceType" :disabled="!!device" aria-label="设备类型" @change="switchType">
        <el-option v-for="(label, value) in deviceTypes" :key="value" :label="label" :value="value" />
      </el-select>
      <div class="form-pair">
        <div><label for="device-name">设备名称</label><el-input id="device-name" v-model="form.deviceName" maxlength="100" /></div>
        <div><label for="device-sn">设备序列号</label><el-input id="device-sn" v-model="form.deviceSn" maxlength="64" :disabled="!!device" /></div>
        <div><label for="device-brand">品牌</label><el-input id="device-brand" v-model="form.brand" maxlength="80" /></div>
        <div><label for="device-model">型号</label><el-input id="device-model" v-model="form.model" maxlength="100" /></div>
      </div>
      <label>所属地块{{ form.deviceType === 'sensor' ? '（必选）' : '' }}</label>
      <PatrolFieldSelect v-if="modelValue" v-model="form.fieldId" label="选择所属地块" :clearable="form.deviceType !== 'sensor'" />
      <template v-if="form.deviceType === 'drone'">
        <label for="device-dock">关联机库</label>
        <el-select id="device-dock" v-model="form.parentDeviceId" aria-label="关联机库" clearable filterable remote
          :remote-method="searchDocks" :loading="loading" placeholder="选择机库（可选）">
          <el-option v-for="dock in visibleDocks" :key="dock.id" :label="`${dock.deviceName} · ${dock.deviceSn}`" :value="dock.id" />
        </el-select>
      </template>
      <label for="device-installed">安装时间</label>
      <el-date-picker id="device-installed" v-model="form.installedAt" type="datetime" format="YYYY-MM-DD HH:mm" placeholder="可选" />
      <details>
        <summary>设备能力（可选）</summary>
        <el-input id="device-capabilities" v-model="form.capabilities" type="textarea" :rows="2" placeholder='{"multispectral":true,"redBand":3,"nirBand":4}' />
      </details>
      <label for="device-remark">备注</label><el-input id="device-remark" v-model="form.remark" type="textarea" :rows="2" maxlength="4000" />
      <p v-if="error" class="monitor-error" role="alert">{{ error }}</p>
      <div class="dialog-actions">
        <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
        <el-button native-type="submit" type="primary" :loading="saving">保存设备</el-button>
      </div>
    </form>
  </el-dialog>
</template>

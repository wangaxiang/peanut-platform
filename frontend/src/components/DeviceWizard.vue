<script setup>
import { computed, reactive, ref, watch } from 'vue'
import PatrolFieldSelect from './PatrolFieldSelect.vue'
import { createDevice, getDevices, updateDevice } from '../api/devices'
import { getDeviceTypes, getMetricDefinitions, isIotAdmin } from '../api/iot'
import { requestMessage } from '../api/patrol'

const props=defineProps({ modelValue:Boolean, device:Object, initialMode:String })
const emit=defineEmits(['update:modelValue','saved'])
const steps=['设备类型','基本资料','接入与归属','指标与采样','确认登记']
const protocols={ mqtt:'MQTT',http:'HTTP',lorawan_gateway:'LoRaWAN 网关',vendor_adapter:'厂商适配器',other:'其他' }
const form=reactive({})
const types=ref([])
const metrics=ref([])
const docks=ref([])
const step=ref(0)
const loading=ref(false)
const saving=ref(false)
const error=ref('')
const currentType=computed(() => types.value.find(type => type.code===form.deviceType))
const fieldRequired=computed(() => form.deviceType==='sensor' || currentType.value?.capabilitySchema?.fieldRequired)
const compatibleDocks=computed(() => docks.value.filter(dock => dock.accessMode===form.accessMode
    && (!form.fieldId || !dock.fieldId || form.fieldId===dock.fieldId)))

watch(() => props.modelValue, async value => {
  if (!value) return
  const device=props.device
  Object.assign(form,{
    deviceType:device?.deviceType || (props.initialMode==='simulated'?'soil_multi_sensor':'drone'),
    deviceName:device?.deviceName || '',deviceSn:device?.deviceSn || '',brand:device?.brand || '',
    model:device?.model || '',firmwareVersion:device?.firmwareVersion || '',remark:device?.remark || '',
    accessMode:device?.accessMode || props.initialMode || 'real',protocol:device?.protocol || 'mqtt',
    fieldId:device?.fieldId ?? undefined,parentDeviceId:device?.parentDeviceId ?? undefined,
    installLocation:device?.installLocation || '',longitude:device?.longitude ?? undefined,latitude:device?.latitude ?? undefined,
    sampleIntervalSeconds:device?.sampleIntervalSeconds ?? undefined,tagsText:(device?.tags || []).join('，'),
    metricCodes:[...(device?.metricCodes || [])],capabilities:device?.capabilities || {},
    installedAt:device?.installedAt,
  })
  if (form.accessMode==='simulated') form.protocol='simulator'
  step.value=0
  loading.value=true
  error.value=''
  try {
    const [typeRows,metricRows,dockRows]=await Promise.all([
      getDeviceTypes(),getMetricDefinitions(),getDevices({deviceType:'dock',status:'active',size:200}),
    ])
    types.value=typeRows
    metrics.value=metricRows
    docks.value=dockRows.content
    if (!device) chooseType()
  } catch (e) { error.value=requestMessage(e,'无法加载设备向导') }
  finally { loading.value=false }
})

function chooseType() {
  form.metricCodes=(currentType.value?.capabilitySchema?.metrics || []).filter(code => metrics.value.some(m=>m.code===code&&m.enabled))
  form.sampleIntervalSeconds=form.accessMode==='simulated'?60:currentType.value?.defaultSampleIntervalSeconds ?? undefined
  form.parentDeviceId=undefined
}
function chooseMode() {
  form.protocol=form.accessMode==='simulated'?'simulator':'mqtt'
  form.parentDeviceId=undefined
  if (form.accessMode==='simulated'&&!form.sampleIntervalSeconds) form.sampleIntervalSeconds=60
}
function validate() {
  if (step.value===0&&!currentType.value) return '请选择设备类型'
  if (step.value===1&&(!form.deviceName.trim()||!form.deviceSn.trim())) return '请输入设备名称和序列号'
  if (step.value===1&&!/^[A-Za-z0-9_-]{1,64}$/.test(form.deviceSn.trim())) return '序列号限 64 位字母、数字、短横线或下划线'
  if (step.value===2&&fieldRequired.value&&!form.fieldId) return '此类型设备需要绑定地块'
  if (step.value===3&&form.accessMode==='simulated'&&!form.metricCodes.length) return '模拟设备至少选择一个指标'
  return ''
}
function next() { error.value=validate(); if (!error.value) step.value++ }
async function save() {
  saving.value=true
  error.value=''
  try {
    const { tagsText,...rest }=form
    const payload={...rest,deviceSn:form.deviceSn.trim(),deviceName:form.deviceName.trim(),
      fieldId:form.fieldId??null,parentDeviceId:form.deviceType==='drone'?form.parentDeviceId??null:null,
      tags:tagsText.split(/[，,]/).map(v=>v.trim()).filter(Boolean)}
    const device=props.device?await updateDevice(props.device.id,payload):await createDevice(payload)
    emit('saved',device)
    emit('update:modelValue',false)
  } catch(e) { error.value=requestMessage(e,'保存设备失败') }
  finally { saving.value=false }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="device?'编辑设备':'添加设备'" width="min(740px, 94vw)" top="5vh"
    class="patrol-task-dialog" :before-close="done=>{ if(!saving) done() }" @update:model-value="emit('update:modelValue',$event)">
    <nav class="iot-steps" aria-label="设备向导步骤">
      <span v-for="(label,index) in steps" :key="label" :aria-current="step===index?'step':undefined">
        {{ index+1 }} {{ label }}
      </span>
    </nav>
    <p v-if="loading" role="status" class="monitor-empty">正在读取设备类型和指标…</p>
    <form v-else class="dialog-form" @submit.prevent="step<4?next():save()">
      <template v-if="step===0">
        <label for="wizard-type">设备类型</label>
        <select id="wizard-type" v-model="form.deviceType" :disabled="!!device" @change="chooseType">
          <option v-for="type in types.filter(row=>row.enabled||row.code===form.deviceType)" :key="type.code" :value="type.code">{{ type.name }}</option>
        </select>
        <small>{{ currentType?.description }}</small>
      </template>
      <template v-if="step===1">
        <div class="form-pair">
          <div><label for="wizard-name">设备名称</label><el-input id="wizard-name" v-model="form.deviceName" maxlength="100" /></div>
          <div><label for="wizard-sn">序列号</label><el-input id="wizard-sn" v-model="form.deviceSn" maxlength="64" :disabled="!!device" /></div>
          <div><label for="wizard-brand">厂商</label><el-input id="wizard-brand" v-model="form.brand" maxlength="80" /></div>
          <div><label for="wizard-model">型号</label><el-input id="wizard-model" v-model="form.model" maxlength="100" /></div>
          <div><label for="wizard-firmware">固件版本</label><el-input id="wizard-firmware" v-model="form.firmwareVersion" maxlength="80" /></div>
          <div><label for="wizard-tags">标签（逗号分隔）</label><el-input id="wizard-tags" v-model="form.tagsText" /></div>
        </div>
        <label for="wizard-remark">备注</label><el-input id="wizard-remark" v-model="form.remark" type="textarea" :rows="2" />
      </template>
      <template v-if="step===2">
        <div class="form-pair">
          <div><label for="wizard-mode">接入来源</label>
            <select id="wizard-mode" v-model="form.accessMode" :disabled="!!device||!!initialMode" @change="chooseMode">
              <option value="real">真实设备</option><option v-if="isIotAdmin" value="simulated">模拟设备</option>
            </select>
          </div>
          <div><label for="wizard-protocol">接入协议</label>
            <select id="wizard-protocol" v-model="form.protocol" :disabled="form.accessMode==='simulated'">
              <option v-if="form.accessMode==='simulated'" value="simulator">后端模拟器</option>
              <template v-else><option v-for="(label,key) in protocols" :key="key" :value="key">{{ label }}</option></template>
            </select>
          </div>
        </div>
        <label>地块{{ fieldRequired?'（必选）':'' }}</label>
        <PatrolFieldSelect v-model="form.fieldId" label="选择所属地块" :clearable="!fieldRequired" />
        <div v-if="form.deviceType==='drone'" class="dialog-form">
          <label for="wizard-dock">关联机库</label>
          <select id="wizard-dock" v-model="form.parentDeviceId">
            <option :value="undefined">未绑定</option>
            <option v-for="dock in compatibleDocks" :key="dock.id" :value="dock.id">{{ dock.deviceName }} · {{ dock.deviceSn }}</option>
          </select>
        </div>
        <label for="wizard-location">安装位置</label><el-input id="wizard-location" v-model="form.installLocation" maxlength="200" />
        <div class="form-pair">
          <div><label for="wizard-lng">经度</label><el-input-number id="wizard-lng" v-model="form.longitude" :min="-180" :max="180" :precision="7" /></div>
          <div><label for="wizard-lat">纬度</label><el-input-number id="wizard-lat" v-model="form.latitude" :min="-90" :max="90" :precision="7" /></div>
        </div>
      </template>
      <template v-if="step===3">
        <label for="wizard-sample">采样间隔（秒）</label><el-input-number id="wizard-sample" v-model="form.sampleIntervalSeconds" :min="5" :max="86400" />
        <fieldset class="iot-checkboxes"><legend>设备支持的指标</legend>
          <label v-for="metric in metrics.filter(row=>row.enabled)" :key="metric.code">
            <input v-model="form.metricCodes" type="checkbox" :value="metric.code" />{{ metric.displayName }}（{{ metric.canonicalUnit }}）
          </label>
        </fieldset>
      </template>
      <template v-if="step===4">
        <dl class="device-facts">
          <div><dt>设备</dt><dd>{{ form.deviceName }} · {{ form.deviceSn }}</dd></div>
          <div><dt>设备类型</dt><dd>{{ currentType?.name }}</dd></div>
          <div><dt>数据来源</dt><dd>{{ form.accessMode==='simulated'?'模拟数据':'真实设备' }}</dd></div>
          <div><dt>能力</dt><dd>{{ form.metricCodes.length }} 项指标</dd></div>
        </dl>
        <small v-if="form.accessMode==='simulated'">登记后可在模拟实验室配置并启动，数据由后端生成。</small>
        <small v-else>登记后状态为待连接，接入凭证和实际硬件协议需在连接时核对。</small>
      </template>
      <p v-if="error" role="alert" class="monitor-error">{{ error }}</p>
      <div class="dialog-actions">
        <el-button v-if="step>0" :disabled="saving" @click="error=''; step--">上一步</el-button>
        <el-button native-type="submit" type="primary" :loading="saving" :disabled="!types.length">{{ step===4?'保存设备':'下一步' }}</el-button>
      </div>
    </form>
  </el-dialog>
</template>

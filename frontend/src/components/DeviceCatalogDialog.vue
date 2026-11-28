<script setup>
import { ref, reactive, watch } from 'vue'
import { getDeviceTypes, getMetricDefinitions, saveDeviceType, saveMetricDefinition } from '../api/iot'
import { requestMessage } from '../api/patrol'

const props=defineProps({ modelValue:Boolean })
const emit=defineEmits(['update:modelValue','saved'])
const tab=ref('type')
const editing=ref(false)
const form=reactive({})
const types=ref([])
const metrics=ref([])
const error=ref('')
const loading=ref(false)
const saving=ref(false)
function choose(row) {
  editing.value=!!row
  const defaults=tab.value==='type' ? { code:'',name:'',category:'other',description:'',
    defaultSampleIntervalSeconds:300,defaultOfflineTimeoutSeconds:900,enabled:true }
    : { code:'',displayName:'',category:'other',canonicalUnit:'',precisionDigits:1,displayOrder:100,description:'',enabled:true }
  for (const key of Object.keys(form)) delete form[key]
  Object.assign(form,defaults,row || {})
  form.fieldRequired=!!row?.capabilitySchema?.fieldRequired
  form.codes=[...(row?.capabilitySchema?.metrics || [])]
  error.value=''
}
watch(tab,()=>choose(null))
watch(()=>props.modelValue,async open=>{
  if(!open) return
  loading.value=true
  choose(null)
  try { [types.value,metrics.value]=await Promise.all([getDeviceTypes(),getMetricDefinitions()]) }
  catch(e){ error.value=requestMessage(e,'目录加载失败') }
  finally{ loading.value=false }
})
async function save() {
  error.value=''
  saving.value=true
  try {
    const { fieldRequired,codes,system,...body }=form
    if (tab.value==='type') await saveDeviceType({...body,capabilitySchema:{...body.capabilitySchema,fieldRequired,metrics:codes}},editing.value)
    else await saveMetricDefinition(body,editing.value)
    ;[types.value,metrics.value]=await Promise.all([getDeviceTypes(),getMetricDefinitions()])
    choose(null)
    emit('saved')
  } catch(e){ error.value=requestMessage(e,'保存目录失败') }
  finally{ saving.value=false }
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="设备类型与指标字典" width="min(760px, 94vw)" top="5vh"
    class="patrol-task-dialog" @update:model-value="emit('update:modelValue',$event)">
    <div class="iot-form-row">
      <label><input v-model="tab" type="radio" value="type">设备类型</label>
      <label><input v-model="tab" type="radio" value="metric">指标字典</label>
    </div>
    <p v-if="loading" role="status" class="monitor-empty">正在加载目录…</p>
    <div v-else class="iot-catalog-layout">
      <div class="iot-catalog-list">
        <el-button size="small" @click="choose(null)">新建{{ tab==='type'?'类型':'指标' }}</el-button>
        <button v-for="row in tab==='type'?types:metrics" :key="row.code" type="button" class="iot-option"
          :aria-pressed="form.code===row.code" @click="choose(row)">
          {{ row.name || row.displayName }} <small>{{ row.code }} · {{ row.enabled?'启用':'停用' }}</small>
        </button>
      </div>
      <form class="dialog-form" @submit.prevent="save">
        <label for="catalog-code">编号</label><el-input id="catalog-code" v-model="form.code" :disabled="editing" />
        <label for="catalog-name">名称</label>
        <el-input v-if="tab==='type'" id="catalog-name" v-model="form.name" />
        <el-input v-else id="catalog-name" v-model="form.displayName" />
        <label for="catalog-category">类别</label><el-input id="catalog-category" v-model="form.category" />
        <template v-if="tab==='type'">
          <label>默认采样间隔（秒）</label><el-input-number v-model="form.defaultSampleIntervalSeconds" :min="5" :max="86400" />
          <label>默认离线阈值（秒）</label><el-input-number v-model="form.defaultOfflineTimeoutSeconds" :min="1" :max="604800" />
          <label><input v-model="form.fieldRequired" type="checkbox">须绑定地块</label>
          <label for="catalog-metrics">建议指标</label>
          <select id="catalog-metrics" v-model="form.codes" multiple size="5">
            <option v-for="metric in metrics.filter(row=>row.enabled)" :key="metric.code" :value="metric.code">{{ metric.displayName }}</option>
          </select>
        </template>
        <template v-else>
          <label for="catalog-unit">统一单位</label><el-input id="catalog-unit" v-model="form.canonicalUnit" />
          <div class="form-pair">
            <div><label>小数位数</label><el-input-number v-model="form.precisionDigits" :min="0" :max="8" /></div>
            <div><label>显示顺序</label><el-input-number v-model="form.displayOrder" /></div>
            <div><label>参考最小值</label><el-input-number v-model="form.recommendedMin" /></div>
            <div><label>参考最大值</label><el-input-number v-model="form.recommendedMax" /></div>
          </div>
        </template>
        <label for="catalog-description">说明</label><el-input id="catalog-description" v-model="form.description" type="textarea" :rows="2" />
        <label><input v-model="form.enabled" type="checkbox">启用</label>
        <p v-if="error" role="alert" class="monitor-error">{{ error }}</p>
        <el-button native-type="submit" type="primary" :loading="saving">保存目录</el-button>
      </form>
    </div>
  </el-dialog>
</template>

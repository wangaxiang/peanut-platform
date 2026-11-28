<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import IotAccessPanel from '../components/IotAccessPanel.vue'
import PatrolFieldSelect from '../components/PatrolFieldSelect.vue'
import SoilChart from '../components/SoilChart.vue'
import { controlSimulator, createSimulator, getMetricDefinitions, getSimulatorPreview, getSimulatorTemplates,
  getSimulators, iotRole, isIotAdmin, saveSimulatorProfile } from '../api/iot'
import { formatTime, requestMessage } from '../api/patrol'

const entries=ref([])
const meta=ref({templates:[],scenarios:{}})
const metrics=ref([])
const active=ref()
const selected=computed(()=>entries.value.find(row=>row.device.id===active.value))
const scenario=ref('normal')
const activeMetric=ref('')
const creating=ref(false)
const loading=ref(true)
const previewLoading=ref(false)
const busy=ref(false)
const error=ref('')
const previewError=ref('')
const formError=ref('')
const preview=ref({points:[],events:[]})
const draft=reactive({})
const profile=reactive({})
const inject=reactive({metricCode:'',value:undefined})
const keepLast=ref(true)
const scenarioOrder=['normal','soil_dry','high_temperature','device_offline','low_battery','sensor_stuck','spike']
const scenarioHelp={
  normal:'围绕基线波动，可配置趋势和昼夜变化。',
  soil_dry:'逐步降低土壤体积含水率。',
  high_temperature:'提高空气或土壤温度。',
  device_offline:'暂停遥测和心跳，等待离线判定。',
  low_battery:'降低电量并继续上报其他指标。',
  sensor_stuck:'让受影响的指标保持恒定。',
  spike:'只在首个采样点注入尖峰，标为可疑。',
}
const stateLabels={running:'模拟运行中',paused:'已暂停',stopped:'已停止'}
const actionLabels={start:'启动',pause:'暂停',resume:'继续',stop:'停止',reset:'重置',inject:'注入异常'}
const availableMetrics=computed(()=>metrics.value.filter(metric=>selected.value?.device.metricCodes.includes(metric.code)))
const displayedMetric=computed(()=>availableMetrics.value.find(metric=>metric.code===activeMetric.value))
const readings=computed(()=>preview.value.points.map(row=>({time:row.time,[activeMetric.value]:row.values[activeMetric.value]??null})))
const chartMetrics=computed(()=>displayedMetric.value?[{key:activeMetric.value,label:displayedMetric.value.displayName,
  unit:displayedMetric.value.canonicalUnit,color:'#267356'}]:[])
const selectedTemplate=computed(()=>meta.value.templates.find(type=>type.code===draft.deviceType))
let timer,listRequest=0,previewRequest=0

async function refresh() {
  const current=++listRequest
  if(!entries.value.length) loading.value=true
  try{
    const [rows,templates,definitions]=await Promise.all([getSimulators(),getSimulatorTemplates(),getMetricDefinitions()])
    if(current!==listRequest) return
    entries.value=rows;meta.value=templates;metrics.value=definitions;error.value=''
    if(!rows.some(row=>row.device.id===active.value)) active.value=rows[0]?.device.id
  }catch(e){if(current===listRequest) error.value=requestMessage(e,'无法读取模拟设备')}
  finally{if(current===listRequest) loading.value=false}
}
async function refreshPreview() {
  const id=active.value
  if(!id) return
  const current=++previewRequest
  previewLoading.value=true
  try {
    const data=await getSimulatorPreview(id)
    if(current===previewRequest){preview.value=data;previewError.value=''}
  }catch(e){if(current===previewRequest) previewError.value=requestMessage(e,'无法读取 InfluxDB 预览')}
  finally{if(current===previewRequest) previewLoading.value=false}
}
function hydrate() {
  const entry=selected.value
  if(!entry) return
  scenario.value=entry.profile.scenario
  const config=entry.profile.config || {}
  Object.assign(profile,{
    sampleIntervalSeconds:entry.profile.sampleIntervalSeconds,randomSeed:entry.profile.randomSeed,
    durationSeconds:config.durationSeconds??undefined,amplitude:config.amplitude??undefined,
    recoverToNormal:config.recoverToNormal??false,impactMetric:config.impactMetric??'',
    metrics:Object.fromEntries(entry.device.metricCodes.map(code=>[code,{...(config.metrics?.[code]||{})}])),
  })
  activeMetric.value=entry.device.metricCodes[0]||''
  inject.metricCode=activeMetric.value;inject.value=undefined
}
watch(active,()=>{preview.value={points:[],events:[]};hydrate();refreshPreview()})
watch(iotRole,refresh)
onMounted(()=>{
  refresh()
  timer=setInterval(()=>{if(!document.hidden&&!busy.value){refresh();refreshPreview()}},3000)
})
onBeforeUnmount(()=>{clearInterval(timer);listRequest++;previewRequest++})

function openCreate(type) {
  Object.assign(draft,{deviceType:type?.code||meta.value.templates[0]?.code,deviceName:'',fieldId:undefined,
    sampleIntervalSeconds:60,randomSeed:20261128,installLocation:''})
  formError.value='';creating.value=true
}
async function add() {
  if(!draft.deviceName?.trim() || !draft.deviceType){formError.value='请填写模拟设备名称并选择模板';return}
  if(selectedTemplate.value?.capabilitySchema.fieldRequired&&!draft.fieldId){formError.value='请选择地块';return}
  busy.value=true
  formError.value=''
  try {
    const entry=await createSimulator({deviceType:draft.deviceType,deviceName:draft.deviceName,fieldId:draft.fieldId,
      installLocation:draft.installLocation,settings:{sampleIntervalSeconds:draft.sampleIntervalSeconds,randomSeed:draft.randomSeed}})
    entries.value=[entry,...entries.value];active.value=entry.device.id;creating.value=false
    ElMessage.success('模拟设备已登记');await refresh()
  }catch(e){formError.value=requestMessage(e,'创建模拟设备失败')}
  finally{busy.value=false}
}
async function config() {
  busy.value=true
  try{
    await saveSimulatorProfile(active.value,{...profile,impactMetric:profile.impactMetric||null})
    await refresh()
    ElMessage.success('模拟配置已保存')
  }catch(e){ElMessage.error(requestMessage(e,'配置保存失败'))}
  finally{busy.value=false}
}
async function act(action) {
  if(action==='reset'){
    try{await ElMessageBox.confirm('重置运行状态和采样序列，已写入的历史遥测会保留。','重置模拟',{type:'warning'})}catch{return}
  }
  busy.value=true
  try {
    const body=action==='start'?{scenario:scenario.value}:action==='stop'?{keepLastOnline:keepLast.value}
      :action==='inject'?{...inject}:{}
    const entry=await controlSimulator(active.value,action,body)
    entries.value=entries.value.map(row=>row.device.id===entry.device.id?entry:row)
    if(action==='reset') hydrate()
    await refreshPreview()
    ElMessage.success(actionLabels[action]+'成功')
  }catch(e){ElMessage.error(requestMessage(e,'模拟操作失败'))}
  finally{busy.value=false}
}
</script>

<template>
  <main class="monitor-page iot-page">
    <header class="iot-heading">
      <div><h1>模拟设备实验室</h1><p>使用后端模拟设备验证采样、在线状态和遥测数据。</p></div>
      <IotAccessPanel />
    </header>
    <p v-if="error" class="monitor-error" role="alert">{{ error }} <button class="text-button" @click="refresh">重试</button></p>
    <div v-if="loading&&!entries.length&&!error" class="iot-skeleton" role="status" aria-label="正在加载模拟设备"><span/><span/><span/></div>
    <div class="iot-lab-layout">
      <section class="monitor-panel iot-lab-pane" aria-label="场景预设">
        <h2>场景预设</h2>
        <button v-for="code in scenarioOrder.filter(key=>meta.scenarios[key])" :key="code" type="button"
          class="iot-option" :aria-pressed="scenario===code" @click="scenario=code">
          {{ meta.scenarios[code] }}<small>{{ scenarioHelp[code] }}</small>
        </button>
      </section>
      <section class="monitor-panel iot-lab-pane" aria-label="模拟设备">
        <div class="iot-lab-section-heading"><h2>模拟设备</h2>
          <el-button type="primary" size="small" :disabled="!isIotAdmin||!meta.templates.length" @click="openCreate()">创建模拟设备</el-button>
        </div>
        <p v-if="!entries.length&&!loading" class="monitor-muted">暂无模拟设备，创建一台设备后即可启动采样。</p>
        <button v-for="row in entries" :key="row.device.id" type="button" class="iot-option" :aria-pressed="active===row.device.id" @click="active=row.device.id">
          {{ row.device.deviceName }} <span class="iot-sim-badge">模拟数据</span>
          <small>{{ row.device.deviceSn }} · {{ stateLabels[row.profile.status] }}</small>
        </button>
      </section>
      <section class="monitor-panel iot-lab-pane iot-lab-monitor" aria-label="模拟运行和遥测">
        <template v-if="selected">
          <div class="iot-lab-section-heading">
            <div><h2>{{ selected.device.deviceName }}</h2><p class="monitor-muted">{{ stateLabels[selected.profile.status] }} · {{ selected.profile.sampleIntervalSeconds }} 秒采样</p></div>
            <span class="iot-sim-badge">模拟数据</span>
          </div>
          <div class="iot-actions iot-lab-actions">
            <el-button type="primary" :disabled="!isIotAdmin||busy||selected.device.status!=='active'" @click="act('start')">启动场景</el-button>
            <el-button :disabled="!isIotAdmin||busy||selected.profile.status!=='running'" @click="act('pause')">暂停</el-button>
            <el-button :disabled="!isIotAdmin||busy||selected.profile.status!=='paused'" @click="act('resume')">继续</el-button>
            <el-button :disabled="!isIotAdmin||busy||selected.profile.status==='stopped'" @click="act('stop')">停止</el-button>
            <el-button :disabled="!isIotAdmin||busy||selected.device.status!=='active'" @click="act('reset')">重置</el-button>
          </div>
          <div class="iot-form-row"><label><input v-model="keepLast" type="checkbox">停止后保留最后在线状态，按阈值下线</label>
            <span class="monitor-muted">最近采样 {{ formatTime(selected.profile.lastGeneratedAt) }}</span>
          </div>
          <details v-if="isIotAdmin" class="iot-profile">
            <summary>采样与场景配置</summary>
            <form class="dialog-form" @submit.prevent="config">
              <div class="form-pair">
                <div><label for="sim-interval">采样周期（秒）</label><el-input-number id="sim-interval" v-model="profile.sampleIntervalSeconds" :min="5" :max="86400" /></div>
                <div><label for="sim-seed">随机 seed</label><el-input-number id="sim-seed" v-model="profile.randomSeed" :min="0" :max="9007199254740991" /></div>
                <div><label>持续时间（秒，空为不限）</label><el-input-number v-model="profile.durationSeconds" :min="0" :max="604800" /></div>
                <div><label>场景幅度</label><el-input-number v-model="profile.amplitude" :min="0" /></div>
              </div>
              <label for="sim-impact">受影响指标</label>
              <select id="sim-impact" v-model="profile.impactMetric"><option value="">按场景默认</option>
                <option v-for="metric in availableMetrics" :key="metric.code" :value="metric.code">{{ metric.displayName }}</option></select>
              <label><input v-model="profile.recoverToNormal" type="checkbox">到时恢复正常场景</label>
              <div v-if="displayedMetric&&profile.metrics[activeMetric]" class="form-pair">
                <div><label>{{ displayedMetric.displayName }}基线</label><el-input-number v-model="profile.metrics[activeMetric].baseline" /></div>
                <div><label>波动</label><el-input-number v-model="profile.metrics[activeMetric].noise" :min="0" /></div>
                <div><label>每次采样趋势</label><el-input-number v-model="profile.metrics[activeMetric].trend" /></div>
                <div><label>昼夜振幅</label><el-input-number v-model="profile.metrics[activeMetric].dailyAmplitude" :min="0" /></div>
              </div>
              <div class="dialog-actions"><el-button type="primary" native-type="submit" :loading="busy">保存配置</el-button></div>
            </form>
          </details>
          <p v-if="previewError" class="monitor-error" role="alert">{{ previewError }} <button class="text-button" @click="refreshPreview">重试</button></p>
          <div class="iot-lab-section-heading iot-preview-heading">
            <h2>最近遥测</h2>
            <select v-model="activeMetric" aria-label="预览指标"><option v-for="metric in availableMetrics" :key="metric.code" :value="metric.code">{{ metric.displayName }}</option></select>
          </div>
          <p v-if="!readings.length&&!previewError" class="monitor-empty">{{ previewLoading?'读取最近遥测…':'暂无采样。启动模拟后，数据写入 InfluxDB 才会显示。' }}</p>
          <SoilChart v-if="readings.some(row=>row[activeMetric]!=null)&&chartMetrics.length" :key="activeMetric"
            :readings="readings" :metrics="chartMetrics" :label="displayedMetric.displayName+'模拟趋势'" />
          <div v-if="preview.points.length" class="iot-sample-log">
            <div v-for="point in preview.points.slice(-5).reverse()" :key="point.time">
              <time>{{ formatTime(point.time) }}</time>
              <span>{{ point.values[activeMetric]??'暂无该指标' }} {{ point.values[activeMetric]==null?'':displayedMetric?.canonicalUnit }}</span>
              <small>{{ point.quality[activeMetric]==='suspect'?'可疑':point.quality[activeMetric]==='bad'?'异常':'模拟数据' }}</small>
            </div>
          </div>
          <form v-if="isIotAdmin" class="iot-form-row iot-injection" @submit.prevent="act('inject')">
            <select v-model="inject.metricCode" aria-label="注入指标"><option v-for="metric in availableMetrics" :key="metric.code" :value="metric.code">{{ metric.displayName }}</option></select>
            <el-input-number v-model="inject.value" aria-label="异常值" />
            <el-button native-type="submit" :disabled="busy||!inject.metricCode||inject.value==null">注入异常</el-button>
          </form>
          <details v-if="preview.events.length"><summary>最近操作</summary>
            <ul class="iot-event-list"><li v-for="event in preview.events" :key="event.time+event.eventType">{{ formatTime(event.time) }} · {{ actionLabels[event.eventType.replace('simulator_','')]||event.summary }}</li></ul>
          </details>
        </template>
        <p v-else class="monitor-empty">选择模拟设备以查看配置和遥测。</p>
      </section>
    </div>
    <el-dialog v-model="creating" title="创建模拟设备" width="min(550px, 94vw)" top="6vh">
      <form class="dialog-form" @submit.prevent="add">
        <label for="sim-template">设备模板</label>
        <select id="sim-template" v-model="draft.deviceType"><option v-for="type in meta.templates" :key="type.code" :value="type.code">{{ type.name }}</option></select>
        <label for="sim-name">设备名称</label><el-input id="sim-name" v-model="draft.deviceName" maxlength="100" />
        <label>所属地块{{ selectedTemplate?.capabilitySchema.fieldRequired?'（必选）':'' }}</label>
        <PatrolFieldSelect v-if="creating" v-model="draft.fieldId" label="选择所属地块" />
        <label for="sim-location">安装位置</label><el-input id="sim-location" v-model="draft.installLocation" maxlength="200" />
        <label for="sim-create-interval">采样周期（秒）</label><el-input-number id="sim-create-interval" v-model="draft.sampleIntervalSeconds" :min="5" :max="86400" />
        <p v-if="formError" class="monitor-error" role="alert">{{ formError }}</p>
        <div class="dialog-actions"><el-button native-type="submit" type="primary" :loading="busy">登记模拟设备</el-button></div>
      </form>
    </el-dialog>
  </main>
</template>

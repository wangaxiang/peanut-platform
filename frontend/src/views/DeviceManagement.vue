<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PatrolFieldSelect from '../components/PatrolFieldSelect.vue'
import DeviceWizard from '../components/DeviceWizard.vue'
import IotAccessPanel from '../components/IotAccessPanel.vue'
import DeviceGroupDialog from '../components/DeviceGroupDialog.vue'
import DeviceCsvDialog from '../components/DeviceCsvDialog.vue'
import DeviceCatalogDialog from '../components/DeviceCatalogDialog.vue'
import { changeDeviceStatus, deviceStatuses, getDevices, getDeviceStats, retireDevice } from '../api/devices'
import { canEditIot, exportDevicesCsv, getDeviceTypes, getGroups, iotRole, isIotAdmin, setGroupMembers } from '../api/iot'
import { formatTime, requestMessage } from '../api/patrol'

const filters=reactive({ keyword:'',deviceType:'',fieldId:undefined,online:'',status:'',source:'',groupId:undefined,sort:'createdAt,desc' })
const devices=ref([])
const types=ref([])
const groups=ref([])
const stats=ref(null)
const count=ref(0)
const page=ref(1)
const loading=ref(true)
const error=ref('')
const wizard=ref(false)
const edit=ref(null)
const groupOpen=ref(false)
const csvOpen=ref(false)
const catalogOpen=ref(false)
const chosen=ref([])
const groupTarget=ref()
const busy=ref(false)
const advanced=ref(false)
const showTelemetry=ref(true)
let request=0,timer,debounce
function parameters() {
  return Object.fromEntries(Object.entries(filters).filter(([,value])=>value!=='' && value!=null))
}
async function refresh() {
  const current=++request
  loading.value=true
  try {
    const [list,totals]=await Promise.all([getDevices({...parameters(),page:page.value-1,size:20}),getDeviceStats()])
    if(current!==request) return
    devices.value=list.content
    count.value=list.totalElements
    stats.value=totals
    error.value=''
  }catch(e){if(current===request) error.value=requestMessage(e,'读取设备台账失败')}
  finally{if(current===request) loading.value=false}
}
async function options() {
  try { [types.value,groups.value]=await Promise.all([getDeviceTypes(),getGroups()]) }
  catch(e){error.value=requestMessage(e,'读取设备类型或分组失败')}
}
function open(row=null){ edit.value=row;wizard.value=true }
function permitted(row){ return canEditIot.value&&(row.accessMode!=='simulated'||isIotAdmin.value) }
async function change(row,status) {
  busy.value=true
  try{await changeDeviceStatus(row.id,status);await refresh();ElMessage.success('设备状态已更新')}
  catch(e){ElMessage.error(requestMessage(e,'更新设备状态失败'))}
  finally{busy.value=false}
}
async function retire(row) {
  try{await ElMessageBox.confirm(`退役「${row.deviceName}」并保留历史记录？`,'退役设备',
    {confirmButtonText:'确认退役',cancelButtonText:'取消',type:'warning'})}catch{return}
  busy.value=true
  try{await retireDevice(row.id);await refresh();ElMessage.success('设备已退役')}
  catch(e){ElMessage.error(requestMessage(e,'设备退役失败'))}
  finally{busy.value=false}
}
async function exportCsv() {
  busy.value=true
  try{
    const {data}=await exportDevicesCsv(parameters())
    const url=URL.createObjectURL(data)
    const anchor=document.createElement('a')
    anchor.href=url;anchor.download='patrol-devices.csv';anchor.click()
    setTimeout(()=>URL.revokeObjectURL(url),1000)
  }catch(e){ElMessage.error(requestMessage(e,'导出失败'))}
  finally{busy.value=false}
}
async function bulkGroup() {
  const group=groups.value.find(item=>item.id===groupTarget.value)
  if(!group) return
  busy.value=true
  try{
    await setGroupMembers(group.id,[...new Set([...group.deviceIds,...chosen.value])])
    chosen.value=[];await options();await refresh();ElMessage.success('已加入设备分组')
  }catch(e){ElMessage.error(requestMessage(e,'调整分组失败'))}
  finally{busy.value=false}
}
watch(filters,()=>{
  clearTimeout(debounce)
  debounce=setTimeout(()=>{chosen.value=[];if(page.value===1) refresh();else page.value=1},200)
})
watch(page,()=>{chosen.value=[];refresh()})
watch(iotRole,()=>{options();refresh()})
onMounted(()=>{
  options();refresh()
  timer=setInterval(()=>{if(!document.hidden&&!loading.value&&!wizard.value) refresh()},10000)
})
onBeforeUnmount(()=>{clearInterval(timer);clearTimeout(debounce);request++})
</script>

<template>
  <main class="monitor-page iot-page">
    <header class="iot-heading">
      <div><h1>设备管理</h1><p>登记田间设备，管理指标能力、分组与连接状态。</p></div>
      <IotAccessPanel />
    </header>
    <dl class="device-stat-grid">
      <div v-for="(label,key) in { total:'设备总数',online:'在线',offline:'离线',maintenance:'维护中' }" :key="key" class="monitor-panel">
        <dt>{{ label }}</dt><dd>{{ stats?.[key]??'—' }}</dd>
      </div>
    </dl>
    <section class="monitor-panel iot-registry">
      <div class="monitor-toolbar iot-toolbar">
        <h2>设备台账</h2>
        <div class="iot-actions">
          <el-button v-if="isIotAdmin" @click="catalogOpen=true">类型与指标</el-button>
          <el-button v-if="isIotAdmin" @click="groupOpen=true">管理分组</el-button>
          <el-button v-if="canEditIot" @click="csvOpen=true">批量导入</el-button>
          <el-button v-if="canEditIot" :disabled="busy" @click="exportCsv">导出 CSV</el-button>
          <el-button type="primary" :disabled="!canEditIot" @click="open()">添加设备</el-button>
        </div>
      </div>
      <div class="iot-filters">
        <el-input v-model="filters.keyword" aria-label="搜索设备" placeholder="按名称或序列号搜索" clearable />
        <select v-model="filters.deviceType" aria-label="设备类型">
          <option value="">全部类型</option><option v-for="type in types" :key="type.code" :value="type.code">{{ type.name }}</option>
        </select>
        <PatrolFieldSelect v-model="filters.fieldId" />
        <button class="iot-filter-button" type="button" :aria-expanded="advanced" @click="advanced=!advanced">更多筛选</button>
        <label class="iot-column-toggle"><input v-model="showTelemetry" type="checkbox">电量与信号</label>
      </div>
      <div v-if="advanced" class="iot-filters iot-advanced">
        <select v-model="filters.online" aria-label="在线状态"><option value="">全部在线状态</option><option :value="true">在线</option><option :value="false">离线</option></select>
        <select v-model="filters.status" aria-label="业务状态"><option value="">全部业务状态</option>
          <option v-for="(label,key) in deviceStatuses" :value="key" :key="key">{{ label }}</option>
        </select>
        <select v-model="filters.source" aria-label="数据来源"><option value="">全部来源</option><option value="real">真实设备</option><option value="simulated">模拟数据</option></select>
        <select v-model="filters.groupId" aria-label="筛选分组"><option :value="undefined">全部分组</option>
          <option v-for="group in groups" :key="group.id" :value="group.id">{{ group.groupName }}</option></select>
        <select v-model="filters.sort" aria-label="排序"><option value="createdAt,desc">最新登记</option>
          <option value="deviceName,asc">设备名称</option><option value="lastSeenAt,desc">最近上报</option></select>
      </div>
      <div v-if="chosen.length" class="iot-filters">
        <span>已选 {{ chosen.length }} 台</span>
        <select v-model="groupTarget" aria-label="目标分组"><option :value="undefined">选择目标分组</option>
          <option v-for="group in groups" :key="group.id" :value="group.id">{{ group.groupName }}</option>
        </select>
        <el-button :disabled="busy||!groupTarget" @click="bulkGroup">加入分组</el-button>
      </div>
      <p v-if="error" role="alert" class="monitor-error">{{ error }} <button class="text-button" @click="options();refresh()">重试</button></p>
      <div v-if="loading&&!devices.length&&!error" class="iot-skeleton" role="status" aria-label="正在加载设备">
        <span v-for="n in 4" :key="n"></span>
      </div>
      <p v-else-if="!devices.length&&!error" class="monitor-empty">暂无符合条件的设备。可调整筛选，或添加设备。</p>
      <div v-if="devices.length" class="monitor-table-wrap" :aria-busy="loading">
        <table class="monitor-table iot-table">
          <thead><tr><th v-if="isIotAdmin">选择</th><th>设备</th><th>类型与来源</th><th>地块／安装点</th>
            <th>连接状态</th><th v-if="showTelemetry">电量／信号</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in devices" :key="row.id">
              <td v-if="isIotAdmin" data-label="选择"><input v-model="chosen" type="checkbox" :value="row.id" :aria-label="'选择'+row.deviceName"></td>
              <td data-label="设备"><a :href="'#/patrol/devices/'+row.id">{{ row.deviceName }}</a><small>{{ row.deviceSn }}</small></td>
              <td data-label="类型与来源">{{ row.deviceTypeName || row.deviceType }}
                <small v-if="row.accessMode==='simulated'" class="iot-sim-badge">模拟数据</small>
                <small v-else>真实设备</small>
              </td>
              <td data-label="地块／安装点">{{ row.fieldName||'未绑定' }}<small v-if="row.installLocation">{{ row.installLocation }}</small>
                <small v-if="row.parentDeviceName">机库 {{ row.parentDeviceName }}</small></td>
              <td data-label="连接状态"><span class="flight-tag" :class="{ done:row.online }">{{ row.online?(row.accessMode==='simulated'?'模拟上报中':'在线'):(row.lastSeenAt?'离线':'待连接') }}</span>
                <small>{{ deviceStatuses[row.status] }} · {{ formatTime(row.lastSeenAt) }}</small></td>
              <td v-if="showTelemetry" data-label="电量／信号">{{ row.batteryPct==null?'—':row.batteryPct+'%' }} ／ {{ row.signalStrengthPct==null?'—':row.signalStrengthPct+'%' }}</td>
              <td data-label="操作"><div v-if="row.status!=='retired'&&permitted(row)" class="device-row-actions">
                <el-button size="small" :disabled="busy" @click="open(row)">编辑</el-button>
                <el-button size="small" :disabled="busy" @click="change(row,row.status==='active'?'inactive':'active')">{{ row.status==='active'?'停用':'启用' }}</el-button>
                <el-button v-if="row.status!=='maintenance'" size="small" :disabled="busy" @click="change(row,'maintenance')">标为维护中</el-button>
                <el-button v-if="isIotAdmin" size="small" :disabled="busy" @click="retire(row)">退役</el-button>
              </div><span v-else>—</span></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="count>20" class="monitor-footer"><el-pagination v-model:current-page="page" :page-size="20" :total="count" layout="prev, pager, next" /></div>
    </section>
    <DeviceWizard v-model="wizard" :device="edit" @saved="refresh" />
    <DeviceGroupDialog v-model="groupOpen" @saved="options();refresh()" />
    <DeviceCsvDialog v-model="csvOpen" @saved="refresh" />
    <DeviceCatalogDialog v-model="catalogOpen" @saved="options();refresh()" />
  </main>
</template>

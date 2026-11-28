<script setup>
import { ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { getDevices } from '../api/devices'
import { getGroups, removeGroup, saveGroup, setGroupMembers } from '../api/iot'
import { requestMessage } from '../api/patrol'
const props=defineProps({ modelValue:Boolean })
const emit=defineEmits(['update:modelValue','saved'])
const groups=ref([])
const options=ref([])
const form=ref({})
const keyword=ref('')
const error=ref('')
const loading=ref(false)
const busy=ref(false)
let request=0
function select(row) { form.value={id:row?.id,groupName:row?.groupName||'',description:row?.description||'',deviceIds:[...(row?.deviceIds||[])]}; error.value='' }
async function search() {
  const current=++request
  try { const result=await getDevices({keyword:keyword.value,size:100}); if(current===request) options.value=result.content }
  catch(e){if(current===request) error.value=requestMessage(e,'设备查询失败')}
}
watch(()=>props.modelValue,async open=>{
  if(!open) return
  select(null)
  loading.value=true
  try { [groups.value]=await Promise.all([getGroups(),search()]) }
  catch(e){error.value=requestMessage(e,'分组加载失败')}
  finally{loading.value=false}
})
async function save(){
  busy.value=true
  error.value=''
  try{
    const row=await saveGroup(form.value.id,{groupName:form.value.groupName,description:form.value.description})
    form.value.id=row.id
    const result=await setGroupMembers(row.id,form.value.deviceIds)
    groups.value=await getGroups()
    select(result)
    emit('saved')
  }catch(e){error.value=requestMessage(e,'分组保存失败')}
  finally{busy.value=false}
}
async function remove(){
  try{ await ElMessageBox.confirm('移除此分组后，成员设备和历史记录仍会保留。','删除分组',{ confirmButtonText:'删除',cancelButtonText:'取消' }) }catch{return}
  busy.value=true
  try{await removeGroup(form.value.id); groups.value=await getGroups(); select(null); emit('saved')}
  catch(e){error.value=requestMessage(e,'分组删除失败')}
  finally{busy.value=false}
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="设备分组" width="min(720px, 94vw)" top="5vh" class="patrol-task-dialog"
    @update:model-value="emit('update:modelValue',$event)">
    <p v-if="loading" role="status" class="monitor-empty">正在加载分组…</p>
    <div v-else class="iot-catalog-layout">
      <div class="iot-catalog-list">
        <el-button size="small" @click="select(null)">新建分组</el-button>
        <button v-for="group in groups" :key="group.id" type="button" class="iot-option" :aria-pressed="form.id===group.id"
          @click="select(group)">{{ group.groupName }} <small>{{ group.deviceIds.length }} 台设备</small></button>
      </div>
      <form class="dialog-form" @submit.prevent="save">
        <label for="group-name">分组名称</label><el-input id="group-name" v-model="form.groupName" maxlength="100" />
        <label for="group-description">说明</label><el-input id="group-description" v-model="form.description" />
        <label for="group-search">搜索成员设备</label>
        <div class="iot-form-row"><el-input id="group-search" v-model="keyword" @keyup.enter.prevent="search" /><el-button @click="search">搜索</el-button></div>
        <fieldset class="iot-checkboxes"><legend>已选择 {{ form.deviceIds?.length || 0 }} 台</legend>
          <label v-for="row in options" :key="row.id">
            <input v-model="form.deviceIds" type="checkbox" :value="row.id">{{ row.deviceName }} · {{ row.deviceSn }}
          </label>
          <small v-if="!options.length">暂无可选择设备</small>
        </fieldset>
        <p v-if="error" role="alert" class="monitor-error">{{ error }}</p>
        <div class="dialog-actions">
          <el-button v-if="form.id" :disabled="busy" @click="remove">删除分组</el-button>
          <el-button native-type="submit" type="primary" :loading="busy" :disabled="!form.groupName?.trim()">保存分组</el-button>
        </div>
      </form>
    </div>
  </el-dialog>
</template>

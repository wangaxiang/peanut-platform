<script setup>
import { ref, watch } from 'vue'
import { previewDevicesCsv, executeDevicesCsv } from '../api/iot'
import { requestMessage } from '../api/patrol'
const props=defineProps({ modelValue:Boolean })
const emit=defineEmits(['update:modelValue','saved'])
const file=ref(null)
const job=ref(null)
const error=ref('')
const busy=ref(false)
watch(()=>props.modelValue,open=>{if(open){file.value=null;job.value=null;error.value=''}})
async function action(){
  busy.value=true
  error.value=''
  try {
    job.value=job.value ? await executeDevicesCsv(job.value.jobId) : await previewDevicesCsv(file.value)
    if(job.value.importedCount!=null) emit('saved')
  }catch(e){error.value=requestMessage(e,'CSV 处理失败')}
  finally{busy.value=false}
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="批量导入设备" width="min(610px, 94vw)" top="7vh"
    class="patrol-task-dialog" @update:model-value="emit('update:modelValue',$event)">
    <form class="dialog-form" @submit.prevent="action">
      <p>选择 UTF-8 CSV，先预览校验结果，再确认写入。</p>
      <small>必填列为 deviceType、deviceSn、deviceName。绑定地块用 fieldId，指标编号用 | 分隔。</small>
      <label for="iot-csv">CSV 文件</label>
      <input id="iot-csv" type="file" accept=".csv,text/csv" @change="file=$event.target.files[0]; job=null; error=''" />
      <template v-if="job">
        <p role="status">{{ job.importedCount!=null ? '已导入 '+job.importedCount+' 台设备' : '通过 '+job.rows.length+' 行，错误 '+job.errors.length+' 行' }}</p>
        <ul v-if="job.errors.length" class="iot-import-rows">
          <li v-for="issue in job.errors" :key="issue.row">第 {{ issue.row }} 行 · {{ issue.message }}</li>
        </ul>
        <ul v-else class="iot-import-rows">
          <li v-for="row in job.rows.slice(0,20)" :key="row.row">{{ row.row }} · {{ row.device.deviceName }} · {{ row.device.deviceSn }}</li>
        </ul>
      </template>
      <p v-if="error" role="alert" class="monitor-error">{{ error }}</p>
      <div class="dialog-actions"><el-button native-type="submit" type="primary" :loading="busy"
        :disabled="!file||!!job?.errors.length||job?.importedCount!=null">{{ job?'确认导入':'预览校验' }}</el-button></div>
    </form>
  </el-dialog>
</template>

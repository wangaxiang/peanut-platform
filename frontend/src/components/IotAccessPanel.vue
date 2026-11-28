<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { iotRole, iotSession } from '../api/iot'
import { requestMessage } from '../api/patrol'

const open=ref(false)
const token=ref('')
const error=ref('')
const waiting=ref(false)
const roleLabels={ VIEWER:'只读访问', OPERATOR:'运维权限', ADMIN:'管理员权限' }
onMounted(() => iotSession().catch(() => {}))
async function apply() {
  error.value=''
  waiting.value=true
  try {
    await iotSession(token.value)
    token.value=''
    open.value=false
    ElMessage.success('设备访问权限已更新')
  } catch (e) { error.value=requestMessage(e,'无法验证访问令牌') }
  finally { waiting.value=false }
}
</script>

<template>
  <div class="iot-access">
    <span>{{ roleLabels[iotRole] || '需要访问权限' }}</span>
    <el-button size="small" @click="error=''; open=true">访问权限</el-button>
    <el-dialog v-model="open" title="设备访问权限" width="min(430px, 92vw)">
      <form class="dialog-form" @submit.prevent="apply">
        <label for="iot-token">访问令牌</label>
        <el-input id="iot-token" v-model="token" type="password" autocomplete="off" />
        <small>由平台管理员提供。留空可切换到本机只读访问。</small>
        <p v-if="error" role="alert" class="monitor-error">{{ error }}</p>
        <div class="dialog-actions"><el-button native-type="submit" type="primary" :loading="waiting">应用权限</el-button></div>
      </form>
    </el-dialog>
  </div>
</template>

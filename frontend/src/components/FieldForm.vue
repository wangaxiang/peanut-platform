<script setup>
import { reactive, watch } from 'vue'

const props = defineProps({ modelValue: Boolean, field: Object, saving: Boolean, error: String })
const emit = defineEmits(['update:modelValue', 'submit'])
const soils = ['沙壤土', '壤土', '黏壤土', '砂土']
const values = reactive({ name: '', soilType: '', variety: '', sowDate: '' })

watch(() => props.modelValue, open => {
  if (!open) return
  for (const key of Object.keys(values)) values[key] = props.field?.[key] ?? ''
})

function submit() {
  if (!values.name.trim()) return
  emit('submit', {
    name: values.name.trim(),
    soilType: values.soilType || null,
    variety: values.variety.trim() || null,
    sowDate: values.sowDate || null,
  })
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="field ? '编辑地块' : '登记地块'"
    width="min(480px, calc(100vw - 32px))" :close-on-click-modal="false"
    :close-on-press-escape="!saving" :show-close="!saving" append-to-body
    @update:model-value="emit('update:modelValue', $event)">
    <form class="dialog-form" @submit.prevent="submit">
      <label for="field-name">地块名称 <span class="required">*</span></label>
      <el-input id="field-name" v-model="values.name" maxlength="100" placeholder="例如：东区 3 号田" />
      <label for="field-soil">土壤类型</label>
      <el-select id="field-soil" v-model="values.soilType" placeholder="选择土壤类型" clearable>
        <el-option v-for="soil in soils" :key="soil" :label="soil" :value="soil" />
        <el-option v-if="values.soilType && !soils.includes(values.soilType)"
          :label="values.soilType" :value="values.soilType" />
      </el-select>
      <label for="field-variety">花生品种</label>
      <el-input id="field-variety" v-model="values.variety" maxlength="100" placeholder="例如：花育 25 号" />
      <label for="field-sow-date">播种日期</label>
      <el-date-picker id="field-sow-date" v-model="values.sowDate" type="date" editable clearable
        value-format="YYYY-MM-DD" format="YYYY-MM-DD" placeholder="选择播种日期" />
      <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
      <div class="dialog-actions">
        <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
        <el-button native-type="submit" type="primary" :loading="saving" :disabled="!values.name.trim()">保存地块</el-button>
      </div>
    </form>
  </el-dialog>
</template>

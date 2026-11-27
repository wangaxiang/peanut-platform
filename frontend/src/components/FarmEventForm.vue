<script setup>
import { computed, reactive, watch } from 'vue'

const props = defineProps({ modelValue: Boolean, saving: Boolean, error: String })
const emit = defineEmits(['update:modelValue', 'submit'])
const types = [
  { value: 'sow', label: '播种', unit: 'kg' },
  { value: 'fertilize', label: '施肥', unit: 'kg' },
  { value: 'spray', label: '打药', unit: 'L' },
  { value: 'harvest', label: '收获', unit: 'kg' },
  { value: 'irrigate', label: '灌溉', unit: 'm³' },
]
const values = reactive({ eventType: '', eventDate: '', amount: null, unit: '', remark: '' })
const hasAmount = computed(() => values.amount !== undefined && values.amount !== null)
watch(() => props.modelValue, open => {
  if (!open) return
  const now = new Date()
  const date = new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
  Object.assign(values, { eventType: '', eventDate: date, amount: null, unit: '', remark: '' })
})

function selectType(type) {
  values.unit = types.find(item => item.value === type)?.unit ?? ''
}

function submit() {
  if (!values.eventType || !values.eventDate || (hasAmount.value && !values.unit.trim())) return
  emit('submit', {
    eventType: values.eventType,
    eventDate: values.eventDate,
    amount: hasAmount.value ? values.amount : null,
    unit: hasAmount.value ? values.unit.trim() : null,
    remark: values.remark.trim() || null,
  })
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="记录农事" width="min(480px, calc(100vw - 32px))"
    :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" append-to-body
    @update:model-value="emit('update:modelValue', $event)">
    <form class="dialog-form" @submit.prevent="submit">
      <label for="event-type">事件类型 <span class="required">*</span></label>
      <el-select id="event-type" v-model="values.eventType" placeholder="选择事件类型" @change="selectType">
        <el-option v-for="type in types" :key="type.value" :label="type.label" :value="type.value" />
      </el-select>
      <label for="event-date">农事日期 <span class="required">*</span></label>
      <el-date-picker id="event-date" v-model="values.eventDate" type="date" value-format="YYYY-MM-DD"
        format="YYYY-MM-DD" placeholder="选择日期" />
      <div class="form-pair">
        <div>
          <label for="event-amount">用量</label>
          <el-input-number id="event-amount" v-model="values.amount" :min="0" :max="99999999.99"
            :precision="2" :step="0.01" :controls="false" placeholder="输入数值" />
        </div>
        <div>
          <label for="event-unit">单位</label>
          <el-input id="event-unit" v-model="values.unit" maxlength="20" placeholder="例如：kg" />
        </div>
      </div>
      <label for="event-remark">备注</label>
      <el-input id="event-remark" v-model="values.remark" type="textarea" :rows="2" />
      <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
      <div class="dialog-actions">
        <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
        <el-button native-type="submit" type="primary" :loading="saving"
          :disabled="!values.eventType || !values.eventDate || (hasAmount && !values.unit.trim())">保存记录</el-button>
      </div>
    </form>
  </el-dialog>
</template>

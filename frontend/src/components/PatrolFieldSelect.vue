<script setup>
import { onMounted, ref, watch } from 'vue'
import { getField, getFields } from '../api/fields'

const props = defineProps({
  modelValue: Number,
  label: { type: String, default: '全部地块' },
  clearable: { type: Boolean, default: true },
})
const emit = defineEmits(['update:modelValue'])
const options = ref([])
const selected = ref(null)
const loading = ref(false)
const failed = ref(false)
let request = 0

async function search(keyword = '') {
  const current = ++request
  loading.value = true
  failed.value = false
  try {
    const result = await getFields({ keyword, size: 50 })
    if (current === request) options.value = result.content
  } catch {
    if (current === request) failed.value = true
  } finally {
    if (current === request) loading.value = false
  }
}

function select(value) {
  selected.value = options.value.find(field => field.id === value) ?? null
  emit('update:modelValue', value || undefined)
}

onMounted(search)
watch(() => props.modelValue, async id => {
  if (!id || options.value.some(field => field.id === id) || selected.value?.id === id) return
  try {
    const field = await getField(id)
    if (props.modelValue === id) selected.value = field
  } catch {
    if (props.modelValue === id) failed.value = true
  }
}, { immediate: true })
</script>

<template>
  <el-select :model-value="modelValue" :aria-label="label" :placeholder="label"
    filterable remote :remote-method="search" :clearable="clearable" :loading="loading"
    :no-data-text="failed ? '地块加载失败，请重新搜索' : '暂无匹配地块'" @update:model-value="select">
    <el-option v-if="selected && selected.id === modelValue && !options.some(field => field.id === selected.id)"
      :label="selected.name" :value="selected.id" />
    <el-option v-for="field in options" :key="field.id" :label="field.name" :value="field.id" />
  </el-select>
</template>

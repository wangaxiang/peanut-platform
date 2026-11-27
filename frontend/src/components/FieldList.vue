<script setup>
defineProps({
  fields: { type: Array, required: true },
  selectedId: Number,
  loading: Boolean,
  total: Number,
  page: Number,
  keyword: String,
})
defineEmits(['update:keyword', 'update:page', 'select'])
const stages = { 苗期: 'seedling', 花针期: 'flowering', 结荚期: 'pod', 饱果期: 'mature' }
const area = value => Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
</script>

<template>
  <aside class="field-sidebar" aria-label="地块列表">
    <div class="sidebar-heading">
      <h2>地块列表</h2>
      <span class="record-count">{{ total }} 块</span>
    </div>
    <label class="search-box">
      <span class="search-icon" aria-hidden="true"></span>
      <input :value="keyword" type="search" placeholder="搜索地块或品种" aria-label="搜索地块或品种"
        @input="$emit('update:keyword', $event.target.value)">
    </label>
    <div class="field-rows" :aria-busy="loading">
      <p v-if="loading && !fields.length" class="list-message" role="status">正在加载地块…</p>
      <div v-else-if="!fields.length" class="list-empty">
        <span class="empty-symbol" aria-hidden="true">▱</span>
        <strong>{{ keyword ? '未找到匹配的地块' : '暂无地块' }}</strong>
        <p v-if="!keyword">在地图上绘制你的第一块农田。</p>
      </div>
      <button v-for="field in fields" :key="field.id" type="button" class="field-row"
        :class="{ active: field.id === selectedId }" :aria-pressed="field.id === selectedId"
        @click="$emit('select', field)">
        <span>
          <strong>{{ field.name }}</strong>
          <span class="field-measure">{{ area(field.areaMu) }} 亩<span v-if="field.variety"> · {{ field.variety }}</span></span>
        </span>
        <span class="stage-tag" :class="stages[field.currentGrowthStage]">{{ field.currentGrowthStage }}</span>
      </button>
    </div>
    <el-pagination v-if="total > 20" small layout="prev, pager, next" :total="total" :page-size="20"
      :current-page="page + 1" :pager-count="5" @current-change="$emit('update:page', $event - 1)" />
  </aside>
</template>

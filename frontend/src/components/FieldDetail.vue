<script setup>
defineProps({ field: Object, events: Array, total: Number, page: Number, loading: Boolean, error: String })
defineEmits(['close', 'edit', 'add-event', 'change-page'])

const types = {
  sow: { text: '播种', icon: '●' },
  fertilize: { text: '施肥', icon: '✚' },
  spray: { text: '打药', icon: '✦' },
  harvest: { text: '收获', icon: '◆' },
  irrigate: { text: '灌溉', icon: '◉' },
}
</script>

<template>
  <aside v-if="field" class="field-detail" aria-label="地块详情">
    <div class="detail-header">
      <div><span class="detail-caption">地块档案</span><h2>{{ field.name }}</h2></div>
      <button class="icon-button" type="button" aria-label="关闭地块详情" @click="$emit('close')">×</button>
    </div>
    <div class="detail-body">
      <button class="text-button edit-field" type="button" @click="$emit('edit')">编辑档案</button>
      <dl class="field-facts">
        <div><dt>面积</dt><dd>{{ Number(field.areaMu).toLocaleString('zh-CN') }} 亩</dd></div>
        <div><dt>土壤</dt><dd>{{ field.soilType || '未填写' }}</dd></div>
        <div><dt>品种</dt><dd>{{ field.variety || '未填写' }}</dd></div>
        <div><dt>播种日期</dt><dd>{{ field.sowDate || '未填写' }}</dd></div>
        <div><dt>生育期</dt><dd>{{ field.currentGrowthStage }}</dd></div>
        <div v-if="field.daysAfterSow >= 0 && field.daysAfterSow !== null">
          <dt>播后天数</dt><dd>{{ field.daysAfterSow }} 天</dd>
        </div>
      </dl>
      <div class="timeline-heading">
        <h3>农事记录</h3>
        <button class="text-button" type="button" @click="$emit('add-event')">＋ 记录农事</button>
      </div>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p v-else-if="loading" class="timeline-empty" role="status">正在加载记录…</p>
      <p v-else-if="!events.length" class="timeline-empty">暂无农事记录</p>
      <ol v-else class="timeline">
        <li v-for="event in events" :key="event.id">
          <span class="timeline-icon" :class="event.eventType" aria-hidden="true">{{ types[event.eventType]?.icon }}</span>
          <div>
            <strong>{{ types[event.eventType]?.text }}</strong>
            <p v-if="event.amount != null">{{ event.amount }} {{ event.unit }}</p>
            <p v-if="event.remark">{{ event.remark }}</p>
          </div>
          <time :datetime="event.eventDate">{{ event.eventDate }}</time>
        </li>
      </ol>
      <el-pagination v-if="total > 20" small layout="prev, pager, next" :page-size="20" :total="total"
        :current-page="page + 1" :pager-count="5" @current-change="$emit('change-page', $event - 1)" />
    </div>
  </aside>
</template>

<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import MapView from './views/MapView.vue'

const tabs = [
  { href: '#/fields', title: '农田管理', component: MapView },
  { href: '#/patrol', title: '智能巡田', component: defineAsyncComponent(() => import('./views/PatrolTask.vue')) },
  { href: '#/patrol/flights', title: '飞行记录', component: defineAsyncComponent(() => import('./views/FlightList.vue')) },
  { href: '#/sensors', title: '土壤监测', component: defineAsyncComponent(() => import('./views/SensorDashboard.vue')) },
  { href: '#/knowledge', title: '知识问答', component: defineAsyncComponent(() => import('./views/KnowledgeChat.vue')) },
  { href: '#/knowledge/chunks', title: '知识库管理', component: defineAsyncComponent(() => import('./views/ChunkAdmin.vue')) },
]

const hash = ref(window.location.hash)
const NdviMap = defineAsyncComponent(() => import('./views/NdviMap.vue'))
const ndviFlight = computed(() => /^#\/patrol\/ndvi\/(\d+)$/.exec(hash.value)?.[1])
const current = computed(() => tabs.find(tab => tab.href === (ndviFlight.value ? '#/patrol/flights' : hash.value)) ?? tabs[0])
function updateHash() { hash.value = window.location.hash }
onMounted(() => window.addEventListener('hashchange', updateHash))
onBeforeUnmount(() => window.removeEventListener('hashchange', updateHash))
</script>

<template>
  <nav class="module-tabs" aria-label="模块导航">
    <a v-for="tab in tabs" :key="tab.href" :href="tab.href"
      :aria-current="tab === current ? 'page' : undefined">{{ tab.title }}</a>
  </nav>
  <NdviMap v-if="ndviFlight" :key="ndviFlight" :flight-id="ndviFlight" />
  <component v-else :is="current.component" />
</template>

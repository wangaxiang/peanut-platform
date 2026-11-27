<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import MapView from './views/MapView.vue'

const tabs = [
  { href: '#/fields', title: '农田管理', component: MapView },
  { href: '#/knowledge', title: '知识问答', component: defineAsyncComponent(() => import('./views/KnowledgeChat.vue')) },
  { href: '#/knowledge/chunks', title: '知识库管理', component: defineAsyncComponent(() => import('./views/ChunkAdmin.vue')) },
]

const hash = ref(window.location.hash)
const current = computed(() => tabs.find(tab => tab.href === hash.value) ?? tabs[0])
function updateHash() { hash.value = window.location.hash }
onMounted(() => window.addEventListener('hashchange', updateHash))
onBeforeUnmount(() => window.removeEventListener('hashchange', updateHash))
</script>

<template>
  <nav class="module-tabs" aria-label="模块导航">
    <a v-for="tab in tabs" :key="tab.href" :href="tab.href"
      :aria-current="tab === current ? 'page' : undefined">{{ tab.title }}</a>
  </nav>
  <component :is="current.component" />
</template>

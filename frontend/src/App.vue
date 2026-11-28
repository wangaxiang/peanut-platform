<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import MapView from './views/MapView.vue'

const tabs = [
  { href: '#/fields', title: '农田管理', component: MapView },
  { href: '#/patrol', title: '巡田与物联网' },
  { href: '#/knowledge', title: '知识问答', component: defineAsyncComponent(() => import('./views/KnowledgeChat.vue')) },
  { href: '#/knowledge/chunks', title: '知识库管理', component: defineAsyncComponent(() => import('./views/ChunkAdmin.vue')) },
]
const FlightList = defineAsyncComponent(() => import('./views/FlightList.vue'))
const SensorDashboard = defineAsyncComponent(() => import('./views/SensorDashboard.vue'))
const patrolTabs = [
  { href: '#/patrol/devices', title: '设备管理', component: defineAsyncComponent(() => import('./views/DeviceManagement.vue')) },
  { href: '#/patrol/simulator', title: '模拟设备实验室', component: defineAsyncComponent(() => import('./views/SimulatorLab.vue')) },
  { href: '#/patrol', title: '巡田任务', component: defineAsyncComponent(() => import('./views/PatrolTask.vue')) },
  { href: '#/patrol/flights', title: '飞行记录', component: FlightList },
  { href: '#/patrol/ndvi', title: 'NDVI 监测', component: FlightList, props: { ndviOnly: true } },
  { href: '#/sensors', title: '传感器监测', component: SensorDashboard },
]

const hash = ref(window.location.hash)
const NdviMap = defineAsyncComponent(() => import('./views/NdviMap.vue'))
const DeviceDetail = defineAsyncComponent(() => import('./views/PatrolDeviceDetail.vue'))
const ndviFlight = computed(() => /^#\/patrol\/ndvi\/(\d+)$/.exec(hash.value)?.[1])
const deviceId = computed(() => /^#\/patrol\/devices\/(\d+)$/.exec(hash.value)?.[1])
const sensorSerial = computed(() => /^#\/sensors\/([A-Za-z0-9_-]{1,64})$/.exec(hash.value)?.[1])
const patrolPage = computed(() => {
  const route = ndviFlight.value ? '#/patrol/ndvi' : deviceId.value ? '#/patrol/devices'
    : sensorSerial.value ? '#/sensors' : hash.value
  return patrolTabs.find(tab => tab.href === route)
})
const current = computed(() => tabs.find(tab => tab.href === (patrolPage.value ? '#/patrol' : hash.value)) ?? tabs[0])
function updateHash() { hash.value = window.location.hash }
onMounted(() => window.addEventListener('hashchange', updateHash))
onBeforeUnmount(() => window.removeEventListener('hashchange', updateHash))
</script>

<template>
  <nav class="module-tabs" aria-label="模块导航">
    <a v-for="tab in tabs" :key="tab.href" :href="tab.href"
      :aria-current="tab === current ? 'page' : undefined">{{ tab.title }}</a>
  </nav>
  <div v-if="patrolPage" class="iot-shell">
    <nav class="patrol-tabs" aria-label="智能巡田导航">
      <a v-for="tab in patrolTabs" :key="tab.href" :href="tab.href"
        :aria-current="tab === patrolPage ? 'page' : undefined">{{ tab.title }}</a>
    </nav>
    <div class="iot-content">
      <NdviMap v-if="ndviFlight" :key="ndviFlight" :flight-id="ndviFlight" />
      <DeviceDetail v-else-if="deviceId" :key="deviceId" :device-id="deviceId" />
      <SensorDashboard v-else-if="sensorSerial" :key="sensorSerial" :initial-serial="sensorSerial" />
      <component v-else :is="patrolPage.component" :key="patrolPage.href" v-bind="patrolPage.props" />
    </div>
  </div>
  <component v-else :is="current.component" />
</template>

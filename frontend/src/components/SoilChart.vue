<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Chart, Legend, LineController, LineElement, LinearScale, PointElement, Tooltip } from 'chart.js'

Chart.register(Legend, LineController, LineElement, LinearScale, PointElement, Tooltip)

const props = defineProps({ readings: Array, metrics: Array, label: String })
const canvas = ref(null)
const tickTime = new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
let chart

function datasets() {
  return props.metrics.map(metric => ({
    label: metric.label,
    data: props.readings.map(reading => ({ x: Date.parse(reading.time), y: reading[metric.key] })),
    borderColor: metric.color,
    backgroundColor: metric.color,
    yAxisID: metric.axis ?? 'y',
    pointRadius: props.readings.length < 40 ? 3 : 0,
    pointHoverRadius: 4,
    borderWidth: 2,
    spanGaps: false,
  }))
}

onMounted(() => {
  const scales = {
    x: { type: 'linear', ticks: { maxTicksLimit: 7, callback: value => tickTime.format(value) } },
    y: { title: { display: true, text: props.metrics[0].unit } },
  }
  if (props.metrics.some(metric => metric.axis === 'right')) {
    scales.right = { position: 'right', title: { display: true, text: '%' },
      suggestedMin: 0, suggestedMax: 100, grid: { drawOnChartArea: false } }
  }
  chart = new Chart(canvas.value, {
    type: 'line',
    data: { datasets: datasets() },
    options: {
      animation: false,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: { tooltip: { callbacks: { title: items => tickTime.format(items[0].parsed.x) } } },
      scales,
    },
  })
})
watch(() => props.readings, () => {
  if (chart) {
    chart.data.datasets = datasets()
    chart.update()
  }
})
onBeforeUnmount(() => chart?.destroy())
</script>

<template>
  <div class="soil-chart"><canvas ref="canvas" role="img" :aria-label="label" /></div>
</template>

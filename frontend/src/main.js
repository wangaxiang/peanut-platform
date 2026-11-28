import { createApp, h } from 'vue'
import { createPinia } from 'pinia'
import {
  ElAlert, ElButton, ElConfigProvider, ElDatePicker, ElDialog, ElInput,
  ElInputNumber, ElOption, ElPagination, ElSelect,
} from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'maplibre-gl/dist/maplibre-gl.css'
import '@mapbox/mapbox-gl-draw/dist/mapbox-gl-draw.css'
import './styles.css'
import './monitoring.css'
import App from './App.vue'

const app = createApp({
  render: () => h(ElConfigProvider, { locale: zhCn }, { default: () => h(App) }),
})
for (const component of [
  ElAlert, ElButton, ElDatePicker, ElDialog, ElInput, ElInputNumber,
  ElOption, ElPagination, ElSelect,
]) {
  app.component(component.name, component)
}
app.use(createPinia()).mount('#app')

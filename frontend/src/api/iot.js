import axios from 'axios'
import { computed, ref } from 'vue'

const client = axios.create({ baseURL: '/api', timeout: 18000 })
const role = ref('')
let accessToken = ''
export const iotRole = computed(() => role.value)
export const isIotAdmin = computed(() => role.value === 'ADMIN')
export const canEditIot = computed(() => ['ADMIN', 'OPERATOR'].includes(role.value))
export function iotHeaders(config) {
  if (accessToken) config.headers.Authorization = 'Bearer ' + accessToken
  return config
}
client.interceptors.request.use(iotHeaders)
const data = response => response.data.data
export async function iotSession(token = accessToken) {
  const previous = accessToken
  accessToken = token.trim()
  try {
    const session = await client.get('/iot/session').then(data)
    role.value = session.role
    return session
  } catch (error) {
    accessToken = previous
    throw error
  }
}
export const getDeviceTypes = () => client.get('/patrol/device-types').then(data)
export const saveDeviceType = (body, editing) => (editing
  ? client.put('/patrol/device-types/' + body.code, body) : client.post('/patrol/device-types', body)).then(data)
export const getMetricDefinitions = () => client.get('/iot/metrics').then(data)
export const saveMetricDefinition = (body, editing) => (editing
  ? client.put('/iot/metrics/' + body.code, body) : client.post('/iot/metrics', body)).then(data)
export const getGroups = () => client.get('/patrol/device-groups').then(data)
export const saveGroup = (id, body) => (id
  ? client.put('/patrol/device-groups/' + id, body) : client.post('/patrol/device-groups', body)).then(data)
export const removeGroup = id => client.delete('/patrol/device-groups/' + id).then(data)
export const setGroupMembers = (id, deviceIds) => client.put('/patrol/device-groups/' + id + '/members', { deviceIds }).then(data)
export const previewDevicesCsv = file => {
  const form = new FormData()
  form.append('file', file)
  return client.post('/patrol/devices/import/preview', form).then(data)
}
export const executeDevicesCsv = jobId => client.post('/patrol/devices/import/execute', { jobId }, { timeout: 60000 }).then(data)
export const exportDevicesCsv = params => client.get('/patrol/devices/export', { params, responseType: 'blob' })
export const getSimulators = () => client.get('/iot/simulator/devices').then(data)
export const createSimulator = body => client.post('/iot/simulator/devices', body).then(data)
export const getSimulatorTemplates = () => client.get('/iot/simulator/templates').then(data)
export const getSimulatorPreview = id => client.get('/iot/simulator/devices/' + id + '/preview').then(data)
export const saveSimulatorProfile = (id, body) => client.put('/iot/simulator/devices/' + id + '/profile', body).then(data)
export const controlSimulator = (id, action, body = {}) => client.post('/iot/simulator/devices/' + id + '/' + action, body).then(data)

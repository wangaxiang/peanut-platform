import axios from 'axios'

const client = axios.create({ baseURL: '/api', timeout: 20000 })
const data = response => response.data.data

export const getTasks = params => client.get('/patrol/tasks', { params }).then(data)
export const createTask = body => client.post('/patrol/tasks', body).then(data)
export const setTaskStatus = (id, status) => client.put(`/patrol/tasks/${id}/status`, { status }).then(data)
export const getFlights = params => client.get('/patrol/flights', { params }).then(data)
export const getFlight = id => client.get(`/patrol/flights/${id}`).then(data)
export const newFlight = id => client.post(`/patrol/tasks/${id}/flights`).then(data)
export const triggerFlight = id => client.post(`/patrol/flights/${id}/trigger`).then(data)
export const uploadFlightImage = (id, file) => {
  const form = new FormData()
  form.append('file', file)
  return client.post(`/patrol/flights/${id}/image`, form, { timeout: 60000 }).then(data)
}
export const getSensors = fieldId => client.get('/iot/sensors', { params: { fieldId } }).then(data)
export const getSensorHistory = (sn, params) =>
  client.get(`/iot/sensors/${encodeURIComponent(sn)}/history`, { params }).then(data)

export const requestMessage = (error, fallback) => error.response?.data?.msg || fallback
const dateFormat = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' })
export const formatTime = time => time ? dateFormat.format(new Date(time)) : '—'

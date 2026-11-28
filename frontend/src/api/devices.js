import axios from 'axios'

const client = axios.create({ baseURL: '/api/patrol/devices', timeout: 15000 })
const data = response => response.data.data
export const getDevices = params => client.get('', { params }).then(data)
export const getDevice = id => client.get(`/${id}`).then(data)
export const getDeviceStats = () => client.get('/stats').then(data)
export const createDevice = body => client.post('', body).then(data)
export const updateDevice = (id, body) => client.put(`/${id}`, body).then(data)
export const changeDeviceStatus = (id, status) => client.put(`/${id}/status`, { status }).then(data)
export const retireDevice = id => client.delete(`/${id}`).then(data)
export const getMaintenance = (id, page) => client.get(`/${id}/maintenance`, { params: { page, size: 10 } }).then(data)
export const createMaintenance = (id, body) => client.post(`/${id}/maintenance`, body).then(data)

export const deviceTypes = { drone: '无人机', dock: '机库', sensor: '巡田传感器' }
export const deviceStatuses = { active: '启用', inactive: '停用', maintenance: '维护中', retired: '已退役' }

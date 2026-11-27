import axios from 'axios'

const client = axios.create({ baseURL: '/api', timeout: 12000 })
const data = response => response.data.data

export const getFields = params => client.get('/fields', { params }).then(data)
export const getStats = () => client.get('/fields/stats').then(data)
export const getField = id => client.get(`/fields/${id}`).then(data)
export const createField = body => client.post('/fields', body).then(data)
export const updateField = (id, body) => client.put(`/fields/${id}`, body).then(data)
export const getEvents = (id, page = 0) =>
  client.get(`/fields/${id}/events`, { params: { page, size: 20 } }).then(data)
export const createEvent = (id, body) => client.post(`/fields/${id}/events`, body).then(data)

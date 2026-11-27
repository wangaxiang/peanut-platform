import axios from 'axios'

const client = axios.create({ baseURL: '/api', timeout: 150000 })
const data = response => response.data.data

export const askQuestion = body => client.post('/knowledge/qa', body).then(data)
export const getQuestionHistory = fieldId =>
  client.get('/knowledge/qa/history', { params: { fieldId: fieldId || undefined, page: 0, size: 20 } }).then(data)
const admin = token => token ? { 'X-Knowledge-Admin-Token': token } : {}
export const getChunks = (page, token) =>
  client.get('/knowledge/chunks', { params: { page, size: 20 }, headers: admin(token) }).then(data)
export const insertChunk = (body, token) => client.post('/knowledge/chunks', body, { headers: admin(token) }).then(data)
function uploadKnowledge(path, file, token, timeout) {
  const form = new FormData()
  form.append('file', file)
  return client.post(path, form, { headers: admin(token), timeout }).then(data)
}
export const importChunks = (file, token) =>
  uploadKnowledge('/knowledge/chunks/import', file, token, 150000)
export const importKnowledgeFile = (file, token) =>
  uploadKnowledge('/knowledge/chunks/import/file', file, token, 300000)
export const generateGrowthReport = fieldId => client.get(`/reports/growth/${fieldId}`).then(data)

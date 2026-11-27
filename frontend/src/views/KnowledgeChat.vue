<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'
import { getFields } from '../api/fields'
import { askQuestion, generateGrowthReport, getQuestionHistory } from '../api/knowledge'

const question = ref('')
const fieldId = ref(null)
const fields = ref([])
const history = ref([])
const turns = ref([])
const pending = ref(false)
const messages = ref(null)
const loadingHistory = ref(false)
const historyError = ref('')
const fieldError = ref('')
const reportError = ref('')
const report = ref(null)
const generating = ref(false)
let historyRequest = 0
const date = value => new Date(value).toLocaleString('zh-CN', { dateStyle: 'short', timeStyle: 'short' })

onMounted(async () => {
  loadHistory()
  try {
    let page = 0
    let result
    do {
      result = await getFields({ page, size: 100 })
      fields.value.push(...result.content)
      page++
    } while (page < result.totalPages)
  } catch (error) {
    fieldError.value = error.response?.data?.msg || '无法读取地块列表。'
  }
})

watch(fieldId, () => {
  turns.value = []
  report.value = null
  reportError.value = ''
  loadHistory()
})

async function loadHistory() {
  const request = ++historyRequest
  loadingHistory.value = true
  historyError.value = ''
  try {
    const result = await getQuestionHistory(fieldId.value)
    if (request === historyRequest) history.value = result.content
  } catch (error) {
    if (request === historyRequest) {
      history.value = []
      historyError.value = error.response?.data?.msg || '暂时无法读取问答历史。'
    }
  } finally {
    if (request === historyRequest) loadingHistory.value = false
  }
}

async function scrollConversation() {
  await nextTick()
  if (messages.value) messages.value.scrollTop = messages.value.scrollHeight
}

async function send() {
  const prompt = question.value.trim()
  if (!prompt || pending.value) return
  const context = turns.value.filter(turn => turn.answer).slice(-3)
    .map(turn => ({ question: turn.question, answer: turn.answer }))
  const index = turns.value.length
  turns.value.push({ question: prompt, pending: true })
  question.value = ''
  pending.value = true
  scrollConversation()
  try {
    const result = await askQuestion({ question: prompt, fieldId: fieldId.value || null, history: context })
    turns.value[index] = result
    loadHistory()
  } catch (error) {
    turns.value[index] = { question: prompt, error: error.response?.data?.msg || '请求失败，请稍后重试。' }
    question.value = prompt
  } finally {
    pending.value = false
    scrollConversation()
  }
}

function keyboardSend(event) {
  if (!event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    send()
  }
}

function showHistory(entry) {
  if (pending.value) return
  turns.value = [entry]
  scrollConversation()
}

async function createReport() {
  const target = fieldId.value
  if (!target || generating.value) return
  generating.value = true
  reportError.value = ''
  try {
    const result = await generateGrowthReport(target)
    if (fieldId.value === target) report.value = result
  } catch (error) {
    if (fieldId.value === target) reportError.value = error.response?.data?.msg || '无法生成生长报告。'
  } finally {
    generating.value = false
  }
}
</script>

<template>
  <main class="knowledge-workspace">
    <header class="knowledge-heading">
      <div><h1>花生知识问答</h1><p>根据知识库资料查询种植与田间管理问题。</p></div>
      <div class="knowledge-field">
        <label for="knowledge-field">关联地块</label>
        <select id="knowledge-field" v-model="fieldId" :disabled="pending || generating">
          <option :value="null">不关联地块</option>
          <option v-for="field in fields" :key="field.id" :value="field.id">{{ field.name }}</option>
        </select>
        <button v-if="fieldId" type="button" class="knowledge-secondary" :disabled="generating" @click="createReport">
          {{ generating ? '正在生成…' : '生成生长报告' }}
        </button>
        <a v-if="report" class="report-link" :href="report.pdfUrl" target="_blank" rel="noopener">查看 PDF</a>
      </div>
    </header>
    <p v-if="fieldError || reportError" class="knowledge-error" role="alert">{{ fieldError || reportError }}</p>
    <div class="knowledge-columns">
      <section class="chat-panel" aria-label="对话">
        <div ref="messages" class="chat-answers" aria-live="polite">
          <div v-if="!turns.length" class="chat-empty">
            <div class="chat-emblem" aria-hidden="true">问</div>
            <h2>你想了解什么？</h2>
            <div class="chat-suggestions">
              <button type="button" @click="question = '花针期需要注意什么？'">花针期需要注意什么？</button>
              <button type="button" @click="question = '花生播种需要哪些条件？'">花生播种需要哪些条件？</button>
            </div>
          </div>
          <article v-for="(turn, index) in turns" :key="turn.id ? 'answer-' + turn.id : 'draft-' + index" class="chat-turn">
            <p class="user-bubble">{{ turn.question }}</p>
            <div class="answer-bubble">
              <p v-if="turn.pending" role="status">思考中…</p>
              <p v-else-if="turn.error" class="knowledge-error" role="alert">{{ turn.error }}</p>
              <template v-else>
                <p>{{ turn.answer }}</p>
                <div v-if="turn.sources?.length" class="citation-list">
                  <span>引用资料</span>
                  <ul><li v-for="source in turn.sources" :key="source.chunkId">{{ source.sourceTitle }}</li></ul>
                </div>
              </template>
            </div>
          </article>
        </div>
        <form class="question-form" @submit.prevent="send">
          <textarea v-model="question" aria-label="输入问题" placeholder="输入你的种植问题…" maxlength="2000"
            rows="2" @keydown.enter="keyboardSend"></textarea>
          <button class="knowledge-primary" type="submit" :disabled="!question.trim() || pending">发送</button>
        </form>
      </section>
      <aside class="history-panel" aria-label="问答历史">
        <div class="history-title"><h2>问答历史</h2><span>最近 20 条</span></div>
        <p v-if="historyError" class="knowledge-error" role="alert">{{ historyError }}</p>
        <p v-else-if="loadingHistory && !history.length" role="status">正在读取…</p>
        <p v-else-if="!history.length" class="history-empty">暂无问答记录</p>
        <button v-for="entry in history" :key="entry.id" class="history-entry" type="button"
          :disabled="pending" @click="showHistory(entry)">
          <strong>{{ entry.question }}</strong><time :datetime="entry.createdAt">{{ date(entry.createdAt) }}</time>
        </button>
      </aside>
    </div>
  </main>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { getChunks, importChunks, importKnowledgeFile, insertChunk } from '../api/knowledge'

const chunks = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const uploading = ref(false)
const saving = ref(false)
const editor = ref(false)
const report = ref(null)
const error = ref('')
const adminToken = ref('')
const needsKey = ref(false)
const formError = ref('')
const notice = ref('')
const csvInput = ref(null)
const documentInput = ref(null)
const form = reactive({ sourceTitle: '', content: '', category: '', variety: '', growthStage: '' })
let loadRequest = 0

onMounted(() => load(0))

async function load(value) {
  const request = ++loadRequest
  loading.value = true
  try {
    const result = await getChunks(value, adminToken.value)
    if (request === loadRequest) {
      needsKey.value = false
      error.value = ''
      chunks.value = result.content
      total.value = result.totalElements
      page.value = value
    }
  } catch (failure) {
    if (request === loadRequest) {
      needsKey.value = failure.response?.status === 401
      error.value = failure.response?.data?.msg || '无法读取知识片段。'
    }
  } finally {
    if (request === loadRequest) loading.value = false
  }
}

function showEditor() {
  Object.assign(form, { sourceTitle: '', content: '', category: '', variety: '', growthStage: '' })
  formError.value = ''
  editor.value = true
}

async function save() {
  if (!form.sourceTitle.trim() || !form.content.trim() || saving.value) return
  saving.value = true
  formError.value = ''
  try {
    await insertChunk({
      sourceTitle: form.sourceTitle.trim(),
      content: form.content.trim(),
      metadata: Object.fromEntries(['category', 'variety', 'growthStage']
        .filter(name => form[name].trim()).map(name => [name, form[name].trim()])),
    }, adminToken.value)
    editor.value = false
    error.value = ''
    notice.value = '知识片段已录入'
    await load(0)
  } catch (failure) {
    formError.value = failure.response?.data?.msg || '知识片段录入失败。'
  } finally {
    saving.value = false
  }
}

async function upload(event, kind) {
  const file = event.target.files?.[0]
  if (!file) return
  uploading.value = true
  report.value = null
  error.value = ''
  notice.value = ''
  try {
    if (kind === 'csv') {
      report.value = await importChunks(file, adminToken.value)
      notice.value = `成功导入 ${report.value.successCount} 条，失败 ${report.value.failureCount} 条`
    } else {
      report.value = await importKnowledgeFile(file, adminToken.value)
      notice.value = `成功写入 ${report.value.successCount} 个知识片段，${report.value.failedFiles} 个文件需处理`
    }
    await load(0)
  } catch (failure) {
    error.value = failure.response?.data?.msg || '文件导入失败。'
  } finally {
    event.target.value = ''
    uploading.value = false
  }
}

const csvTemplate = 'data:text/csv;charset=utf-8,' + encodeURIComponent('\ufeffsourceTitle,content,category,variety,growthStage\n')
</script>

<template>
  <main class="knowledge-workspace knowledge-admin">
    <header class="knowledge-heading">
      <div><h1>知识库管理</h1><p>录入农技资料，供智能问答检索与引用。</p></div>
      <div class="knowledge-field">
        <a :href="csvTemplate" class="report-link" download="knowledge-template.csv">下载 CSV 模板</a>
        <button type="button" class="knowledge-secondary" :disabled="uploading || needsKey" @click="csvInput.click()">
          上传 CSV
        </button>
        <input ref="csvInput" type="file" accept=".csv,text/csv" aria-label="上传知识 CSV" hidden @change="upload($event, 'csv')">
        <button type="button" class="knowledge-secondary" :disabled="uploading || needsKey" @click="documentInput.click()">
          {{ uploading ? '正在导入…' : '导入文件' }}
        </button>
        <input ref="documentInput" type="file" accept=".pdf,.docx,.txt,.md,.csv,.zip"
          aria-label="导入知识文件" hidden @change="upload($event, 'file')">
        <button type="button" class="knowledge-primary" :disabled="needsKey || uploading" @click="showEditor">录入知识</button>
      </div>
    </header>
    <p>文件导入支持 PDF、Word（.docx）、TXT、Markdown、CSV 和 ZIP，单次最多 20 MB。</p>
    <form v-if="needsKey" class="admin-key-form" @submit.prevent="load(0)">
      <label for="knowledge-admin-key">管理密钥</label>
      <input id="knowledge-admin-key" v-model="adminToken" type="password" autocomplete="off" required>
      <button type="submit" class="knowledge-secondary">解锁知识库</button>
    </form>
    <p v-if="notice" class="knowledge-success" role="status">{{ notice }}</p>
    <p v-if="error" class="knowledge-error" role="alert">{{ error }}</p>
    <ul v-if="report?.failures?.length" class="import-errors">
      <li v-for="failure in report.failures" :key="failure.row">第 {{ failure.row }} 行 · {{ failure.message }}</li>
    </ul>
    <ul v-if="report?.files?.length" class="file-import-results" aria-label="文件导入明细">
      <li v-for="(result, index) in report.files" :key="index" :class="{ warning: result.message }">
        {{ result.name }} · 已写入 {{ result.imported }} 个片段<span v-if="result.message"> · {{ result.message }}</span>
      </li>
    </ul>
    <section class="chunk-panel" aria-label="知识片段列表">
      <div class="chunk-table-wrap">
        <table class="chunk-table">
          <caption>知识片段 <span>{{ total }} 条</span></caption>
          <thead><tr><th>来源标题</th><th>分类</th><th>品种</th><th>生育期</th></tr></thead>
          <tbody>
            <tr v-if="!chunks.length"><td colspan="4">{{ loading ? '正在加载…' : '暂无知识片段' }}</td></tr>
            <tr v-for="chunk in chunks" :key="chunk.id">
              <td><strong>{{ chunk.sourceTitle || '未命名资料' }}</strong><p>{{ chunk.content }}</p></td>
              <td>{{ chunk.metadata?.category || '—' }}</td>
              <td>{{ chunk.metadata?.variety || '—' }}</td>
              <td>{{ chunk.metadata?.growthStage || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <el-pagination v-if="total > 20" small layout="prev, pager, next" :page-size="20"
        :current-page="page + 1" :total="total" @current-change="load($event - 1)" />
    </section>
    <el-dialog v-model="editor" title="录入知识" width="min(560px, calc(100vw - 32px))"
      append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <form class="dialog-form" @submit.prevent="save">
        <label for="chunk-title">来源标题 <span class="required">*</span></label>
        <el-input id="chunk-title" v-model="form.sourceTitle" maxlength="200" />
        <label for="chunk-body">资料正文 <span class="required">*</span></label>
        <el-input id="chunk-body" v-model="form.content" type="textarea" :rows="4" maxlength="5000" />
        <div class="form-pair">
          <div><label for="chunk-category">分类</label><el-input id="chunk-category" v-model="form.category" /></div>
          <div><label for="chunk-variety">品种</label><el-input id="chunk-variety" v-model="form.variety" /></div>
        </div>
        <label for="chunk-stage">生育期</label>
        <el-input id="chunk-stage" v-model="form.growthStage" />
        <p v-if="formError" class="knowledge-error" role="alert">{{ formError }}</p>
        <div class="dialog-actions">
          <el-button :disabled="saving" @click="editor = false">取消</el-button>
          <el-button native-type="submit" type="primary" :loading="saving"
            :disabled="!form.sourceTitle.trim() || !form.content.trim()">保存资料</el-button>
        </div>
      </form>
    </el-dialog>
  </main>
</template>
<style scoped>
.file-import-results { margin: 0; padding: 5px 22px; color: var(--pine); font-size: .9rem; }
.file-import-results li + li { margin-top: 5px; }
.file-import-results .warning { color: #87372e; }
</style>

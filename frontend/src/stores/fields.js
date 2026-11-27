import { defineStore } from 'pinia'
import { getFields, getStats } from '../api/fields'

export const useFields = defineStore('fields', {
  state: () => ({
    rows: [],
    mapRows: [],
    stats: null,
    total: 0,
    loading: false,
    listRequest: 0,
  }),
  actions: {
    async loadList(keyword, page) {
      const request = ++this.listRequest
      this.loading = true
      try {
        const result = await getFields({ keyword, page, size: 20 })
        if (request === this.listRequest) {
          this.rows = result.content
          this.total = result.totalElements
        }
      } finally {
        if (request === this.listRequest) this.loading = false
      }
    },
    async loadOverview() {
      const [stats, firstPage] = await Promise.all([getStats(), getFields({ page: 0, size: 2000 })])
      const rows = [...firstPage.content]
      for (let page = 1; page < firstPage.totalPages; page++) {
        const result = await getFields({ page, size: 2000 })
        rows.push(...result.content)
      }
      this.stats = stats
      this.mapRows = rows
    },
  },
})

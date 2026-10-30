<script setup>
defineProps({ items: { type: Array, default: () => [] } })
const dimensions = { ROOM: '自习室', SEAT_TYPE: '座位类型', POWER: '插座', QUIET: '安静区' }
const sources = { EXPLICIT: '本次偏好', HISTORY: '历史记录', NONE: '暂无依据' }
</script>
<template>
  <details v-if="items.length" class="score-breakdown">
    <summary>查看评分明细</summary>
    <div v-for="item in items" :key="item.dimension" class="breakdown-row">
      <strong>{{ dimensions[item.dimension] }}</strong>
      <span>{{ Number(item.score).toFixed(2) }} / {{ item.weight }} 分</span>
      <small>{{ sources[item.source] }}{{ item.source === 'EXPLICIT' ? (item.matched ? ' · 匹配' : ' · 不匹配') : item.source === 'HISTORY' ? ` · ${item.historyMatches}/${item.historyTotal} 次` : '' }}</small>
    </div>
    <p class="tiny">总分按未舍入值计算，分项显示可能存在舍入尾差。</p>
  </details>
</template>
<style scoped>
.score-breakdown{margin-top:14px;font-size:13px;color:var(--text,#263f36)}summary{cursor:pointer;color:#367659;font-weight:600}.breakdown-row{display:grid;grid-template-columns:75px 110px 1fr;gap:8px;margin-top:10px;align-items:center}.breakdown-row small{color:#76877e}@media(max-width:600px){.breakdown-row{grid-template-columns:65px 1fr}.breakdown-row small{grid-column:1/-1}}
</style>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, post, typeLabel, statusLabel, displayTime } from '../api'
import ScoreBreakdown from './ScoreBreakdown.vue'

const available = ref(false), loading = ref(true), sending = ref(false), resetting = ref(false)
const messages = ref([]), cards = ref([]), criteria = ref({}), input = ref(''), error = ref(''), feed = ref(null), pending = ref(null), now = ref(Date.now())
const booking = ref(''), retryCards = ref(new Set())
let generation = 0
const timer = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => { clearInterval(timer); generation++ })
const examples = ['帮我找明天下午两点到四点，靠窗、有插座的座位', '我想预约明天的自习室，先看看有哪些空间', '查看我待开始的预约']
const fieldNames = { startTime: '开始', endTime: '结束', roomId: '自习室ID', seatType: '座位类型', hasPower: '插座', isQuiet: '安静区' }
const labels = computed(() => Object.entries(criteria.value).map(([field, condition]) => {
  let value = condition.value
  if (field.endsWith('Time')) value = displayTime(value)
  if (field === 'seatType') value = typeLabel(value)
  if (typeof value === 'boolean') value = value ? '是' : '否'
  return `${fieldNames[field]}：${value}${field.endsWith('Time') ? '' : condition.strength === 'HARD' ? ' · 必须' : ' · 优先'}`
}))
const expired = card => Date.parse(card.expiresAt) <= now.value
const turnCards = turn => cards.value.filter(c => (turn.cardIds || []).includes(c.cardId))
async function bottom() { await nextTick(); feed.value?.scrollTo({ top: feed.value.scrollHeight, behavior: 'smooth' }) }
async function restore() {
  loading.value = true
  try { const data = await api('/assistant/conversation'); available.value = data.available; messages.value = data.messages; cards.value = data.cards; criteria.value = data.conversationState; error.value = data.busy ? '上一条消息仍在处理中，请稍后刷新会话。' : ''; await bottom() }
  catch (e) { error.value = e.message } finally { loading.value = false }
}
async function send(text, retry = false) {
  if (sending.value || resetting.value || !available.value) return
  const message = (text ?? input.value).trim()
  if (!message) return
  if ([...message].length > 2000) return ElMessage.warning('消息最多2000个字符')
  const request = retry && pending.value ? pending.value : { message, requestId: crypto.randomUUID() }
  pending.value = request; sending.value = true; error.value = ''; const version = generation
  try {
    const data = await post('/assistant/chat', request)
    if (version !== generation) return
    available.value = data.available; criteria.value = data.conversationState
    if (!messages.value.some(m => m.requestId === request.requestId)) messages.value.push({ requestId: request.requestId, message: request.message, reply: data.reply, cardIds: data.cards.map(c => c.cardId) })
    messages.value = messages.value.slice(-20)
    const incoming = new Map(cards.value.map(c => [c.cardId, c])); data.cards.forEach(c => incoming.set(c.cardId, c)); cards.value = [...incoming.values()].filter(c => Date.parse(c.generatedAt) + 1800000 > Date.now()).slice(-100)
    input.value = ''; pending.value = null; await bottom()
  } catch (e) {
    if (version !== generation) return
    error.value = e.message
    if (e.code !== 'NETWORK_ERROR') pending.value = null
    if (e.code === 'ASSISTANT_UNAVAILABLE') error.value += '；可继续使用发现自习室或为你推荐。'
  } finally { if (version === generation) sending.value = false }
}
async function reset() {
  try { await ElMessageBox.confirm('清空后会移除对话、偏好和卡片，已有预约仍保留。', '清空会话', { confirmButtonText: '清空', cancelButtonText: '保留' }) } catch { return }
  generation++; sending.value = false; resetting.value = true
  try { await post('/assistant/reset', {}); pending.value = null; input.value = ''; await restore() } catch (e) { error.value = e.message } finally { resetting.value = false }
}
async function book(card) {
  if (booking.value) return
  try { await ElMessageBox.confirm(`${card.roomName} · ${card.seatNo}号，${displayTime(card.startTime)} 至 ${displayTime(card.endTime)}。确认预约？`, '确认预约', { confirmButtonText: '确认预约', cancelButtonText: '再看看' }) } catch { return }
  booking.value = card.cardId
  try { const result = await post('/assistant/book', { cardId: card.cardId }); card.reservation = result; card.actionState = 'BOOKED'; retryCards.value.delete(card.cardId); ElMessage.success(`预约记录已确认：${statusLabel(result.status)}`) }
  catch (e) { ElMessage.error(e.message); if (e.code === 'NETWORK_ERROR') retryCards.value.add(card.cardId); if (e.code === 'ASSISTANT_CARD_EXPIRED') card.actionState = 'EXPIRED'; if (['SEAT_CONFLICT','USER_CONFLICT','ROOM_UNAVAILABLE','RESOURCE_NOT_FOUND'].includes(e.code)) card.actionState = 'CONFLICT' }
  finally { booking.value = '' }
}
async function cancel(card) {
  try { await ElMessageBox.confirm('确定取消这条预约吗？取消后将释放座位。', '取消预约', { confirmButtonText: '确定取消', cancelButtonText: '保留' }); await post('/reserve/cancel', { reserveId: card.reserveId }); card.status = 'CANCELLED'; ElMessage.success('已取消预约') } catch (e) { if (e instanceof Error) ElMessage.error(e.message) }
}
onMounted(restore)
</script>
<template>
  <div class="page-content assistant-page" v-loading="loading">
    <section class="page-heading"><div><span class="eyebrow">说出计划，找到合适的位置</span><h1>预约助手</h1><p class="muted">DeepSeek 理解需求，系统实时查座；预约与取消由你确认。</p></div><el-button :loading="resetting" @click="reset">清空会话</el-button></section>
    <el-alert v-if="!loading && !available" title="预约助手尚未配置或已关闭" description="请联系管理员检查本机配置。你仍可以使用自习室查询、推荐和预约功能。" type="info" :closable="false" show-icon />
    <div class="assistant-criteria"><span class="small-label">当前查询条件</span><div v-if="labels.length" class="tags"><span v-for="label in labels" :key="label">{{ label }}</span></div><p v-else class="muted">可以先告诉我想预约的日期、起止时间和座位偏好。</p></div>
    <div ref="feed" class="assistant-feed" aria-live="polite">
      <div v-if="!messages.length" class="assistant-welcome"><span class="assistant-mark">✦</span><h2>下一段专注，从哪里开始？</h2><p>试着说“明天14点到16点，最好靠窗”，或查看你的预约。</p><button v-for="example in examples" :key="example" :disabled="!available || sending || resetting" @click="send(example)">{{ example }} ↗</button></div>
      <article v-for="turn in messages" :key="turn.requestId" class="assistant-turn">
        <div class="assistant-user">{{ turn.message }}</div><div class="assistant-reply"><span class="assistant-speaker">✦ 静习助手</span><p>{{ turn.reply }}</p></div>
        <div class="assistant-cards"><article v-for="card in turnCards(turn)" :key="card.cardId" class="assistant-card">
          <div class="assistant-card-heading"><h3>{{ card.roomName }} · {{ card.seatNo }}号</h3><strong v-if="card.kind === 'CANDIDATE'">{{ Number(card.score).toFixed(2) }}<small> 分</small></strong><span v-else class="status-badge" :class="card.status">{{ statusLabel(card.status) }}</span></div>
          <p>{{ displayTime(card.startTime) }} 至 {{ displayTime(card.endTime) }}</p><div class="tags"><span>{{ typeLabel(card.seatType) }}</span><span>{{ card.hasPower ? '有插座' : '无插座' }}</span><span>{{ card.isQuiet ? '安静区' : '非安静区' }}</span></div>
          <p v-if="card.reason" class="muted">{{ card.reason }}</p><ScoreBreakdown :items="card.breakdown" />
          <div class="assistant-card-actions" v-if="card.kind === 'CANDIDATE'">
            <template v-if="card.reservation"><span class="status-badge" :class="card.reservation.status">预约已确认 · {{ statusLabel(card.reservation.status) }}</span></template>
            <template v-else><el-button type="primary" :loading="booking === card.cardId" :disabled="!!booking || !available || (expired(card) && !retryCards.has(card.cardId)) || ['EXPIRED','CONFLICT'].includes(card.actionState)" @click="book(card)">{{ retryCards.has(card.cardId) ? '确认上次预约结果' : '确认预约' }}</el-button><small class="muted">{{ expired(card) ? '卡片已过期，请重新查询' : `查询于 ${displayTime(card.generatedAt)} · 有效2分钟` }}</small></template>
            <el-button plain :disabled="sending || !available" @click="send('请沿用当前条件重新查询空闲座位')">重新查询</el-button>
          </div>
          <el-button v-else-if="card.status === 'PENDING' && Date.parse(card.startTime) > now" type="danger" plain @click="cancel(card)">取消预约</el-button>
        </article></div>
      </article>
      <div v-if="sending" class="assistant-thinking">✦ 正在理解条件并查询，请稍候…</div>
    </div>
    <div v-if="Object.keys(criteria).some(k => !k.endsWith('Time'))" class="assistant-relax"><span class="tiny">调整条件</span><button v-for="field in Object.keys(criteria).filter(k => !k.endsWith('Time'))" :key="field" :disabled="sending || !available" @click="send({ roomId: '不限自习室', seatType: '去掉座位类型要求', hasPower: '去掉插座要求', isQuiet: '去掉安静区要求' }[field])">去掉{{ fieldNames[field] }}要求</button></div>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon /><el-button v-if="pending && !sending" @click="send(pending.message, true)">重试原消息</el-button>
    <form class="assistant-composer" @submit.prevent="send()"><el-input v-model="input" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="告诉我日期、开始与结束时间，以及你的座位偏好…" :disabled="!available || sending || resetting" @keydown.ctrl.enter.prevent="send()" /><div><span class="tiny">Ctrl + Enter 发送 · 不会自动预约</span><el-button type="primary" native-type="submit" :loading="sending" :disabled="!available || resetting || !input.trim()">发送消息 ↗</el-button></div></form>
  </div>
</template>
<style scoped>
.assistant-page{max-width:1150px;margin:auto}.assistant-criteria{padding:20px 0}.assistant-criteria .tags{margin-top:12px;flex-wrap:wrap}.assistant-feed{max-height:65vh;min-height:300px;overflow-y:auto;padding:4px 10px 18px 0;scroll-behavior:smooth}.assistant-welcome{text-align:center;padding:30px 12px}.assistant-mark{font-size:42px;color:#4d8767}.assistant-welcome h2{font-size:24px}.assistant-welcome p{color:#748279;line-height:1.7}.assistant-welcome button{display:block;margin:12px auto;border:1px solid #dce7df;background:#fff;border-radius:12px;padding:14px 20px;color:#3b6650;cursor:pointer;text-align:left;max-width:100%}.assistant-turn{margin:20px 0 28px}.assistant-user{white-space:pre-wrap;overflow-wrap:anywhere;max-width:85%;width:fit-content;margin-left:auto;padding:14px 18px;background:#e4eee5;border-radius:16px 16px 3px 16px;line-height:1.7}.assistant-reply{margin:22px 0 14px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}.assistant-speaker{font-size:12px;color:#62816c}.assistant-reply p{margin:6px 0}.assistant-cards{display:grid;gap:12px}.assistant-card{padding:20px;border:1px solid #e0e7df;border-radius:14px;background:#fff}.assistant-card-heading{display:flex;justify-content:space-between;gap:12px;align-items:center}.assistant-card h3{font-size:16px;margin:0}.assistant-card-heading strong{color:#3d7853;white-space:nowrap}.assistant-card-heading small{font-size:12px}.assistant-card>p{font-size:13px;line-height:1.7}.assistant-card .tags{flex-wrap:wrap}.assistant-card-actions{display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin-top:18px}.assistant-card>.el-button{margin-top:16px}.assistant-thinking{padding:20px;color:#5a8269}.assistant-composer{position:sticky;bottom:0;background:#f7f8f3;padding:16px 0 8px;margin-top:14px;z-index:2}.assistant-composer>div{display:flex;justify-content:space-between;align-items:center;margin-top:10px;gap:8px}.assistant-relax{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:14px 0}.assistant-relax button{font-size:12px;border:1px solid #dce6dc;border-radius:20px;padding:7px 10px;background:white;color:#4b7358;cursor:pointer}button:disabled{cursor:not-allowed;opacity:.6}@media(max-width:600px){.assistant-feed{max-height:58vh}.assistant-card{padding:14px}.assistant-user{max-width:95%}.assistant-page .page-heading{gap:10px;align-items:center}.assistant-page .page-heading h1{font-size:25px}.assistant-welcome{padding:12px 0}.assistant-welcome h2{font-size:21px}.assistant-card-actions{align-items:flex-start}.assistant-composer>div>span{max-width:45%;line-height:1.6}}
</style>

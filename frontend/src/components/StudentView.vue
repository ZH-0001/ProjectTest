<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, post, typeLabel, statusLabel, displayTime, chinaDate } from '../api'
const props = defineProps({ view: String })
const loading = ref(false), saving = ref(false), rooms = ref([]), roomTotal = ref(0), roomPage = ref(1), selectedRoom = ref(null), seats = ref([]), selectedSeat = ref(null), userAvailable = ref(true)
const recommended = ref(null), bookings = ref([]), total = ref(0), page = ref(1), status = ref('')
const period = reactive({ date: chinaDate(1), start: '08:00', end: '10:00' })
const hours = Array.from({ length: 48 }, (_, i) => `${String(Math.floor(i / 2)).padStart(2, '0')}:${i % 2 ? '30' : '00'}`)
const timeParams = computed(() => ({ startTime: `${period.date}T${period.start}:00+08:00`, endTime: `${period.date}T${period.end}:00+08:00` }))
const free = computed(() => seats.value.filter(s => s.seatAvailable).length)
const duration = computed(() => { const [a, b] = [period.start, period.end].map(t => Number(t.slice(0, 2)) + Number(t.slice(3)) / 60); return b - a })
// Retain uncertain submissions by payload, including when the user selects the same seat again.
const pendingRequests = new Map()
let queryVersion = 0
watch(() => [period.date, period.start, period.end], () => { queryVersion++; seats.value = []; selectedSeat.value = null; recommended.value = null })
watch(() => props.view, loadView)
function validPeriod() { if (!period.date || duration.value < 0.5 || duration.value > 4) { ElMessage.warning('请选择同日的30分钟至4小时时段'); return false } return true }
async function roomList() { const data = await api('/room/list', { params: { page: roomPage.value, size: 6 } }); rooms.value = data.records; roomTotal.value = data.total }
async function openRoom(room) {
  if (!validPeriod()) return
  loading.value = true; selectedSeat.value = null; selectedRoom.value = room
  const version = ++queryVersion
  try { const data = await api(`/seat/list/${room.id}`, { params: timeParams.value }); if (version !== queryVersion) return; seats.value = data.seats; userAvailable.value = data.userAvailable } catch (e) { if (version === queryVersion) { seats.value = []; ElMessage.error(e.message) } } finally { loading.value = false }
}
async function reserve(seat = selectedSeat.value) {
  if (!seat || saving.value) return
  try { await ElMessageBox.confirm(`预约 ${selectedRoom.value?.name || seat.roomName} · ${seat.seatNo}号座位，${period.date} ${period.start}–${period.end}？`, '确认预约', { confirmButtonText: '确认预约', cancelButtonText: '再看看' }) } catch { return }
  const seatId = seat.seatId || seat.id
  const signature = JSON.stringify({ seatId, ...timeParams.value })
  if (!pendingRequests.has(signature)) pendingRequests.set(signature, { seatId, ...timeParams.value, requestId: crypto.randomUUID() })
  const request = pendingRequests.get(signature)
  saving.value = true
  try {
    const result = await post('/reserve/add', request)
    ElMessage.success(result.status === 'CANCELLED' ? '原请求对应预约已取消；新预约请重新选择座位' : '预约成功，可在“我的预约”查看')
    pendingRequests.delete(signature); selectedSeat.value = null; recommended.value = null
    if (props.view === 'rooms' && selectedRoom.value) await openRoom(selectedRoom.value)
  } catch (e) { ElMessage.error(e.message); if (!['NETWORK_ERROR', 'RETRYABLE_ERROR', 'INTERNAL_ERROR', 'SERVER_ERROR'].includes(e.code)) pendingRequests.delete(signature) } finally { saving.value = false }
}
async function recommend() { if (!validPeriod()) return; loading.value = true; selectedRoom.value = null; const version = ++queryVersion; try { const result = await api('/ai/recommend', { params: timeParams.value }); if (version === queryVersion) recommended.value = result } catch (e) { if (version === queryVersion) { recommended.value = null; ElMessage.error(e.message) } } finally { loading.value = false } }
async function myList() { const data = await api('/reserve/myList', { params: { page: page.value, size: 10, status: status.value } }); bookings.value = data.records; total.value = data.total }
async function cancel(row) { try { await ElMessageBox.confirm('取消后将释放该预约时段，确定取消吗？', '取消预约', { confirmButtonText: '确定取消', cancelButtonText: '保留预约' }); await post('/reserve/cancel', { reserveId: row.id }); ElMessage.success('已取消预约'); await myList() } catch (e) { if (e instanceof Error) ElMessage.error(e.message) } }
async function loadView() { loading.value = true; try { if (props.view === 'rooms') await roomList(); else if (props.view === 'reservations') await myList() } catch (e) { ElMessage.error(e.message) } finally { loading.value = false } }
function disabledDate(date) { const d = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; return d < chinaDate() || d > chinaDate(7) }
onMounted(loadView)
</script>
<template>
  <div class="page-content" v-loading="loading">
    <section class="page-heading"><div><span class="eyebrow">{{ view === 'rooms' ? '给学习，留一个好位置' : view === 'recommend' ? '你的习惯，我们记得' : '每一段专注，都有安排' }}</span><h1>{{ view === 'rooms' ? '发现自习室' : view === 'recommend' ? '为你推荐' : '我的预约' }}</h1><p class="muted">{{ view === 'rooms' ? '选一个时间，找到适合你的安静角落。' : view === 'recommend' ? '结合历史选择，为你的下一次学习推荐座位。' : '查看学习计划，合理安排你的时间。' }}</p></div><div class="heading-decoration">{{ view === 'recommend' ? '✧' : '◷' }}</div></section>
    <template v-if="view !== 'reservations'">
      <section class="period-bar"><div class="period-title"><span class="small-label">计划自习时间</span><strong>预约你的专注时光</strong></div><div class="period-input"><label>日期</label><el-date-picker v-model="period.date" type="date" value-format="YYYY-MM-DD" :clearable="false" :disabled-date="disabledDate" /></div><div class="period-input"><label>开始</label><el-select v-model="period.start"><el-option v-for="hour in hours" :key="hour" :value="hour" :label="hour" /></el-select></div><span class="time-dash">—</span><div class="period-input"><label>结束</label><el-select v-model="period.end"><el-option v-for="hour in hours" :key="hour" :value="hour" :label="hour" /></el-select></div><el-button type="primary" :loading="loading" @click="view === 'recommend' ? recommend() : selectedRoom ? openRoom(selectedRoom) : loadView()">{{ view === 'recommend' ? '获取推荐' : '查询座位' }} →</el-button></section>
      <div class="helper-line">北京时间 · 半小时档位 · 单次 30 分钟至 4 小时 · 可预约未来 7 天</div>
    </template>
    <template v-if="view === 'rooms'">
      <div class="section-title"><h2>校园学习空间 <span>{{ roomTotal }} 间</span></h2><span class="muted">选择自习室，查看目标时段座位</span></div>
      <div class="room-grid"><article v-for="(room, index) in rooms" :key="room.id" class="room-card" :class="{ chosen: selectedRoom?.id === room.id }"><div class="room-art" :class="`art-${index % 3}`"><span class="room-art-label">{{ ['QUIET SPACE', 'MORNING LIGHT', 'FOCUS STUDIO'][index % 3] }}</span><div class="art-window"></div><div class="art-desk"></div><span class="room-open">开放预约</span></div><div class="room-card-body"><h3>{{ room.name }}</h3><p>{{ room.location }}</p><div class="room-card-footer"><span>开放 {{ room.openTime }}–{{ room.closeTime }}</span><button @click="openRoom(room)">查看座位 ↗</button></div></div></article></div>
      <el-empty v-if="!rooms.length" description="暂无开放自习室" /><el-pagination v-if="roomTotal > 6" v-model:current-page="roomPage" :page-size="6" :total="roomTotal" layout="prev, pager, next" @current-change="roomList" />
      <section v-if="selectedRoom" class="seat-panel"><div class="section-title"><h2>{{ selectedRoom.name }} <span>{{ free }} 个空闲座位</span></h2><div class="legend"><i></i>可预约<i class="unavailable"></i>已预约<i class="selected"></i>已选择</div></div><el-alert v-if="!userAvailable" title="你在该时段已有预约，请选择其他时间或先取消原预约" type="warning" :closable="false" /><div class="seat-content"><div class="seat-map"><div class="window-label">采光窗 / 座位属性以标签为准</div><div class="seat-grid"><button v-for="seat in seats" :key="seat.id" class="seat" :class="{ unavailable: !seat.seatAvailable, selected: selectedSeat?.id === seat.id }" :disabled="!seat.seatAvailable || !userAvailable" @click="selectedSeat = seat"><svg viewBox="0 0 36 36" aria-hidden="true"><path d="M8 5h20v13H8zM5 20h26v8H5zM8 28v5m20-5v5" /></svg><strong>{{ seat.seatNo }}</strong><span>{{ typeLabel(seat.seatType) }}{{ seat.hasPower ? ' · 插座' : '' }}</span></button></div><el-empty v-if="!seats.length" description="请查询目标时段，或选择其他自习室" /></div><aside class="booking-summary"><span class="small-label">本次学习计划</span><h3>{{ selectedSeat ? selectedSeat.seatNo + ' 号座位' : '选择一个座位' }}</h3><p>{{ selectedRoom.location }}</p><div class="summary-detail"><span>日期<strong>{{ period.date }}</strong></span><span>时段<strong>{{ period.start }}–{{ period.end }}</strong></span><span>时长<strong>{{ duration > 0 ? duration : '—' }} 小时</strong></span></div><div v-if="selectedSeat" class="tags"><span>{{ typeLabel(selectedSeat.seatType) }}</span><span>{{ selectedSeat.hasPower ? '有插座' : '无插座' }}</span><span>{{ selectedSeat.isQuiet ? '安静区' : '非安静区' }}</span></div><el-button type="primary" class="full-button" :disabled="!selectedSeat || !userAvailable" :loading="saving" @click="reserve()">确认预约 →</el-button><p class="tiny">预约开始前可取消，请按计划合理使用座位。</p></aside></div></section>
    </template>
    <template v-else-if="view === 'recommend'"><div class="recommend-intro"><span>✧</span><div><h3>适合你的，不止一个座位</h3><p>根据已结束且未取消的预约，分析自习室、座位类型、插座和安静区的选择比例。</p></div></div><template v-if="recommended"><div class="section-title"><h2>{{ recommended.mode === 'DEFAULT' ? '可预约座位' : '你的个性化推荐' }}</h2><span class="muted">{{ recommended.historyCount }} 条有效历史 · 最多推荐 5 席</span></div><div class="recommend-list"><article v-for="(seat, index) in recommended.seats" :key="seat.seatId" class="recommend-card"><span class="rank">{{ String(index + 1).padStart(2, '0') }}</span><div class="recommend-description"><h3>{{ seat.roomName }} · {{ seat.seatNo }}号</h3><div class="tags"><span>{{ typeLabel(seat.seatType) }}</span><span>{{ seat.hasPower ? '有插座' : '无插座' }}</span><span>{{ seat.isQuiet ? '安静区' : '非安静区' }}</span></div><p>{{ seat.reason }}</p></div><div class="score"><strong>{{ Number(seat.score).toFixed(2) }}</strong><small>推荐得分</small></div><el-button type="primary" plain :loading="saving" @click="reserve(seat)">预约这一席 ↗</el-button></article></div><el-empty v-if="!recommended.seats.length" description="该时段暂无可预约座位，请更换时间" /></template><el-empty v-else description="选择自习时段，获取属于你的推荐" /></template>
    <template v-else><div class="table-toolbar"><el-select v-model="status" placeholder="全部状态" clearable @change="page = 1; myList()"><el-option v-for="s in ['PENDING', 'IN_PROGRESS', 'ENDED', 'CANCELLED']" :key="s" :value="s" :label="statusLabel(s)" /></el-select><el-button @click="myList">刷新记录</el-button></div><div class="table-card"><el-table :data="bookings" empty-text="还没有预约，去发现一个学习空间吧"><el-table-column label="学习空间" min-width="230"><template #default="{ row }"><strong>{{ row.roomName }}</strong><div class="table-sub">{{ row.seatNo }}号 · {{ typeLabel(row.seatType) }}</div></template></el-table-column><el-table-column label="预约时段" min-width="240"><template #default="{ row }">{{ displayTime(row.startTime) }}<div class="table-sub">至 {{ displayTime(row.endTime) }}</div></template></el-table-column><el-table-column label="状态" width="120"><template #default="{ row }"><span class="status-badge" :class="row.status">{{ statusLabel(row.status) }}</span></template></el-table-column><el-table-column label="操作" width="120"><template #default="{ row }"><el-button v-if="row.status === 'PENDING'" link type="danger" @click="cancel(row)">取消预约</el-button><span v-else class="muted">—</span></template></el-table-column></el-table><el-pagination v-model:current-page="page" :page-size="10" :total="total" layout="total, prev, pager, next" @current-change="myList" /></div></template>
  </div>
</template>

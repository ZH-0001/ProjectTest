<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, post, typeLabel, statusLabel, displayTime, chinaDate } from '../api'

const props = defineProps({ view: String })
const loading = ref(false), saving = ref(false), rows = ref([]), total = ref(0), page = ref(1)
const allRooms = ref([]), roomId = ref(''), dialog = ref(false), editing = ref(false)
const enabled = ref(''), status = ref(''), account = ref(''), dates = ref([])
const statsDates = ref([chinaDate(-6), chinaDate()]), statistics = ref([])
const roomForm = reactive({ roomId: '', name: '', location: '', openTime: '08:00', closeTime: '22:00' })
const seatForm = reactive({ seatId: '', seatNo: '', seatType: 'WINDOW', hasPower: true, isQuiet: true })
const titles = { rooms: '自习室管理', seats: '座位管理', reservations: '预约记录', statistics: '每日统计' }
const totals = computed(() => statistics.value.reduce((sum, day) => ({ total: sum.total + day.totalCount, cancelled: sum.cancelled + day.cancelledCount, active: sum.active + day.activeCount }), { total: 0, cancelled: 0, active: 0 }))
const maxCount = computed(() => Math.max(1, ...statistics.value.map(d => d.totalCount)))
const roomOptions = computed(() => allRooms.value.map(r => ({ label: `${r.name}${r.enabled ? '' : '（已停用）'}`, value: r.id })))

async function loadRoomOptions() {
  const first = await api('/admin/room/list', { params: { page: 1, size: 100 } })
  let result = [...first.records]
  for (let p = 2; result.length < first.total; p++) {
    const next = await api('/admin/room/list', { params: { page: p, size: 100 } })
    if (!next.records.length) break
    result.push(...next.records)
  }
  allRooms.value = result
  if (!result.some(r => r.id === roomId.value)) roomId.value = result[0]?.id || ''
}
async function load() {
  loading.value = true
  try {
    if (props.view === 'statistics') {
      if (!statsDates.value?.length) { ElMessage.warning('请选择统计日期'); return }
      statistics.value = await api('/admin/statistics/daily', { params: { fromDate: statsDates.value[0], toDate: statsDates.value[1] } })
      return
    }
    if (props.view === 'seats' && !roomId.value) { rows.value = []; total.value = 0; return }
    const params = { page: page.value, size: 10 }
    if (props.view === 'rooms') params.enabled = enabled.value
    if (props.view === 'seats') params.roomId = roomId.value
    if (props.view === 'reservations') Object.assign(params, { account: account.value.trim(), status: status.value, fromDate: dates.value?.[0], toDate: dates.value?.[1] })
    const path = { rooms: '/admin/room/list', seats: '/admin/seat/list', reservations: '/admin/reserve/list' }[props.view]
    const data = await api(path, { params }); rows.value = data.records; total.value = data.total
  } catch (e) { ElMessage.error(e.message) } finally { loading.value = false }
}
async function enterView() {
  page.value = 1; rows.value = []; total.value = 0
  try { if (props.view === 'seats') await loadRoomOptions(); await load() } catch (e) { ElMessage.error(e.message) }
}
function edit(row) {
  editing.value = !!row
  if (props.view === 'rooms') Object.assign(roomForm, { roomId: row?.id || '', name: row?.name || '', location: row?.location || '', openTime: row?.openTime || '08:00', closeTime: row?.closeTime || '22:00' })
  else Object.assign(seatForm, { seatId: row?.id || '', seatNo: row?.seatNo || '', seatType: row?.seatType || 'WINDOW', hasPower: row?.hasPower ?? true, isQuiet: row?.isQuiet ?? true })
  dialog.value = true
}
async function save() {
  let body, resource
  if (props.view === 'rooms') {
    if (!roomForm.name.trim() || !roomForm.location.trim() || !roomForm.openTime || !roomForm.closeTime) return ElMessage.warning('请完整填写自习室信息')
    body = { name: roomForm.name.trim(), location: roomForm.location.trim(), openTime: roomForm.openTime, closeTime: roomForm.closeTime }
    if (editing.value) body.roomId = roomForm.roomId
    resource = 'room'
  } else {
    if (!seatForm.seatNo.trim()) return ElMessage.warning('请输入座位编号')
    body = { seatNo: seatForm.seatNo.trim(), seatType: seatForm.seatType, hasPower: seatForm.hasPower, isQuiet: seatForm.isQuiet }
    if (editing.value) body.seatId = seatForm.seatId; else body.roomId = roomId.value
    resource = 'seat'
  }
  saving.value = true
  try { await post(`/admin/${resource}/${editing.value ? 'update' : 'add'}`, body); ElMessage.success('保存成功'); dialog.value = false; await load() } catch (e) { ElMessage.error(e.message) } finally { saving.value = false }
}
async function toggle(row) {
  try { await ElMessageBox.confirm(`${row.enabled ? '停用' : '启用'}“${row.name}”？`, '调整预约开放状态', { confirmButtonText: '确定', cancelButtonText: '取消' }); await post('/admin/room/status', { roomId: row.id, enabled: !row.enabled }); ElMessage.success('状态已更新'); await load() } catch (e) { if (e instanceof Error) ElMessage.error(e.message) }
}
async function remove(row) {
  try {
    await ElMessageBox.confirm('删除后不再接受预约，历史记录仍会保留。有未结束预约时无法删除。', '确认删除', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    await post(`/admin/${props.view === 'rooms' ? 'room' : 'seat'}/delete`, props.view === 'rooms' ? { roomId: row.id } : { seatId: row.id })
    ElMessage.success('已删除'); if (rows.value.length === 1 && page.value > 1) page.value--; await load()
  } catch (e) { if (e instanceof Error) ElMessage.error(e.message) }
}
function search() { page.value = 1; load() }
watch(() => props.view, enterView)
onMounted(enterView)
</script>
<template>
  <div class="page-content" v-loading="loading">
    <section class="page-heading"><div><span class="eyebrow">让每一处空间，有序运行</span><h1>{{ titles[view] }}</h1><p class="muted">{{ view === 'statistics' ? '按预约开始日期查看预约数量，不代表实际到场或使用次数。' : view === 'reservations' ? '查看全校预约安排，支持按账号、状态和日期筛选。' : '维护学习空间与预约规则，为学生提供清晰可靠的选座信息。' }}</p></div><span class="heading-decoration">▦</span></section>
    <div v-if="view !== 'statistics'" class="table-toolbar">
      <el-select v-if="view === 'rooms'" v-model="enabled" clearable placeholder="全部开放状态" @change="search"><el-option label="已启用" :value="true" /><el-option label="已停用" :value="false" /></el-select>
      <el-select v-if="view === 'seats'" v-model="roomId" placeholder="选择自习室" @change="search"><el-option v-for="room in roomOptions" :key="room.value" :label="room.label" :value="room.value" /></el-select>
      <template v-if="view === 'reservations'"><el-input v-model="account" placeholder="学生账号（精确匹配）" clearable @keyup.enter="search" /><el-select v-model="status" clearable placeholder="全部状态"><el-option v-for="s in ['PENDING', 'IN_PROGRESS', 'ENDED', 'CANCELLED']" :key="s" :value="s" :label="statusLabel(s)" /></el-select><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" /><el-button type="primary" @click="search">查询</el-button></template>
      <el-button v-else type="primary" :disabled="view === 'seats' && !roomId" @click="edit(null)">＋ {{ view === 'rooms' ? '新增自习室' : '新增座位' }}</el-button><el-button @click="load">刷新</el-button>
    </div>
    <div v-if="view !== 'statistics'" class="table-card">
      <el-table :data="rows" empty-text="暂无符合条件的数据">
        <template v-if="view === 'rooms'"><el-table-column label="自习室" min-width="230"><template #default="{ row }"><strong>{{ row.name }}</strong><div class="table-sub">{{ row.location }}</div></template></el-table-column><el-table-column label="每日开放时间" min-width="140"><template #default="{ row }">{{ row.openTime }}–{{ row.closeTime }}</template></el-table-column><el-table-column label="状态" width="110"><template #default="{ row }"><span class="status-badge" :class="row.enabled ? 'PENDING' : 'CANCELLED'">{{ row.enabled ? '已启用' : '已停用' }}</span></template></el-table-column><el-table-column label="操作" min-width="180"><template #default="{ row }"><el-button link type="primary" @click="edit(row)">编辑</el-button><el-button link @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column></template>
        <template v-else-if="view === 'seats'"><el-table-column prop="seatNo" label="座位编号" min-width="120" /><el-table-column label="座位类型" min-width="120"><template #default="{ row }">{{ typeLabel(row.seatType) }}</template></el-table-column><el-table-column label="插座" width="110"><template #default="{ row }">{{ row.hasPower ? '有插座' : '无插座' }}</template></el-table-column><el-table-column label="区域" min-width="120"><template #default="{ row }">{{ row.isQuiet ? '安静区' : '非安静区' }}</template></el-table-column><el-table-column label="操作" width="140"><template #default="{ row }"><el-button link type="primary" @click="edit(row)">编辑</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column></template>
        <template v-else><el-table-column label="学生" min-width="140"><template #default="{ row }">{{ row.studentName }}<div class="table-sub">{{ row.account }}</div></template></el-table-column><el-table-column label="学习空间" min-width="210"><template #default="{ row }">{{ row.roomName }}<div class="table-sub">{{ row.seatNo }}号 · {{ typeLabel(row.seatType) }}</div></template></el-table-column><el-table-column label="预约时段" min-width="210"><template #default="{ row }">{{ displayTime(row.startTime) }}<div class="table-sub">至 {{ displayTime(row.endTime) }}</div></template></el-table-column><el-table-column label="状态" width="110"><template #default="{ row }"><span class="status-badge" :class="row.status">{{ statusLabel(row.status) }}</span></template></el-table-column></template>
      </el-table><el-pagination v-model:current-page="page" :page-size="10" :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>
    <template v-else><div class="table-toolbar"><el-date-picker v-model="statsDates" type="daterange" value-format="YYYY-MM-DD" :clearable="false" start-placeholder="开始日期" end-placeholder="结束日期" /><el-button type="primary" @click="load">查看统计</el-button><span class="muted">单次最多查询 367 天</span></div><div class="metrics-grid"><article><span>预约总数</span><strong>{{ totals.total }}</strong><small>含已取消预约</small></article><article><span>未取消预约</span><strong>{{ totals.active }}</strong><small>含待开始、进行中及已结束</small></article><article><span>已取消预约</span><strong>{{ totals.cancelled }}</strong><small>随取消操作更新</small></article></div><section class="table-card chart-card"><div class="section-title"><h2>每日预约趋势</h2><div class="legend"><i class="selected"></i>未取消<i class="cancelled"></i>已取消</div></div><div class="daily-chart"><div v-for="day in statistics" :key="day.date" class="daily-row"><span>{{ day.date }}</span><div class="daily-track"><div class="daily-active" :style="{ width: `${day.activeCount / maxCount * 100}%` }"></div><div class="daily-cancelled" :style="{ width: `${day.cancelledCount / maxCount * 100}%` }"></div></div><strong>{{ day.totalCount }}</strong></div></div><p class="tiny">预约日期按北京时间计算；未到场的预约无法在此区分。</p></section></template>
    <el-dialog v-model="dialog" :title="`${editing ? '编辑' : '新增'}${view === 'rooms' ? '自习室' : '座位'}`" width="min(520px, 92vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top" @submit.prevent="save"><template v-if="view === 'rooms'"><el-form-item label="自习室名称"><el-input v-model="roomForm.name" maxlength="100" /></el-form-item><el-form-item label="位置"><el-input v-model="roomForm.location" maxlength="200" /></el-form-item><div class="form-two-columns"><el-form-item label="开放时间"><el-time-select v-model="roomForm.openTime" start="00:00" step="00:30" end="23:30" /></el-form-item><el-form-item label="关闭时间"><el-time-select v-model="roomForm.closeTime" start="00:00" step="00:30" end="23:30" /></el-form-item></div></template><template v-else><el-form-item label="座位编号"><el-input v-model="seatForm.seatNo" maxlength="32" /></el-form-item><el-form-item label="座位类型"><el-select v-model="seatForm.seatType"><el-option v-for="type in ['WINDOW', 'AISLE', 'MIDDLE']" :key="type" :value="type" :label="typeLabel(type)" /></el-select></el-form-item><el-form-item label="座位属性"><el-checkbox v-model="seatForm.hasPower">有插座</el-checkbox><el-checkbox v-model="seatForm.isQuiet">安静区</el-checkbox></el-form-item></template><el-alert title="有未结束预约时，影响预约的修改会被阻止。" :closable="false" type="info" /><div class="dialog-footer"><el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">保存</el-button></div></el-form>
    </el-dialog>
  </div>
</template>

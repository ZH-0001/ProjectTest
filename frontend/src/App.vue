<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { session, restore, post, refreshCsrf } from './api'
import AuthView from './components/AuthView.vue'
import StudentView from './components/StudentView.vue'
import AdminView from './components/AdminView.vue'
import AssistantView from './components/AssistantView.vue'
const view = ref('rooms')
const admin = computed(() => session.user?.role === 'ADMIN')
const menus = computed(() => admin.value ? [{ id: 'rooms', icon: '▦', name: '自习室管理' }, { id: 'seats', icon: '▤', name: '座位管理' }, { id: 'reservations', icon: '◷', name: '预约记录' }, { id: 'statistics', icon: '▥', name: '每日统计' }] : [{ id: 'rooms', icon: '▦', name: '发现自习室' }, { id: 'recommend', icon: '✧', name: '为你推荐' }, { id: 'assistant', icon: '✦', name: '预约助手' }, { id: 'reservations', icon: '◷', name: '我的预约' }])
watch(() => session.user?.id, () => { view.value = 'rooms' })
async function logout() { try { await post('/user/logout', {}); session.user = null; await refreshCsrf() } catch (e) { ElMessage.error(e.message) } }
onMounted(restore)
</script>
<template>
  <div v-if="!session.ready" class="loading-screen">正在连接静习…</div>
  <AuthView v-else-if="!session.user" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="#" @click.prevent="view = 'rooms'"><span class="brand-symbol">习</span><span>静习<small>STUDY, AT YOUR PACE.</small></span></a>
      <div class="sidebar-label">{{ admin ? '空间管理' : '我的学习空间' }}</div>
      <nav><button v-for="menu in menus" :key="menu.id" :class="{ active: view === menu.id }" @click="view = menu.id"><span>{{ menu.icon }}</span>{{ menu.name }}<span v-if="view === menu.id" class="nav-dot"></span></button></nav>
      <div class="sidebar-note"><span>一席安静，一段专注。</span><p>为每一次认真学习，<br>留一个合适的位置。</p></div>
      <div class="profile"><div class="avatar">{{ session.user.name.slice(0, 1) }}</div><div><strong>{{ session.user.name }}</strong><small>{{ admin ? '系统管理员' : session.user.account }}</small></div><button class="logout" aria-label="退出登录" title="退出登录" @click="logout">↗</button></div>
    </aside>
    <main class="workspace"><header class="topbar"><span>校园自习室预约 <span class="breadcrumb">/ {{ menus.find(m => m.id === view)?.name }}</span></span><span class="topbar-badge">{{ admin ? '管理工作台' : '学生端' }}<i></i><button class="mobile-logout" aria-label="退出登录" @click="logout">退出</button></span></header><AdminView v-if="admin" :view="view" /><AssistantView v-else-if="view === 'assistant'" /><StudentView v-else :view="view" /></main>
  </div>
</template>

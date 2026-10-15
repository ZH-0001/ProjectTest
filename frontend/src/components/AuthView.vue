<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { post, refreshCsrf, session } from '../api'
const registering = ref(false), busy = ref(false)
const form = reactive({ account: '', name: '', password: '' })
async function submit() {
  if (!form.account.trim() || !form.password || (registering.value && !form.name.trim())) return ElMessage.warning('请完整填写信息')
  busy.value = true
  try {
    if (registering.value) { await post('/user/register', { ...form }); ElMessage.success('注册成功，请登录'); registering.value = false; form.password = '' }
    else { const user = await post('/user/login', { account: form.account, password: form.password }); await refreshCsrf(); session.user = user }
  } catch (e) { ElMessage.error(e.message) } finally { busy.value = false }
}
</script>
<template>
  <div class="auth-page">
    <section class="auth-story"><div class="brand light"><span class="brand-symbol">习</span><span>静习<small>STUDY, AT YOUR PACE.</small></span></div><div class="story-content"><span class="eyebrow">让专注，有处安放</span><h1>找到你的<br>下一席<span>安静。</span></h1><p>从选座到预约，把更多时间留给学习。<br>为你的习惯，找到合适的自习空间。</p><div class="desk-illustration"><div class="window-frame"><i></i><i></i><i></i><i></i></div><div class="desk-lamp"></div><div class="book-stack"></div><div class="desk-line"></div><div class="chair"></div></div></div><div class="story-footer">高校自习室智能预约系统 · 预约你的专注时光</div></section>
    <section class="auth-form"><div class="auth-box"><span class="eyebrow">欢迎来到静习</span><h2>{{ registering ? '创建学生账号' : '开始你的学习计划' }}</h2><p class="muted">{{ registering ? '使用学号注册，已有账号可直接登录。' : '登录后，发现适合自己的自习室。' }}</p><el-form label-position="top" @submit.prevent="submit"><el-form-item label="学号 / 管理员账号"><el-input v-model="form.account" maxlength="32" placeholder="请输入账号" autocomplete="username" /></el-form-item><el-form-item v-if="registering" label="姓名"><el-input v-model="form.name" maxlength="50" placeholder="请输入姓名" autocomplete="name" /></el-form-item><el-form-item label="密码"><el-input v-model="form.password" type="password" show-password :placeholder="registering ? '至少 8 个字符' : '请输入密码'" :autocomplete="registering ? 'new-password' : 'current-password'" /></el-form-item><el-button class="full-button" type="primary" native-type="submit" :loading="busy">{{ registering ? '注册账号' : '登录静习' }} <span>→</span></el-button></el-form><div class="auth-switch">{{ registering ? '已经有账号？' : '第一次使用？' }}<button @click="registering = !registering">{{ registering ? '返回登录' : '注册学生账号' }}</button></div><div class="auth-footnote">预约、管理与个性化推荐，在一个地方完成。</div></div></section>
  </div>
</template>

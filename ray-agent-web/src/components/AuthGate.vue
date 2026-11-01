<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { readSession, login, logout as endSession } from '../api/auth.js'
import BrandMark from './BrandMark.vue'
const session = ref(null)
const username = ref('owner')
const password = ref('')
const error = ref('')
const busy = ref(false)
async function refresh() { try { session.value = await readSession(); error.value = '' } catch (e) { error.value = e.message } }
async function submit() {
  if (busy.value) return
  busy.value = true
  try { session.value = await login(username.value, password.value); error.value = '' }
  catch (e) { error.value = e.message }
  finally { password.value = ''; busy.value = false }
}
async function logout() {
  if (busy.value) return
  busy.value = true
  try { session.value = await endSession(); error.value = '' }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
function expired() { session.value = { authenticated: false }; error.value = '登录已过期，请重新登录' }
onMounted(() => { window.addEventListener('ray-auth-required', expired); refresh() })
onUnmounted(() => window.removeEventListener('ray-auth-required', expired))
</script>
<template>
  <template v-if="session?.authenticated">
    <p v-if="error" class="session-error" role="alert">{{ error }}</p>
    <slot :user="session.username" :logout="logout" />
  </template>
  <main v-else class="login-screen"><div class="login-brand"><BrandMark/><span>RayAgent</span></div><section class="login-panel">
    <BrandMark/><h1>登录 RayAgent</h1>
    <p class="login-intro">继续你的对话，查阅你的资料。</p>
    <form @submit.prevent="submit">
      <label for="login-username">账号</label><input id="login-username" v-model="username" autocomplete="username" maxlength="40" :disabled="busy" />
      <label for="login-password">密码</label><input id="login-password" v-model="password" type="password" autocomplete="current-password" :disabled="busy" />
      <div class="login-actions"><button class="primary" :disabled="busy || !username || !password || !session">{{ busy ? '正在登录…' : '登录' }}</button><button type="button" :disabled="busy" @click="refresh">刷新登录状态</button></div>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <details class="login-help"><summary>首次登录说明</summary><p>使用你的个人账号进入聊天与管理后台。首次启动的登录信息在服务端本机 <code>.ray-data/identity/initial-login.txt</code>。</p></details>
  </section></main>
</template>

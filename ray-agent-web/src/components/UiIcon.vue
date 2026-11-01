<script setup>
defineProps({ name: String, size: { type: [Number, String], default: 20 } })
// 同一套细线图标只用于操作；不请求外部图标字体，也不借用其他产品的标识。
const paths = {
  chat: ['M5 5h14v11H9l-4 4V5Z'],
  book: ['M4 5h6a3 3 0 0 1 2 1 3 3 0 0 1 2-1h6v14h-6a3 3 0 0 0-2 1 3 3 0 0 0-2-1H4V5Z', 'M12 6v14'],
  activity: ['M3 12h4l3-7 4 14 3-7h4'],
  settings: ['M4 7h16M4 17h16', 'M9 4v6M15 14v6'],
  plus: ['M12 5v14M5 12h14'],
  close: ['m6 6 12 12M18 6 6 18'],
  menu: ['M4 6h16M4 12h16M4 18h16'],
  panel: ['M4 4h16v16H4zM15 4v16'],
  arrow: ['M12 19V5m-5 5 5-5 5 5'],
  moon: ['M20 13a8 8 0 0 1-9-9 8 8 0 1 0 9 9Z'],
  sun: ['M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 1.4 1.4m10 10 1.4 1.4M5.6 18.4 1.4-1.4m10-10 1.4-1.4'],
  logout: ['M10 4H5v16h5M10 12h11m-4-4 4 4-4 4'],
  copy: ['M8 8h12v12H8zM16 8V4H4v12h4'],
  check: ['m5 12 4 4L19 6'],
  chevron: ['m8 5 7 7-7 7'],
}
</script>
<template><svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle v-if="name === 'sun'" cx="12" cy="12" r="4"/><path v-for="(d, i) in paths[name] || paths.chat" :key="i" :d="d"/></svg></template>

<script setup>
import { ref, watch } from 'vue'
const props = defineProps({ question: Object, disabled: Boolean })
const emit = defineEmits(['answer'])
const answer = ref('')
watch(() => props.question?.id, () => { answer.value = '' })
</script>

<template>
  <section v-if="question" aria-label="待补充信息">
    <template v-if="question.status === 'WAITING_USER'">
      <h2>需要你补充信息</h2>
      <p>{{ question.question }}</p>
      <p>当前运行已暂停；回答后继续同一次运行。有效期至 {{ new Date(question.expiresAt).toLocaleString() }}</p>
      <form @submit.prevent="emit('answer', answer.trim())">
        <label for="user-answer">你的补充</label>
        <textarea id="user-answer" v-model="answer" maxlength="2000" :disabled="disabled" rows="3" />
        <button type="submit" class="primary" :disabled="disabled || !answer.trim()">回答并继续</button>
      </form>
    </template>
    <p v-else-if="question.status === 'ANSWERED'">你已补充：{{ question.answer }}</p>
    <p v-else>该提问已{{ question.status === 'EXPIRED' ? '过期' : '取消' }}。</p>
  </section>
</template>

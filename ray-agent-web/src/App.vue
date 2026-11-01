<script setup>
import AuthGate from './components/AuthGate.vue'
import WorkspaceShell from './components/WorkspaceShell.vue'
import './styles/app.css'
</script>
<template>
  <AuthGate v-slot="{ user, logout }"><WorkspaceShell :user="user" :logout="logout"/></AuthGate>
</template>

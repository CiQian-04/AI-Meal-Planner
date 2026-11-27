<script setup lang="ts">
import { ref } from 'vue'
import type { PlanRequest } from '@/types'

const props = defineProps<{
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'submit', request: PlanRequest): void
}>()

const days = ref(3)
const avoidSpicy = ref(true)
const budget = ref(100)
const mealsPerDay = ref(3)
const servings = ref(2)
const preferenceInput = ref('')
const preferences = ref<string[]>([])
const excludedInput = ref('')
const excluded = ref<string[]>([])

function addPreference() {
  const value = preferenceInput.value.trim()
  if (value && !preferences.value.includes(value)) {
    preferences.value.push(value)
  }
  preferenceInput.value = ''
}

function addExcluded() {
  const value = excludedInput.value.trim()
  if (value && !excluded.value.includes(value)) {
    excluded.value.push(value)
  }
  excludedInput.value = ''
}

function removePreference(index: number) {
  preferences.value.splice(index, 1)
}

function removeExcluded(index: number) {
  excluded.value.splice(index, 1)
}

function submit() {
  emit('submit', {
    days: days.value,
    avoidSpicy: avoidSpicy.value,
    budget: budget.value,
    mealsPerDay: mealsPerDay.value,
    servings: servings.value,
    preferences: [...preferences.value],
    excludedIngredients: [...excluded.value],
  })
}
</script>

<template>
  <form class="panel" @submit.prevent="submit">
    <h2 class="panel-title">规划条件</h2>

    <div class="form-grid">
      <div class="field">
        <label for="days">天数</label>
        <input id="days" v-model.number="days" type="number" min="1" max="14" />
      </div>
      <div class="field">
        <label for="budget">预算（元）</label>
        <input id="budget" v-model.number="budget" type="number" min="10" step="10" />
      </div>
      <div class="field">
        <label for="meals">每天几道菜</label>
        <input id="meals" v-model.number="mealsPerDay" type="number" min="1" max="5" />
      </div>
      <div class="field">
        <label for="servings">用餐人数</label>
        <input id="servings" v-model.number="servings" type="number" min="1" max="10" />
      </div>
    </div>

    <div class="switch-row">
      <label class="switch">
        <input v-model="avoidSpicy" type="checkbox" />
        <span class="slider"></span>
      </label>
      <span>不吃辣</span>
    </div>

    <div class="form-grid" style="margin-top: 18px">
      <div class="field">
        <label for="preference">口味偏好</label>
        <div class="chip-input">
          <input
            id="preference"
            v-model="preferenceInput"
            type="text"
            placeholder="如：清淡、少油"
            @keyup.enter="addPreference"
          />
          <button type="button" class="btn btn-ghost btn-sm" @click="addPreference">添加</button>
          <span v-for="(item, index) in preferences" :key="item" class="chip">
            {{ item }}
            <button type="button" @click="removePreference(index)">×</button>
          </span>
        </div>
      </div>

      <div class="field">
        <label for="excluded">忌口食材</label>
        <div class="chip-input">
          <input
            id="excluded"
            v-model="excludedInput"
            type="text"
            placeholder="如：香菜、内脏"
            @keyup.enter="addExcluded"
          />
          <button type="button" class="btn btn-ghost btn-sm" @click="addExcluded">添加</button>
          <span v-for="(item, index) in excluded" :key="item" class="chip">
            {{ item }}
            <button type="button" @click="removeExcluded(index)">×</button>
          </span>
        </div>
      </div>
    </div>

    <div class="actions">
      <button type="submit" class="btn btn-primary" :disabled="props.loading">
        <span v-if="props.loading" class="spinner"></span>
        {{ props.loading ? 'AI 规划中…' : '生成菜谱' }}
      </button>
      <button
        type="button"
        class="btn btn-ghost"
        :disabled="props.loading"
        @click="days = 3; avoidSpicy = true; budget = 100; mealsPerDay = 3; servings = 2; preferences = []; excluded = []"
      >
        重置
      </button>
    </div>
  </form>
</template>

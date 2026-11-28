<script setup lang="ts">
import type { Recipe } from '@/types'
import { formatQuantity } from '@/utils/format'

defineProps<{
  recipe: Recipe
}>()
</script>

<template>
  <article class="recipe-card">
    <div class="recipe-top">
      <h4 class="recipe-name">{{ recipe.name }}</h4>
      <div class="recipe-meta">
        <span class="tag">{{ recipe.category }}</span>
        <span class="tag">{{ recipe.cookingMinutes }} 分钟</span>
        <span class="tag">{{ recipe.servings }} 人份</span>
        <span class="tag spicy-none">{{ recipe.spicyLevel === 'none' ? '不辣' : recipe.spicyLevel }}</span>
      </div>
    </div>

    <p v-if="recipe.description" class="recipe-desc">{{ recipe.description }}</p>

    <ul v-if="recipe.ingredients?.length" class="ingredients">
      <li v-for="(ing, index) in recipe.ingredients" :key="`${ing.name}-${index}`">
        {{ ing.name }} {{ formatQuantity(ing.quantity, ing.unit) }}
      </li>
    </ul>

    <ol v-if="recipe.steps?.length" class="steps">
      <li v-for="(step, index) in recipe.steps" :key="index">{{ step }}</li>
    </ol>
  </article>
</template>

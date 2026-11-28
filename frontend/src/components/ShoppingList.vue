<script setup lang="ts">
import { computed } from 'vue'
import type { ShoppingItem } from '@/types'
import { formatQuantity } from '@/utils/format'

const props = defineProps<{
  items: ShoppingItem[]
  checked: Set<string>
}>()

const emit = defineEmits<{
  (e: 'toggle', name: string): void
}>()

/** Group by shopping category, preserving the backend's ordering within each group. */
const grouped = computed(() => {
  const map = new Map<string, ShoppingItem[]>()
  for (const item of props.items) {
    const list = map.get(item.category) ?? []
    list.push(item)
    map.set(item.category, list)
  }
  return [...map.entries()]
})

const checkedCost = computed(() =>
  props.items
    .filter((item) => props.checked.has(item.name))
    .reduce((sum, item) => sum + item.estimatedCost, 0),
)

const remainingCost = computed(() =>
  props.items
    .filter((item) => !props.checked.has(item.name))
    .reduce((sum, item) => sum + item.estimatedCost, 0),
)

function isChecked(name: string): boolean {
  return props.checked.has(name)
}
</script>

<template>
  <div>
    <div class="summary-grid" style="margin-bottom: 14px">
      <div class="stat">
        <div class="stat-label">已购金额</div>
        <div class="stat-value ok">¥{{ checkedCost.toFixed(2) }}</div>
      </div>
      <div class="stat">
        <div class="stat-label">待购金额</div>
        <div class="stat-value">¥{{ remainingCost.toFixed(2) }}</div>
      </div>
      <div class="stat">
        <div class="stat-label">已勾选</div>
        <div class="stat-value">{{ checked.size }} / {{ items.length }}</div>
      </div>
    </div>

    <div v-for="[category, groupItems] in grouped" :key="category">
      <div class="shop-group-title">{{ category }}</div>
      <label
        v-for="item in groupItems"
        :key="item.name"
        class="shop-item"
      >
        <input
          type="checkbox"
          :checked="isChecked(item.name)"
          @change="emit('toggle', item.name)"
        />
        <span class="shop-name" :class="{ checked: isChecked(item.name) }">
          {{ item.name }}
          <span class="shop-qty">{{ formatQuantity(item.quantity, item.unit) }}</span>
          <span v-if="item.usedIn?.length" class="shop-qty">· 用于 {{ item.usedIn.join('、') }}</span>
        </span>
        <span class="shop-cost" :class="{ checked: isChecked(item.name) }">
          <template v-if="item.category === '未知'">价格待定</template>
          <template v-else>¥{{ item.estimatedCost.toFixed(2) }}</template>
        </span>
      </label>
    </div>
  </div>
</template>

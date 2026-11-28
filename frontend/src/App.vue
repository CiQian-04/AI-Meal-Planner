<script setup lang="ts">
import { computed, ref } from 'vue'
import PlanForm from '@/components/PlanForm.vue'
import RecipeCard from '@/components/RecipeCard.vue'
import ShoppingList from '@/components/ShoppingList.vue'
import { createPlan } from '@/api/client'
import { formatQuantity } from '@/utils/format'
import type { PlanRequest, PlanResponse } from '@/types'

const loading = ref(false)
const error = ref('')
const response = ref<PlanResponse | null>(null)
const checkedItems = ref<Set<string>>(new Set())
const exportNotice = ref('')

const plan = computed(() => response.value?.plan ?? null)
const budget = computed(() => response.value?.requestEcho?.budget ?? 0)
const packCost = computed(() => response.value?.shoppingPackCost ?? 0)
const overBudget = computed(() => {
  if (!plan.value || !budget.value) return false
  return plan.value.estimatedShoppingCost > budget.value
})
const budgetUsage = computed(() => {
  if (!plan.value || !budget.value) return 0
  return Math.min(100, Math.round((plan.value.estimatedShoppingCost / budget.value) * 100))
})

async function onSubmit(request: PlanRequest) {
  loading.value = true
  error.value = ''
  exportNotice.value = ''
  try {
    response.value = await createPlan(request)
    checkedItems.value = new Set()
  }
  catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
    response.value = null
  }
  finally {
    loading.value = false
  }
}

function toggleItem(name: string) {
  const next = new Set(checkedItems.value)
  if (next.has(name)) {
    next.delete(name)
  }
  else {
    next.add(name)
  }
  checkedItems.value = next
}

/** Build a plain-text shopping list from the current plan. */
function buildExportText(): string {
  const current = response.value
  if (!current) return ''

  const lines: string[] = []
  lines.push('购物清单')
  lines.push(`生成条件：${current.requestEcho.days} 天 / 预算 ¥${current.requestEcho.budget} / ${current.requestEcho.avoidSpicy ? '不吃辣' : '不限口味'}`)
  lines.push('')

  for (const day of current.plan.days) {
    lines.push(`【第 ${day.day} 天】`)
    for (const meal of day.meals) {
      lines.push(`  ${meal.name}（${meal.cookingMinutes} 分钟 / ${meal.servings} 人份）`)
      for (const ing of meal.ingredients) {
        lines.push(`    - ${ing.name} ${formatQuantity(ing.quantity, ing.unit)}`)
      }
    }
    lines.push('')
  }

  lines.push('【采购清单】')
  for (const item of current.shoppingList) {
    const mark = checkedItems.value.has(item.name) ? '[x]' : '[ ]'
    lines.push(`${mark} ${item.name} ${formatQuantity(item.quantity, item.unit)}  ¥${item.estimatedCost.toFixed(2)}`)
  }
  lines.push('')
  lines.push(`合计：¥${current.plan.estimatedShoppingCost.toFixed(2)}（整包购买 ¥${current.shoppingPackCost.toFixed(2)}）/ 预算 ¥${current.requestEcho.budget}`)
  return lines.join('\n')
}

function download() {
  const text = buildExportText()
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `购物清单-${new Date().toISOString().slice(0, 10)}.txt`
  link.click()
  URL.revokeObjectURL(url)
  exportNotice.value = '已下载购物清单'
}

async function copyToClipboard() {
  const text = buildExportText()
  try {
    await navigator.clipboard.writeText(text)
    exportNotice.value = '已复制到剪贴板'
  }
  catch {
    exportNotice.value = '复制失败，请改用下载'
  }
}
</script>

<template>
  <header class="site-header">
    <div class="container header-inner">
      <a class="brand" href="#top" aria-label="好好吃饭，回到首页">
        <span class="brand-mark" aria-hidden="true">食</span>
        <span>好好吃饭<small>MEAL PLANNER</small></span>
      </a>
      <a class="preferences-link" href="#planner">饮食偏好与预算 <span aria-hidden="true">↗</span></a>
    </div>
  </header>

  <main id="top" class="container page-content">
    <section class="welcome">
      <div>
        <p class="eyebrow">A LITTLE MORE DELICIOUS, EVERY DAY</p>
        <h1>把一日三餐，安排得刚刚好。</h1>
        <p class="welcome-copy">告诉我们你的口味、预算和用餐人数，轻松规划一周菜单与采购清单。</p>
      </div>
      <div class="welcome-note" aria-hidden="true"><span>本周</span><strong>吃得<br>有计划</strong><i>✳</i></div>
    </section>

    <div v-if="error" class="alert alert-error" role="alert">{{ error }}</div>

    <section id="planner" class="planner-section">
      <PlanForm :loading="loading" @submit="onSubmit" />
    </section>

    <template v-if="plan">
      <section class="panel results-overview">
        <h2 class="panel-title">
          菜单总览
          <span class="badge">{{ plan.days.length }} 天 · {{ plan.summary }}</span>
        </h2>
        <div class="summary-grid">
          <div class="stat">
            <div class="stat-label">采购预算</div>
            <div class="stat-value">¥{{ budget.toFixed(2) }}</div>
          </div>
          <div class="stat">
            <div class="stat-label">实际预估</div>
            <div class="stat-value" :class="overBudget ? 'over' : 'ok'">
              ¥{{ plan.estimatedShoppingCost.toFixed(2) }}
            </div>
          </div>
          <div class="stat">
            <div class="stat-label" title="超市按整包售卖时的实际支出">整包采购</div>
            <div class="stat-value">¥{{ packCost.toFixed(2) }}</div>
          </div>
          <div class="stat">
            <div class="stat-label">菜品总数</div>
            <div class="stat-value">
              {{ plan.days.reduce((sum, d) => sum + d.meals.length, 0) }}
            </div>
          </div>
          <div class="stat">
            <div class="stat-label">预算占用</div>
            <div class="stat-value" :class="overBudget ? 'over' : 'ok'">{{ budgetUsage }}%</div>
          </div>
        </div>
        <div class="progress-track">
          <div
            class="progress-fill"
            :class="{ over: overBudget }"
            :style="{ width: `${budgetUsage}%` }"
          ></div>
        </div>
        <div v-if="overBudget" class="alert alert-info" style="margin: 12px 0 0">
          预估采购成本超出预算，可减少菜品或份量后重新规划。
        </div>
        <ul v-if="plan.tips?.length" class="tips">
          <li v-for="(tip, index) in plan.tips" :key="index">{{ tip }}</li>
        </ul>
      </section>

      <section class="panel recipes-section">
        <h2 class="panel-title">
          菜谱安排
          <span class="badge">共 {{ plan.days.length }} 天</span>
        </h2>
        <div v-for="day in plan.days" :key="day.day">
          <div class="day-header">
            <h3>第 {{ day.day }} 天 · {{ day.label }}</h3>
            <span class="cost">预估 ¥{{ day.estimatedCost.toFixed(2) }}</span>
          </div>
          <div class="recipe-grid">
            <RecipeCard v-for="(meal, index) in day.meals" :key="`${day.day}-${index}`" :recipe="meal" />
          </div>
        </div>
      </section>

      <section class="panel shopping-section">
        <h2 class="panel-title">
          购物清单
          <span class="badge">{{ response?.shoppingList.length ?? 0 }} 项</span>
        </h2>
        <div class="shopping-layout">
          <ShoppingList :items="response!.shoppingList" :checked="checkedItems" @toggle="toggleItem" />
          <aside>
            <div class="side-card">
              <h4>导出</h4>
              <div class="actions" style="margin-top: 0">
                <button type="button" class="btn btn-primary btn-sm" @click="download">下载 TXT</button>
                <button type="button" class="btn btn-ghost btn-sm" @click="copyToClipboard">复制</button>
              </div>
              <div v-if="exportNotice" style="margin-top: 9px; font-size: 13px; color: var(--accent)">
                {{ exportNotice }}
              </div>
            </div>

            <div class="side-card" style="margin-top: 14px">
              <h4>模型调用的函数</h4>
              <div v-if="!response?.toolCalls?.length" style="font-size: 13px; color: var(--text-muted)">
                本次未记录到函数调用
              </div>
              <div v-for="(call, index) in response?.toolCalls ?? []" :key="index" class="tool-call">
                <span class="name">{{ call.tool }}</span>
                <div style="color: var(--text-muted); margin-top: 3px">{{ call.arguments }}</div>
                <details v-if="call.result" class="tool-result">
                  <summary>查看返回结果</summary>
                  <pre>{{ call.result }}</pre>
                </details>
              </div>
              <div style="font-size: 12px; color: var(--text-muted); margin-top: 8px">
                共 {{ response?.toolCalls?.length ?? 0 }} 次调用
              </div>
            </div>
          </aside>
        </div>
      </section>
    </template>

      <div v-else-if="!loading" class="panel empty">
      <div class="empty-icon">🍜</div>
      <div>填写上方条件，点击「生成菜谱」开始规划</div>
    </div>

    <footer class="footer">
      <a href="/swagger-ui.html">服务说明</a> · 好好吃饭，从认真计划开始
    </footer>
  </main>
</template>

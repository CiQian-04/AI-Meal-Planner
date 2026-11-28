/** Shared types mirroring the backend DTOs. */

export interface Ingredient {
  name: string
  quantity: number
  unit: string
  estimatedCostPerUnit: number | null
}

export interface Recipe {
  name: string
  description: string
  cookingMinutes: number
  servings: number
  category: string
  spicyLevel: string
  ingredients: Ingredient[]
  steps: string[]
}

export interface DayPlan {
  day: number
  label: string
  meals: Recipe[]
  estimatedCost: number
}

export interface MealPlan {
  days: DayPlan[]
  estimatedTotalCost: number
  estimatedShoppingCost: number
  summary: string
  tips: string[]
  toolCallsUsed: string[]
}

export interface ShoppingItem {
  name: string
  quantity: number
  unit: string
  estimatedCost: number
  category: string
  usedIn: string[]
}

export interface ToolInvocation {
  tool: string
  arguments: string
  result: string
}

export interface PlanRequest {
  days: number
  avoidSpicy: boolean
  budget: number
  mealsPerDay: number
  servings: number
  preferences: string[]
  excludedIngredients: string[]
  staplePreference: 'ANY' | 'RICE' | 'NOODLES'
}

export interface PlanResponse {
  plan: MealPlan
  shoppingList: ShoppingItem[]
  toolCalls: ToolInvocation[]
  requestEcho: PlanRequest
  /** What the shopping trip costs when everything is bought in whole packs. */
  shoppingPackCost: number
}

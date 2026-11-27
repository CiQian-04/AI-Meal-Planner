import type { PlanRequest, PlanResponse } from '@/types'

const BASE = import.meta.env.VITE_API_BASE ?? ''

export async function createPlan(request: PlanRequest): Promise<PlanResponse> {
  const response = await fetch(`${BASE}/api/v1/plans`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    let detail = `请求失败（HTTP ${response.status}）`
    try {
      const body = await response.json()
      if (body?.message) detail = body.message
      if (body?.fieldErrors && Object.keys(body.fieldErrors).length) {
        detail += `：${Object.entries(body.fieldErrors)
          .map(([field, message]) => `${field} ${message}`)
          .join('；')}`
      }
    } catch {
      // Response was not JSON; keep the generic message.
    }
    throw new Error(detail)
  }

  return (await response.json()) as PlanResponse
}

export async function fetchCatalog(): Promise<string[]> {
  const response = await fetch(`${BASE}/api/v1/prices/catalog`)
  if (!response.ok) throw new Error('加载食材目录失败')
  const body = await response.json()
  return body.ingredients ?? []
}

/**
 * Formatting helpers for ingredient amounts.
 *
 * The backend normalises every amount into a base unit (grams, millilitres or items), so the
 * display side only has to make it readable: no trailing zeros, no `1.2500g`, and kilograms as soon
 * as the amount is big enough for them.
 */

/** Format a quantity the way it would be written on a shopping list: `1250 g` -> `1.25 kg`. */
export function formatQuantity(quantity: number, unit: string): string {
  if (!Number.isFinite(quantity)) {
    return `${quantity}${unit ?? ''}`
  }

  const suffix = (unit ?? '').trim()
  let value = quantity
  let label = suffix

  if (suffix === 'g' && Math.abs(value) >= 1000) {
    value = value / 1000
    label = 'kg'
  }
  else if (suffix === 'ml' && Math.abs(value) >= 1000) {
    value = value / 1000
    label = 'L'
  }

  const amount = trimNumber(value)
  return label ? `${amount} ${label}` : amount
}

/** Drop the decimals that carry no information: 4.00 -> 4, 4.50 -> 4.5. */
function trimNumber(value: number): string {
  const rounded = Math.round(value * 100) / 100
  if (Number.isInteger(rounded)) {
    return String(rounded)
  }
  return String(rounded).replace(/0+$/, '').replace(/\.$/, '')
}

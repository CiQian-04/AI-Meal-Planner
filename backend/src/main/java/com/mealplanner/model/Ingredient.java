package com.mealplanner.model;

/**
 * One ingredient line inside a recipe.
 *
 * @param name ingredient name, e.g. "鸡胸肉"
 * @param quantity amount for {@code servings} people
 * @param unit unit of measure, e.g. "g", "个", "ml"
 * @param estimatedCostPerUnit optional cached unit price in CNY; filled in by the price tool.
 */
public record Ingredient(
		String name,
		double quantity,
		String unit,
		Double estimatedCostPerUnit) {

	public Ingredient withEstimatedCostPerUnit(Double cost) {
		return new Ingredient(this.name, this.quantity, this.unit, cost);
	}
}

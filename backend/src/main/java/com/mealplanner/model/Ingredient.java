package com.mealplanner.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * One ingredient line inside a recipe.
 *
 * <p>The quantity has to be an absolute amount in a base unit - grams, millilitres or items - so
 * that the shopping list can merge it with the same ingredient from another dish and price it
 * against the pack size of the price database.
 *
 * @param name ingredient name, e.g. "鸡胸肉"
 * @param quantity amount for {@code servings} people
 * @param unit unit of measure, e.g. "g", "个", "ml"
 * @param estimatedCostPerUnit optional cached unit price in CNY; filled in by the price tool.
 */
public record Ingredient(
		@JsonPropertyDescription("食材名称，中文，例如「鸡胸肉」「番茄」") String name,
		@JsonPropertyDescription("实际用量的绝对数值，例如 300 表示 300 克；不要写包装规格或千克数值") double quantity,
		@JsonPropertyDescription("基础单位：重量用 g，体积用 ml，计数用 个/根/枚/片/张；不要写 500g、10个、1L 这类包装规格")
		String unit,
		@JsonPropertyDescription("每包装单位的价格（元），由价格工具提供") Double estimatedCostPerUnit) {

	public Ingredient withEstimatedCostPerUnit(Double cost) {
		return new Ingredient(this.name, this.quantity, this.unit, cost);
	}
}

package com.mealplanner.model;

import java.util.List;

/**
 * Result returned by the {@code lookupIngredientPrice} tool.
 *
 * @param found whether the ingredient is present in the price database
 * @param name normalized ingredient name
 * @param unit price unit, e.g. "500g" or "个"
 * @param unitPrice price per {@code unit}, in CNY
 * @param category shopping category, e.g. "肉类", "蔬菜"
 * @param alternatives nearby ingredients with known prices, useful when the exact match misses
 * @param source where this price was published
 * @param priceDate date the source price applies to, empty for sample prices
 */
public record IngredientPrice(
		boolean found,
		String name,
		String unit,
		double unitPrice,
		String category,
		List<IngredientPrice> alternatives,
		String source,
		String priceDate) {

	public IngredientPrice(boolean found, String name, String unit, double unitPrice, String category,
			List<IngredientPrice> alternatives) {
		this(found, name, unit, unitPrice, category, alternatives, "项目内示例价格", null);
	}

	public static IngredientPrice notFound(String name, List<IngredientPrice> alternatives) {
		return new IngredientPrice(false, name, "", 0.0, "未知", alternatives, "", null);
	}
}

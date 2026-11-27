package com.mealplanner.model;

/**
 * A single line on the shopping list.
 *
 * @param name ingredient name
 * @param quantity total quantity to buy
 * @param unit unit of measure
 * @param estimatedCost estimated cost in CNY
 * @param category shopping category
 * @param usedIn recipes that consume this ingredient
 */
public record ShoppingItem(
		String name,
		double quantity,
		String unit,
		double estimatedCost,
		String category,
		java.util.List<String> usedIn) {
}

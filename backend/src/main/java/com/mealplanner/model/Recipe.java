package com.mealplanner.model;

import java.util.List;

/**
 * A single dish in the generated meal plan.
 *
 * <p>Kept deliberately flat so that {@code BeanOutputConverter} can derive a compact JSON schema
 * that small models handle reliably.
 */
public record Recipe(
		String name,
		String description,
		int cookingMinutes,
		int servings,
		/** e.g. "main", "side", "soup" */
		String category,
		/** Human readable spice level, "none" when the plan excludes spicy food. */
		String spicyLevel,
		List<Ingredient> ingredients,
		List<String> steps) {
}

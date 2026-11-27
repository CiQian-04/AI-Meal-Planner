package com.mealplanner.model;

import java.util.List;

/**
 * One day's worth of meals.
 */
public record DayPlan(
		int day,
		String label,
		List<Recipe> meals,
		/** Sum of the estimated ingredient cost for this day, in CNY. */
		double estimatedCost) {
}

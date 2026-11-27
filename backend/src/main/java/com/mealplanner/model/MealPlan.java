package com.mealplanner.model;

import java.util.List;

/**
 * The complete structured output of a meal planning request.
 */
public record MealPlan(
		List<DayPlan> days,
		/** Total estimated ingredient cost across all days, in CNY. */
		double estimatedTotalCost,
		/** Total shopping list cost after merging duplicate ingredients, in CNY. */
		double estimatedShoppingCost,
		/** Short human readable summary, e.g. "三天三菜一汤，全部清淡下饭". */
		String summary,
		List<String> tips,
		/** Names of the price tools the model invoked while planning. */
		List<String> toolCallsUsed) {

	public MealPlan withToolCallsUsed(List<String> calls) {
		return new MealPlan(this.days, this.estimatedTotalCost, this.estimatedShoppingCost, this.summary, this.tips, calls);
	}
}

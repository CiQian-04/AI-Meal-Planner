package com.mealplanner.model;

import java.util.List;

/**
 * Full response returned to the frontend.
 *
 * @param plan the generated meal plan
 * @param shoppingList merged shopping list ready for export
 * @param toolCalls the function calls the model actually made, for transparency
 * @param requestEcho the normalized request that produced this plan
 */
public record PlanResponse(
		MealPlan plan,
		List<ShoppingItem> shoppingList,
		List<ToolInvocation> toolCalls,
		PlanRequest requestEcho) {

	/**
	 * A single function call made by the model.
	 *
	 * @param tool name of the invoked tool
	 * @param arguments the raw JSON arguments the model passed
	 * @param result a short preview of the tool result
	 */
	public record ToolInvocation(String tool, String arguments, String result) {
	}
}

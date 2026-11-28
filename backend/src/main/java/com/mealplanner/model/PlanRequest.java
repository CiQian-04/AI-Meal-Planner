package com.mealplanner.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.List;

/**
 * User input for a planning request.
 *
 * @param days how many days to plan for
 * @param avoidSpicy whether the plan must avoid spicy food
 * @param budget total budget in CNY
 * @param mealsPerDay number of dishes per day
 * @param servings number of people to feed
 * @param preferences free-form extra preferences, e.g. "清淡", "少油"
 * @param excludedIngredients ingredient names the user refuses to eat
 */
public record PlanRequest(
		@NotNull @Min(1) @Max(14) Integer days,
		@NotNull Boolean avoidSpicy,
		@NotNull @Min(10) @Max(100000) Double budget,
		@Min(1) @Max(5) Integer mealsPerDay,
		@Min(1) @Max(10) Integer servings,
		List<String> preferences,
		List<String> excludedIngredients,
		@Pattern(regexp = "ANY|RICE|NOODLES") String staplePreference) {

	public PlanRequest(Integer days, Boolean avoidSpicy, Double budget, Integer mealsPerDay, Integer servings,
			List<String> preferences, List<String> excludedIngredients) {
		this(days, avoidSpicy, budget, mealsPerDay, servings, preferences, excludedIngredients, "ANY");
	}

	public int daysOrDefault() {
		return this.days == null ? 3 : this.days;
	}

	public int mealsPerDayOrDefault() {
		return this.mealsPerDay == null ? 3 : this.mealsPerDay;
	}

	public int servingsOrDefault() {
		return this.servings == null ? 2 : this.servings;
	}

	public List<String> preferencesOrEmpty() {
		return this.preferences == null ? List.of() : this.preferences;
	}

	public List<String> excludedOrEmpty() {
		return this.excludedIngredients == null ? new ArrayList<>() : this.excludedIngredients;
	}

	public boolean avoidSpicyOrDefault() {
		return this.avoidSpicy == null || this.avoidSpicy;
	}
}

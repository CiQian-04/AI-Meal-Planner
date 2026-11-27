package com.mealplanner.service;

import com.mealplanner.model.Ingredient;
import com.mealplanner.model.IngredientPrice;
import com.mealplanner.model.MealPlan;
import com.mealplanner.model.Recipe;
import com.mealplanner.model.ShoppingItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds the shopping list from a generated meal plan.
 *
 * <p>Duplicate ingredients are merged into a single line, and each line is priced from the same
 * database the {@code lookupIngredientPrice} tool exposes, so the number shown in the UI always
 * matches what the model was told during planning.
 */
@Component
public class ShoppingListService {

	private final IngredientPriceService priceService;

	public ShoppingListService(IngredientPriceService priceService) {
		this.priceService = priceService;
	}

	public List<ShoppingItem> build(MealPlan plan) {
		Map<String, Accumulator> merged = new LinkedHashMap<>();

		for (var day : plan.days()) {
			for (Recipe recipe : day.meals()) {
				for (Ingredient ingredient : recipe.ingredients()) {
					String key = ingredient.name().trim().toLowerCase(Locale.ROOT);
					Accumulator acc = merged.computeIfAbsent(key,
							k -> new Accumulator(ingredient.name(), ingredient.unit()));
					acc.quantity += ingredient.quantity();
					acc.usedIn.add(recipe.name());
				}
			}
		}

		List<ShoppingItem> items = new ArrayList<>();
		for (Accumulator acc : merged.values()) {
			IngredientPrice price = priceService.findExact(acc.name);
			double cost = price != null
					? priceService.costOf(acc.name, acc.quantity, acc.unit)
					: 0.0;
			items.add(new ShoppingItem(
					acc.name,
					round(acc.quantity),
					acc.unit,
					cost,
					price != null ? price.category() : "未知",
					List.copyOf(acc.usedIn)));
		}

		items.sort((a, b) -> a.category().compareTo(b.category()));
		return items;
	}

	public double totalCost(List<ShoppingItem> items) {
		return round(items.stream().mapToDouble(ShoppingItem::estimatedCost).sum());
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private static final class Accumulator {
		private final String name;
		private final String unit;
		private double quantity;
		private final List<String> usedIn = new ArrayList<>();

		private Accumulator(String name, String unit) {
			this.name = name;
			this.unit = unit;
		}
	}
}

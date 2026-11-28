package com.mealplanner.service;

import com.mealplanner.model.Ingredient;
import com.mealplanner.model.IngredientPrice;
import com.mealplanner.model.MealPlan;
import com.mealplanner.model.Recipe;
import com.mealplanner.model.ShoppingItem;
import com.mealplanner.tool.IngredientPriceTools;
import com.mealplanner.unit.UnitConverter;
import com.mealplanner.unit.UnitConverter.UnitSpec;
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
 *
 * <p>Amounts are grouped per unit family first, because the same ingredient can show up as grams
 * in one dish and as items in another. Each group is priced on its own, and the line total is the
 * sum of them. On top of that the service also reports what the shopping trip really costs when
 * the shop only sells whole packs, which is the number a budget should really be compared with.
 */
@Component
public class ShoppingListService {

	/**
	 * The shopping list together with both cost figures.
	 *
	 * @param items one line per ingredient
	 * @param exactCost cost of exactly the amounts listed
	 * @param packCost cost when the ingredients are bought in whole packs
	 */
	public record ShoppingList(List<ShoppingItem> items, double exactCost, double packCost) {
	}

	private final IngredientPriceService priceService;

	public ShoppingListService(IngredientPriceService priceService) {
		this.priceService = priceService;
	}

	/** Build the shopping list only. */
	public List<ShoppingItem> build(MealPlan plan) {
		return buildDetailed(plan).items();
	}

	/**
	 * Build the shopping list and both cost figures.
	 *
	 * @param plan the generated plan; its ingredients are expected to be normalised already
	 */
	public ShoppingList buildDetailed(MealPlan plan) {
		Map<String, Accumulator> merged = new LinkedHashMap<>();

		for (var day : plan.days()) {
			for (Recipe recipe : day.meals()) {
				for (Ingredient ingredient : recipe.ingredients()) {
					String key = ingredient.name().trim().toLowerCase(Locale.ROOT);
					Accumulator acc = merged.computeIfAbsent(key,
							k -> new Accumulator(ingredient.name()));
					acc.add(ingredient.quantity(), ingredient.unit());
					acc.usedIn.add(recipe.name());
				}
			}
		}

		List<ShoppingItem> items = new ArrayList<>();
		double exactCost = 0.0;
		double packCost = 0.0;

		for (Accumulator acc : merged.values()) {
			IngredientPrice price = priceService.lookup(acc.name);
			double cost = 0.0;
			double packed = 0.0;
			for (Map.Entry<String, Double> bucket : acc.buckets.entrySet()) {
				if (price == null || !price.found()) {
					continue;
				}
				cost += IngredientPriceTools.costFromPrice(price, bucket.getValue(), bucket.getKey());
				packed += packCostOf(price, bucket.getValue(), bucket.getKey());
			}
			exactCost += cost;
			packCost += packed;

			items.add(new ShoppingItem(
					acc.name,
					round(acc.totalQuantity()),
					acc.dominantUnit(),
					round(cost),
					price != null ? price.category() : "未知",
					List.copyOf(acc.usedIn)));
		}

		items.sort((a, b) -> a.category().compareTo(b.category()));
		return new ShoppingList(List.copyOf(items), round(exactCost), round(packCost));
	}

	public double totalCost(List<ShoppingItem> items) {
		return round(items.stream().mapToDouble(ShoppingItem::estimatedCost).sum());
	}

	/**
	 * Cost of buying an amount in whole packs.
	 *
	 * <p>{@code 1250 g} of chicken leg sold in 500 g packs costs three packs, so {@code 37.50} -
	 * not the proportional {@code 31.25} the shopping list shows.
	 */
	private static double packCostOf(IngredientPrice price, double quantity, String unit) {
		UnitSpec requested = UnitConverter.parse(unit);
		UnitSpec pack = UnitConverter.parse(price.unit());
		if (!requested.isKnown() || !UnitConverter.sameFamily(requested, pack)) {
			return 0.0;
		}
		double packSize = pack.baseSize();
		if (packSize <= 0) {
			return 0.0;
		}
		double base = UnitConverter.toBaseUnits(quantity, unit);
		double packs = Math.ceil(base / packSize - 1e-9);
		return Math.max(0.0, packs) * price.unitPrice();
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	/** Collects every appearance of one ingredient, grouped by unit family. */
	private static final class Accumulator {
		private final String name;
		private final Map<String, Double> buckets = new LinkedHashMap<>();
		private final List<String> usedIn = new ArrayList<>();

		private Accumulator(String name) {
			this.name = name;
		}

		private void add(double quantity, String unit) {
			UnitSpec spec = UnitConverter.parse(unit);
			String key = spec.isKnown() ? UnitConverter.canonicalUnit(spec) : (unit == null ? "" : unit.trim());
			double base = spec.isKnown()
					? UnitConverter.toBaseUnits(quantity, unit)
					: quantity;
			buckets.merge(key, base, Double::sum);
		}

		/** Sum of all amounts, which is what the shopping list line shows. */
		private double totalQuantity() {
			return buckets.values().stream().mapToDouble(Double::doubleValue).sum();
		}

		/** The unit of the largest group, so the line reads like the ingredient it describes. */
		private String dominantUnit() {
			String best = "";
			double bestAmount = -1;
			for (Map.Entry<String, Double> bucket : buckets.entrySet()) {
				if (bucket.getValue() > bestAmount) {
					bestAmount = bucket.getValue();
					best = bucket.getKey();
				}
			}
			return best;
		}
	}
}

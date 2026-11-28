package com.mealplanner.service;

import com.mealplanner.model.DayPlan;
import com.mealplanner.model.Ingredient;
import com.mealplanner.model.IngredientPrice;
import com.mealplanner.model.MealPlan;
import com.mealplanner.model.Recipe;
import com.mealplanner.unit.UnitConverter;
import com.mealplanner.unit.UnitConverter.UnitSpec;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns whatever the model wrote into an amount the rest of the app can trust.
 *
 * <p>The model has three habits that all break the arithmetic downstream:
 *
 * <ol>
 * <li>It sometimes reports the <em>pack</em> of the price database as the unit
 * ({@code 1.0 × "500g"}, {@code 0.4 × "10个"}), because that string is what the price tool just
 * showed it.</li>
 * <li>It sometimes reports a kilogram sized number while labelling it {@code g}
 * ({@code 1.25 g} for 1.25 kg of chicken).</li>
 * <li>It mixes units for the same ingredient from one dish to the next.</li>
 * </ol>
 *
 * <p>Every ingredient is rewritten into an absolute amount in one canonical unit (grams,
 * millilitres or items), using the pack size of the price database as the yardstick for what a
 * plausible dish portion looks like. The shopping list then merges and prices those amounts, so
 * the cost on screen is the cost of the food that is actually listed.
 */
@Component
public class QuantityNormalizer {

	/** A main ingredient has to be a noticeable share of the pack it is sold in. */
	private static final double MAIN_MIN_RATIO = 0.05;

	/** Condiments are used in tiny amounts, so a much smaller share is still plausible. */
	private static final double CONDIMENT_MIN_RATIO = 0.002;

	/** Even after the scale correction an amount may not exceed this share of a pack. */
	private static final double MAX_CORRECTED_RATIO = 4.0;

	/** No dish uses less than 50 mg of anything, so such an amount is always a unit mistake. */
	private static final double ABSURDLY_SMALL = 0.05;

	/** A single dish does not need more than three kilos of one ingredient. */
	private static final double MAX_CORRECTED_AMOUNT = 3000.0;

	/** A "fraction of the pack" reading is only kept while it stays inside this window. */
	private static final double MIN_PACK_FRACTION_RATIO = 0.0001;

	private static final double MAX_PACK_FRACTION_RATIO = 20.0;

	/** Categories whose ingredients are used in small amounts. */
	private static final String CONDIMENT_CATEGORY = "调味";

	private final IngredientPriceService priceService;

	public QuantityNormalizer(IngredientPriceService priceService) {
		this.priceService = priceService;
	}

	/** Normalise every recipe of a plan, keeping the rest of the plan untouched. */
	public MealPlan normalize(MealPlan plan) {
		if (plan == null || plan.days() == null) {
			return plan;
		}
		List<DayPlan> days = plan.days().stream()
				.map(day -> new DayPlan(day.day(), day.label(),
						day.meals() == null ? List.of() : day.meals().stream().map(this::normalize).toList(),
						day.estimatedCost()))
				.toList();
		return new MealPlan(days, plan.estimatedTotalCost(), plan.estimatedShoppingCost(),
				plan.summary(), plan.tips(), plan.toolCallsUsed());
	}

	/**
	 * Normalise the ingredients of one recipe.
	 *
	 * <p>The kilogram/gram mix up is only corrected when the numbers make it necessary: the
	 * uncorrected amount has to be implausibly small for that ingredient, and the corrected one
	 * has to be plausible. A dish that lists several implausibly small amounts is treated as
	 * written in kilograms as a whole, while a single main ingredient is enough on its own. A lone
	 * "0.5 g of salt" is left alone, because that is a legitimate amount of salt.
	 */
	public Recipe normalize(Recipe recipe) {
		if (recipe == null || recipe.ingredients() == null || recipe.ingredients().isEmpty()) {
			return recipe;
		}

		List<Candidate> candidates = new ArrayList<>();
		for (Ingredient ingredient : recipe.ingredients()) {
			candidates.add(Candidate.of(ingredient, priceService.lookup(ingredient.name())));
		}

		int suspects = 0;
		for (Candidate candidate : candidates) {
			if (candidate.scaleSuspect) {
				suspects++;
			}
		}
		for (Candidate candidate : candidates) {
			if (!candidate.scaleSuspect) {
				continue;
			}
			boolean apply = candidate.absurdlySmall || suspects >= 2 || !candidate.condiment;
			if (apply) {
				candidate.scaleUp();
			}
		}

		List<Ingredient> ingredients = candidates.stream().map(Candidate::toIngredient).toList();
		return new Recipe(recipe.name(), recipe.description(), recipe.cookingMinutes(), recipe.servings(),
				recipe.category(), recipe.spicyLevel(), ingredients, recipe.steps());
	}

	/** One ingredient on its way from the model output to a canonical amount. */
	private static final class Candidate {

		private final String name;
		private final double originalQuantity;
		private final String originalUnit;
		private final Double originalUnitPrice;
		private final UnitSpec spec;
		private final IngredientPrice price;
		private final double packBase;
		private final boolean condiment;

		private double baseAmount;
		private boolean scaleSuspect;
		private boolean absurdlySmall;

		private Candidate(String name, double originalQuantity, String originalUnit, Double originalUnitPrice,
				UnitSpec spec, IngredientPrice price, double packBase, boolean condiment, double baseAmount) {
			this.name = name;
			this.originalQuantity = originalQuantity;
			this.originalUnit = originalUnit;
			this.originalUnitPrice = originalUnitPrice;
			this.spec = spec;
			this.price = price;
			this.packBase = packBase;
			this.condiment = condiment;
			this.baseAmount = baseAmount;
		}

		static Candidate of(Ingredient ingredient, IngredientPrice price) {
			UnitSpec spec = UnitConverter.parse(ingredient.unit());
			double quantity = ingredient.quantity();
			double base = UnitConverter.toBaseUnits(quantity, ingredient.unit());

			double packBase = 0.0;
			UnitSpec packSpec = UnitSpec.UNKNOWN;
			boolean condiment = false;
			if (price != null && price.found()) {
				packSpec = UnitConverter.parse(price.unit());
				packBase = packSpec.baseSize();
				condiment = CONDIMENT_CATEGORY.equals(price.category());
			}

			Candidate candidate = new Candidate(ingredient.name(), quantity, ingredient.unit(),
					ingredient.estimatedCostPerUnit(), spec, price, packBase, condiment, base);

			boolean comparable = packBase > 0 && UnitConverter.sameFamily(spec, packSpec);
			if (spec.isKnown() && comparable) {
				if (spec.composite()) {
					// The model echoed the pack size: "1.0 × 500g" means one pack of 500 g.
					// Keep that reading only while it stays in a believable range.
					double ratio = base / packBase;
					if (ratio < MIN_PACK_FRACTION_RATIO || ratio > MAX_PACK_FRACTION_RATIO) {
						candidate.baseAmount = quantity * spec.factor();
					}
				}
				else if (spec.isCount() && quantity > 0 && quantity < 1 && packBase >= 2) {
					// A fraction of an item only makes sense as a fraction of the pack.
					candidate.baseAmount = quantity * packBase;
				}
				else if ((spec.isWeight() || spec.isVolume()) && spec.factor() == 1.0) {
					// Kilogram sized number with a gram label: "1.25 g" really means 1.25 kg.
					double ratio = base / packBase;
					double minRatio = condiment ? CONDIMENT_MIN_RATIO : MAIN_MIN_RATIO;
					boolean tooSmall = ratio < minRatio || base < ABSURDLY_SMALL;
					boolean plausibleAfter = ratio * 1000 >= CONDIMENT_MIN_RATIO
							&& ratio * 1000 <= MAX_CORRECTED_RATIO
							&& base * 1000 <= MAX_CORRECTED_AMOUNT;
					if (tooSmall && plausibleAfter) {
						candidate.scaleSuspect = true;
						candidate.absurdlySmall = base < ABSURDLY_SMALL;
					}
				}
			}
			return candidate;
		}

		/** Multiply the amount by 1000, turning kilograms into grams and litres into millilitres. */
		void scaleUp() {
			baseAmount *= 1000.0;
		}

		/** Rebuild the ingredient with the canonical amount and the real unit price. */
		Ingredient toIngredient() {
			if (!spec.isKnown()) {
				return new Ingredient(name, originalQuantity, originalUnit, originalUnitPrice);
			}
			Double unitPrice = price != null && price.found()
					? Double.valueOf(price.unitPrice())
					: originalUnitPrice;
			return new Ingredient(name, round(baseAmount), UnitConverter.canonicalUnit(spec), unitPrice);
		}

		private static double round(double value) {
			return Math.round(value * 100.0) / 100.0;
		}
	}
}

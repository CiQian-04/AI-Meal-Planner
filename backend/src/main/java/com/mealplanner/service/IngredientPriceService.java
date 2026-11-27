package com.mealplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealplanner.model.IngredientPrice;
import com.mealplanner.tool.IngredientPriceTools;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Read-only access to the ingredient price database used by the tools.
 *
 * <p>The price table itself lives in {@code IngredientPriceTools} so that the model-facing tool and
 * the server-side pricing logic can never drift apart. This service exposes the same data for
 * internal use (shopping list pricing) without going through the JSON tool interface.
 */
@Component
public class IngredientPriceService {

	private final IngredientPriceTools tools;
	private final ObjectMapper objectMapper;

	public IngredientPriceService(IngredientPriceTools tools, ObjectMapper objectMapper) {
		this.tools = tools;
		this.objectMapper = objectMapper;
	}

	/**
	 * Find the price of an ingredient, ignoring JSON serialization.
	 *
	 * @return the price entry, or {@code null} when the ingredient is unknown
	 */
	public IngredientPrice findExact(String name) {
		String json = tools.lookupIngredientPrice(name);
		return parse(json);
	}

	/**
	 * Cost of buying {@code quantity} of {@code unit} of {@code name}, in CNY.
	 *
	 * <p>Returns 0 when the ingredient is not in the database, so an unknown item does not break
	 * the total.
	 */
	public double costOf(String name, double quantity, String unit) {
		IngredientPrice price = findExact(name);
		if (price == null || !price.found()) {
			return 0.0;
		}
		// Uses the same conversion helper as the model-facing tool, so the shopping list total
		// always matches the prices the model was shown.
		return IngredientPriceTools.costFromPrice(price, quantity, unit);
	}

	/** All known ingredient names, useful for a picker UI. */
	public List<String> knownIngredients() {
		return tools.knownIngredientNames();
	}

	private IngredientPrice parse(String json) {
		try {
			return objectMapper.readValue(json, IngredientPrice.class);
		}
		catch (Exception ex) {
			return null;
		}
	}
}

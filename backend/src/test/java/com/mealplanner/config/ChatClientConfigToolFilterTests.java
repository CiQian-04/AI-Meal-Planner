package com.mealplanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealplanner.tool.IngredientPriceTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * The tool surface is the model's temptation surface. Exposing the per-dish estimator makes some
 * reasoning models loop one tool call per dish, so the provider must expose only the batch lookup.
 */
class ChatClientConfigToolFilterTests {

	@Test
	@DisplayName("只向模型暴露批量查价工具")
	void exposesOnlyBatchPriceLookup() {
		ChatClientConfig config = new ChatClientConfig();
		ToolCallbackProvider provider = config.ingredientToolProvider(new IngredientPriceTools(new ObjectMapper()));

		ToolCallback[] callbacks = provider.getToolCallbacks();

		assertThat(callbacks).hasSize(1);
		assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("lookupIngredientPrices");
	}
}

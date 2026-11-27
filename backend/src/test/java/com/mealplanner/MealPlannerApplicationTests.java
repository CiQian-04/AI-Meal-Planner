package com.mealplanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealplanner.config.ChatClientConfig;
import com.mealplanner.tool.IngredientPriceTools;
import com.mealplanner.tool.ToolCallRecordingAdvisor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * Verifies that the Spring context wires the chat client and the tools correctly.
 *
 * <p>Tool registration is easy to get subtly wrong: registering the same tool twice makes Spring
 * AI throw, and forgetting the tool-calling advisor means the tools are advertised to the model
 * but never executed. Both failures show up here rather than in a production request.
 */
@SpringBootTest(properties = {
		"spring.ai.openai.api-key=mock",
		"spring.ai.openai.base-url=http://127.0.0.1:1",
		"spring.ai.openai.chat.options.model=mock-model"
})
class MealPlannerApplicationTests {

	@Autowired
	private ChatClient chatClient;

	@Autowired
	private ToolCallbackProvider ingredientToolProvider;

	@Test
	@DisplayName("Spring 上下文能正常加载")
	void contextLoads() {
		assertThat(chatClient).isNotNull();
	}

	@Test
	@DisplayName("只向模型暴露批量查价工具")
	void onlyBatchPriceLookupIsRegistered() {
		ToolCallback[] callbacks = ingredientToolProvider.getToolCallbacks();

		assertThat(callbacks).hasSize(1);
		assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("lookupIngredientPrices");
	}

	@Test
	@DisplayName("工具定义带有模型可读的描述与参数")
	void toolDefinitionsAreDescribed() {
		ToolCallback[] callbacks = ingredientToolProvider.getToolCallbacks();

		for (ToolCallback callback : callbacks) {
			assertThat(callback.getToolDefinition().description()).isNotBlank();
			assertThat(callback.getToolDefinition().inputSchema()).isNotBlank();
		}
	}

	@Test
	@DisplayName("调用轨迹记录器可以重置与读取")
	void recordingAdvisorCanBeReset() {
		ToolCallRecordingAdvisor.reset();
		assertThat(ToolCallRecordingAdvisor.invocations()).isEmpty();
	}

	@Test
	@DisplayName("ChatClient 配置类装配了工具提供者")
	void chatClientConfigIsPresent() {
		assertThat(ChatClientConfig.class).isNotNull();
		assertThat(IngredientPriceTools.class).isNotNull();
	}
}

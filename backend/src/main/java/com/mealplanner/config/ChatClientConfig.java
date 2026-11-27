package com.mealplanner.config;

import com.mealplanner.tool.IngredientPriceTools;
import com.mealplanner.tool.ToolCallRecordingAdvisor;
import java.util.Arrays;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the chat model, the tool registry and the {@link ChatClient} used by the planner.
 */
@Configuration
public class ChatClientConfig {

	/**
	 * Exposes only the batch price lookup to the model.
	 *
	 * <p>Some reasoning models turn every dish into another {@code estimateDishCost} round, which
	 * multiplies the number of upstream requests and can blow the request budget. Exposing the one
	 * batch lookup keeps the function-calling showcase intact while keeping the tool loop short.
	 */
	@Bean
	public ToolCallbackProvider ingredientToolProvider(IngredientPriceTools priceTools) {
		ToolCallback[] all = MethodToolCallbackProvider.builder().toolObjects(priceTools).build().getToolCallbacks();
		ToolCallback[] priceLookupOnly = Arrays.stream(all)
				.filter(callback -> "lookupIngredientPrices".equals(callback.getToolDefinition().name()))
				.toArray(ToolCallback[]::new);
		return () -> priceLookupOnly;
	}

	/**
	 * A single {@link ChatClient} shared by the whole application.
	 *
	 * <p>Two advisors are involved:
	 *
	 * <ul>
	 * <li>{@link ToolCallRecordingAdvisor} runs first and notes which tools the model asked for.
	 * It has to sit in front of the tool-calling advisor, because that advisor consumes the
	 * intermediate rounds before the application sees them.</li>
	 * <li>{@link ToolCallAdvisor} drives the function-calling loop itself: the model requests a
	 * tool, the matching Java method is executed through the {@link ToolCallingManager}, and the
	 * result is fed back until the model produces a final answer.</li>
	 * </ul>
	 */
	@Bean
	public ChatClient chatClient(ChatModel chatModel, ToolCallbackProvider ingredientToolProvider,
			ToolCallingManager toolCallingManager) {
		return ChatClient.builder(chatModel)
				.defaultToolCallbacks(ingredientToolProvider)
				.defaultAdvisors(
						ToolCallRecordingAdvisorHolder.ADVISOR,
						ToolCallAdvisor.builder().toolCallingManager(toolCallingManager).build())
				.build();
	}

	/**
	 * A tool-free client used as a fallback. Some reasoning models keep re-requesting tools and
	 * never emit the final JSON, so after collecting real prices we ask them again with the
	 * prices already in the prompt and no tools available.
	 */
	@Bean
	public ChatClient plainChatClient(ChatModel chatModel) {
		return ChatClient.builder(chatModel).build();
	}

	/**
	 * Holds the single {@link ToolCallRecordingAdvisor} instance.
	 *
	 * <p>The advisor is stateless apart from a thread local, so one shared instance is enough and
	 * it keeps the advisor ordering explicit.
	 */
	static final class ToolCallRecordingAdvisorHolder {

		static final ToolCallRecordingAdvisor ADVISOR = new ToolCallRecordingAdvisor();

		private ToolCallRecordingAdvisorHolder() {
		}
	}
}

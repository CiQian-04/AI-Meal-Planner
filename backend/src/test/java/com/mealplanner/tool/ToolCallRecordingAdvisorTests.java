package com.mealplanner.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * The recording advisor has to report not just which tools the model asked for, but also what
 * they returned — the tool output only arrives with the following request.
 */
class ToolCallRecordingAdvisorTests {

	private final ToolCallRecordingAdvisor advisor = new ToolCallRecordingAdvisor();

	@AfterEach
	void cleanUp() {
		ToolCallRecordingAdvisor.reset();
	}

	@Test
	@DisplayName("下一轮请求里的工具结果会回填到上一条调用记录")
	void attachesToolOutputToPreviousInvocation() {
		ToolCallRecordingAdvisor.reset();
		// Simulate the first turn: the model requested a tool and no output is known yet.
		seedInvocation("lookupIngredientPrices", "{\"names\":[\"番茄\"]}");
		ToolResponseMessage toolMessage = ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse(
						"call_1", "lookupIngredientPrices", "[{\"found\":true,\"name\":\"番茄\"}]")))
				.build();
		var request = org.springframework.ai.chat.client.ChatClientRequest.builder()
				.prompt(new Prompt(toolMessage))
				.build();

		advisor.before(request, null);

		List<ToolCallRecordingAdvisor.Invocation> invocations = ToolCallRecordingAdvisor.invocations();
		assertThat(invocations).hasSize(1);
		assertThat(invocations.get(0).result()).contains("番茄").contains("found");
	}

	@Test
	@DisplayName("超长工具结果会被截断成预览")
	void truncatesLongResult() {
		ToolCallRecordingAdvisor.reset();
		seedInvocation("lookupIngredientPrices", "{}");

		String longPayload = "x".repeat(500);
		ToolResponseMessage toolMessage = ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "lookupIngredientPrices", longPayload)))
				.build();
		advisor.before(org.springframework.ai.chat.client.ChatClientRequest.builder()
				.prompt(new Prompt(toolMessage)).build(), null);

		assertThat(ToolCallRecordingAdvisor.invocations().get(0).result())
				.hasSizeLessThanOrEqualTo(201)
				.endsWith("…");
	}

	@Test
	@DisplayName("reset 清空当前线程的记录")
	void resetClearsInvocations() {
		seedInvocation("estimateDishCost", "{}");
		assertThat(ToolCallRecordingAdvisor.invocations()).isNotEmpty();

		ToolCallRecordingAdvisor.reset();

		assertThat(ToolCallRecordingAdvisor.invocations()).isEmpty();
	}

	/**
	 * Puts one invocation into the thread-local recording without going through the chat model.
	 */
	private void seedInvocation(String tool, String arguments) {
		ToolCallRecordingAdvisor.seed(tool, arguments);
	}
}

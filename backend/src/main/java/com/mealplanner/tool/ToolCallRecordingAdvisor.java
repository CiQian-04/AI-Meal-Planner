package com.mealplanner.tool;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * Records the tool calls the model requests during a planning round.
 *
 * <p>Spring AI runs the function-calling loop inside {@code ToolCallAdvisor}: the model asks for
 * a tool, the tool is executed, and the result is fed back into the conversation. The response
 * that finally reaches the application only contains the <em>last</em> turn of that loop, so the
 * intermediate tool calls are invisible from the outside.
 *
 * <p>This advisor sits in front of {@code ToolCallAdvisor} and inspects every response that comes
 * back, which is the only place where the individual rounds are still visible.
 */
public class ToolCallRecordingAdvisor implements CallAdvisor, BaseAdvisor {

	private static final Logger log = LoggerFactory.getLogger(ToolCallRecordingAdvisor.class);

	/**
	 * Holds the invocations of the request currently being served on this thread.
	 */
	private static final ThreadLocal<List<Invocation>> CURRENT = ThreadLocal.withInitial(ArrayList::new);

	@Override
	public String getName() {
		return "toolCallRecordingAdvisor";
	}

	/**
	 * Runs right after {@code ToolCallAdvisor} in the chain.
	 *
	 * <p>The order matters: {@code ToolCallAdvisor} rebuilds the chain for every round of the
	 * function-calling loop, keeping only the advisors that come <em>after</em> itself. An advisor
	 * placed before it would therefore only ever see the final response, which carries no tool
	 * calls.
	 */
	@Override
	public int getOrder() {
		return BaseAdvisor.HIGHEST_PRECEDENCE + 301;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain chain) {
		captureResults(chatClientRequest);
		return chatClientRequest;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain chain) {
		record(chatClientResponse);
		return chatClientResponse;
	}

	// adviseCall() is intentionally not overridden: the default implementation in BaseAdvisor
	// calls before() and after() around the rest of the chain, which is what we need.

	/**
	 * Fills in the results of tool calls executed on the previous round.
	 *
	 * <p>The tool output never comes back on the response that asked for it — it is appended to
	 * the conversation as a {@link ToolResponseMessage} and arrives with the <em>next</em> request.
	 * Every round therefore starts by draining whatever the tool layer produced last time.
	 */
	private static void captureResults(ChatClientRequest request) {
		if (request == null || request.prompt() == null) {
			return;
		}
		for (var message : request.prompt().getInstructions()) {
			if (!(message instanceof ToolResponseMessage toolMessage)) {
				continue;
			}
			for (var response : toolMessage.getResponses()) {
				attach(response.responseData());
			}
		}
	}

	/**
	 * Attaches a tool output to the oldest still-unresolved invocation of that tool.
	 */
	private static void attach(String rawResult) {
		if (rawResult == null || rawResult.isBlank()) {
			return;
		}
		List<Invocation> invocations = CURRENT.get();
		for (int i = 0; i < invocations.size(); i++) {
			if (invocations.get(i).result() == null) {
				invocations.set(i, invocations.get(i).withResult(preview(rawResult)));
				return;
			}
		}
	}

	/**
	 * Trims a tool output to a length that is useful in the UI without shipping the whole payload.
	 */
	private static String preview(String raw) {
		String flattened = raw.strip();
		return flattened.length() <= 200 ? flattened : flattened.substring(0, 200) + "…";
	}

	/**
	 * Stores the tool calls contained in the given response, if any.
	 */
	private static void record(ChatClientResponse response) {
		if (response == null || response.chatResponse() == null || !response.chatResponse().hasToolCalls()) {
			return;
		}
		var output = response.chatResponse().getResult().getOutput();
		for (var call : output.getToolCalls()) {
			CURRENT.get().add(new Invocation(call.name(), String.valueOf(call.arguments()), null));
			log.info("Model requested tool: {} with arguments {}", call.name(), call.arguments());
		}
	}

	/**
	 * The tool calls recorded during the current thread's exchange.
	 */
	public static List<Invocation> invocations() {
		return List.copyOf(CURRENT.get());
	}

	/**
	 * Start a fresh recording for the current thread.
	 */
	public static void reset() {
		CURRENT.remove();
	}

	/**
	 * Adds an invocation to the current thread's recording.
	 *
	 * <p>Visible for testing so the recording can be exercised without a running model.
	 */
	static void seed(String tool, String arguments) {
		CURRENT.get().add(new Invocation(tool, arguments, null));
	}

	/**
	 * A recorded function call.
	 *
	 * @param tool name of the invoked tool
	 * @param arguments raw JSON arguments the model passed
	 * @param result preview of the tool result, filled in later if available
	 */
	public record Invocation(String tool, String arguments, String result) {

		public Invocation withResult(String value) {
			return new Invocation(this.tool, this.arguments, value);
		}
	}
}

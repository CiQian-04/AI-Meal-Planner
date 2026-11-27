package com.mealplanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealplanner.model.PlanRequest;
import com.mealplanner.model.PlanResponse;
import com.mealplanner.service.MealPlanService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end test of the function-calling flow against a stub OpenAI compatible server.
 *
 * <p>The stub behaves like a real model that needs prices: it asks for a tool, the backend
 * executes the {@code @Tool} method, and the result is fed back before the final plan is
 * produced. The test therefore proves the whole loop works, not just that the wiring compiles.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FunctionCallingIntegrationTests {

	@Autowired
	private MealPlanService mealPlanService;

	@Autowired
	private TestRestTemplate restTemplate;

	@LocalServerPort
	private int port;

	/**
	 * The stub is started in a static initializer because {@link DynamicPropertySource} is
	 * evaluated while the Spring context is being created, which happens before
	 * {@code @BeforeAll} methods run.
	 */
	private static final StubOpenAiServer stub = startStub();

	private static StubOpenAiServer startStub() {
		try {
			StubOpenAiServer server = new StubOpenAiServer();
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not start the stub OpenAI server", ex);
		}
	}

	@AfterAll
	static void stopStub() {
		stub.stop();
	}

	@DynamicPropertySource
	static void stubProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.ai.openai.base-url", () -> stub.baseUrl());
		registry.add("spring.ai.openai.api-key", () -> "mock");
		registry.add("spring.ai.openai.chat.options.model", () -> "stub-model");
	}

	@Test
	@DisplayName("模型发起函数调用，工具被执行，返回结构化菜谱")
	void modelCallsThePriceToolAndGetsAStructuredPlan() {
		stub.reset();

		PlanResponse response = mealPlanService.plan(new PlanRequest(3, true, 100.0, 3, 2,
				List.of("清淡"), List.of()));

		// The model asked for prices, and the tool really ran.
		assertThat(response.toolCalls()).isNotEmpty();
		assertThat(response.toolCalls().get(0).tool()).isEqualTo("lookupIngredientPrices");
		assertThat(response.toolCalls().get(0).arguments()).contains("番茄");

		// The plan is structured, not free text.
		assertThat(response.plan().days()).hasSize(3);
		assertThat(response.plan().days().get(0).meals()).isNotEmpty();
		assertThat(response.plan().summary()).isNotBlank();

		// Costs came from the tool result, so they are real numbers.
		assertThat(response.plan().estimatedShoppingCost()).isGreaterThan(0);
		assertThat(response.shoppingList()).isNotEmpty();
		assertThat(response.shoppingList().stream().map(item -> item.name())).contains("番茄");
	}

	@Test
	@DisplayName("购物清单合并了重复食材并完成计价")
	void shoppingListMergesDuplicatesAndPricesThem() {
		stub.reset();

		PlanResponse response = mealPlanService.plan(new PlanRequest(3, true, 100.0, 3, 2,
				List.of(), List.of()));

		var tomato = response.shoppingList().stream()
				.filter(item -> item.name().equals("番茄"))
				.findFirst()
				.orElseThrow();

		// 番茄 appears in more than one recipe, so the merged quantity must exceed a single use.
		assertThat(tomato.quantity()).isGreaterThan(300.0);
		assertThat(tomato.usedIn()).isNotEmpty();
		assertThat(tomato.estimatedCost()).isGreaterThan(0);
	}

	@Test
	@DisplayName("REST 接口返回 200 与完整响应体")
	void restEndpointReturnsTheFullResponse() {
		stub.reset();

		var request = new PlanRequest(3, true, 100.0, 3, 2, List.of(), List.of());
		var headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		ResponseEntity<PlanResponse> response = restTemplate.exchange(
				"http://127.0.0.1:" + port + "/api/v1/plans",
				HttpMethod.POST,
				new HttpEntity<>(request, headers),
				PlanResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().plan().days()).hasSize(3);
		assertThat(response.getBody().toolCalls()).isNotEmpty();
	}

	@Test
	@DisplayName("非法参数返回 400")
	void invalidRequestIsRejected() {
		var headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		ResponseEntity<String> response = restTemplate.exchange(
				"http://127.0.0.1:" + port + "/api/v1/plans",
				HttpMethod.POST,
				new HttpEntity<>("{\"days\":99,\"avoidSpicy\":true,\"budget\":-1}", headers),
				String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	/**
	 * Minimal OpenAI compatible server that answers with a tool call first and a plan second.
	 */
	private static final class StubOpenAiServer {

		private final List<String> requests = new CopyOnWriteArrayList<>();
		private com.sun.net.httpserver.HttpServer server;

		void start() throws IOException {
			server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
			server.createContext("/v1/chat/completions", exchange -> {
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				requests.add(body);

				String response = body.contains("\"role\": \"tool\"") || body.contains("\"role\":\"tool\"")
						? finalPlan()
						: toolCall();

				byte[] payload = response.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, payload.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(payload);
				}
			});
			server.start();
		}

		void stop() {
			if (server != null) {
				server.stop(0);
			}
		}

		String baseUrl() {
			return "http://127.0.0.1:" + server.getAddress().getPort();
		}

		void reset() {
			requests.clear();
		}

		/** Asks for the prices of the ingredients the plan will use. */
		private String toolCall() {
			return """
					{
					  "id": "chatcmpl-stub",
					  "object": "chat.completion",
					  "created": 0,
					  "model": "stub-model",
					  "choices": [{
					    "index": 0,
					    "message": {
					      "role": "assistant",
					      "content": null,
					      "tool_calls": [{
					        "id": "call_1",
					        "type": "function",
					        "function": {
					          "name": "lookupIngredientPrices",
					          "arguments": "{\\"names\\": [\\"番茄\\", \\"鸡蛋\\", \\"鸡胸肉\\", \\"西兰花\\"]}"
					        }
					      }]
					    },
					    "finish_reason": "tool_calls"
					  }],
					  "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
					}
					""";
		}

		/** The final answer, shaped like the MealPlan record. */
		private String finalPlan() {
			return """
					{
					  "id": "chatcmpl-stub",
					  "object": "chat.completion",
					  "created": 0,
					  "model": "stub-model",
					  "choices": [{
					    "index": 0,
					    "message": {
					      "role": "assistant",
					      "content": "{\\"days\\":[{\\"day\\":1,\\"label\\":\\"第 1 天\\",\\"meals\\":[{\\"name\\":\\"番茄炒蛋\\",\\"description\\":\\"经典家常菜\\",\\"cookingMinutes\\":15,\\"servings\\":2,\\"category\\":\\"main\\",\\"spicyLevel\\":\\"none\\",\\"ingredients\\":[{\\"name\\":\\"番茄\\",\\"quantity\\":300,\\"unit\\":\\"g\\",\\"estimatedCostPerUnit\\":4.5},{\\"name\\":\\"鸡蛋\\",\\"quantity\\":3,\\"unit\\":\\"个\\",\\"estimatedCostPerUnit\\":0.85}],\\"steps\\":[\\"鸡蛋打散\\",\\"番茄切块炒出汁\\",\\"倒回鸡蛋翻炒\\"]}],\\"estimatedCost\\":7.5},{\\"day\\":2,\\"label\\":\\"第 2 天\\",\\"meals\\":[{\\"name\\":\\"清炒西兰花\\",\\"description\\":\\"低卡高纤维\\",\\"cookingMinutes\\":12,\\"servings\\":2,\\"category\\":\\"side\\",\\"spicyLevel\\":\\"none\\",\\"ingredients\\":[{\\"name\\":\\"西兰花\\",\\"quantity\\":250,\\"unit\\":\\"g\\",\\"estimatedCostPerUnit\\":6.8},{\\"name\\":\\"番茄\\",\\"quantity\\":200,\\"unit\\":\\"g\\",\\"estimatedCostPerUnit\\":4.5}],\\"steps\\":[\\"西兰花焯水\\",\\"大火翻炒\\"]}],\\"estimatedCost\\":5.2},{\\"day\\":3,\\"label\\":\\"第 3 天\\",\\"meals\\":[{\\"name\\":\\"香菇滑鸡\\",\\"description\\":\\"嫩滑少油\\",\\"cookingMinutes\\":25,\\"servings\\":2,\\"category\\":\\"main\\",\\"spicyLevel\\":\\"none\\",\\"ingredients\\":[{\\"name\\":\\"鸡胸肉\\",\\"quantity\\":250,\\"unit\\":\\"g\\",\\"estimatedCostPerUnit\\":9.9},{\\"name\\":\\"香菇\\",\\"quantity\\":150,\\"unit\\":\\"g\\",\\"estimatedCostPerUnit\\":7.9}],\\"steps\\":[\\"鸡胸肉腌制\\",\\"香菇炒香\\",\\"下鸡肉翻炒\\"]}],\\"estimatedCost\\":7.3}],\\"estimatedTotalCost\\":20.0,\\"estimatedShoppingCost\\":0.0,\\"summary\\":\\"三天清淡家常菜\\",\\"tips\\":[\\"食材采购价随季节浮动\\"],\\"toolCallsUsed\\":[]}"
					    },
					    "finish_reason": "stop"
					  }],
					  "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
					}
					""";
		}
	}
}

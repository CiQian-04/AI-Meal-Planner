package com.mealplanner.service;

import com.mealplanner.model.MealPlan;
import com.mealplanner.model.PlanRequest;
import com.mealplanner.model.PlanResponse;
import com.mealplanner.model.ShoppingItem;
import com.mealplanner.tool.ToolCallRecordingAdvisor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.stereotype.Service;

/**
 * Turns a {@link PlanRequest} into a fully priced meal plan.
 *
 * <p>The flow is the showcase of the backend:
 *
 * <ol>
 * <li>The model receives the user's constraints in a system prompt.</li>
 * <li>It decides it needs prices and <em>itself</em> calls {@code lookupIngredientPrice} /
 * {@code lookupIngredientPrices} / {@code estimateDishCost}. Spring AI executes the Java methods
 * and feeds the results back into the conversation.</li>
 * <li>The final answer is converted into a {@link MealPlan} via
 * {@code BeanOutputConverter}, so the response is guaranteed to be valid JSON of the expected
 * shape rather than free text we have to parse hopefully.</li>
 * </ol>
 */
@Service
public class MealPlanService {

	private static final Logger log = LoggerFactory.getLogger(MealPlanService.class);

	private final ChatClient chatClient;
	private final ShoppingListService shoppingListService;

	public MealPlanService(ChatClient chatClient, ShoppingListService shoppingListService) {
		this.chatClient = chatClient;
		this.shoppingListService = shoppingListService;
	}

	public PlanResponse plan(PlanRequest request) {
		// The tool callbacks are registered once on the ChatClient bean, so the model can call
		// them without repeating the wiring here.
		ToolCallRecordingAdvisor.reset();

		CallResponseSpec spec = chatClient.prompt()
				.system(systemPrompt(request))
				.user(userPrompt(request))
				.call();

		// The structured entity and the recorded tool calls together make up the response.
		MealPlan plan = spec.entity(MealPlan.class);
		List<PlanResponse.ToolInvocation> invocations = extractToolCalls();

		List<ShoppingItem> shoppingList = shoppingListService.build(plan);
		double shoppingCost = shoppingListService.totalCost(shoppingList);

		MealPlan enriched = new MealPlan(
				plan.days(),
				plan.estimatedTotalCost(),
				shoppingCost,
				plan.summary(),
				plan.tips(),
				invocations.stream().map(PlanResponse.ToolInvocation::tool).distinct().toList());

		log.info("Generated plan for {} days, budget {} CNY, shopping cost {} CNY, {} tool calls",
				request.daysOrDefault(), request.budget(), shoppingCost, invocations.size());

		return new PlanResponse(enriched, shoppingList, invocations, request);
	}

	/**
	 * Reads the tool calls the model made during the exchange.
	 *
	 * <p>These are recorded by {@link ToolCallRecordingAdvisor}, which sits in front of the
	 * function-calling advisor; the final chat response only carries the last turn, so the
	 * intermediate calls have to be captured separately.
	 */
	private List<PlanResponse.ToolInvocation> extractToolCalls() {
		return ToolCallRecordingAdvisor.invocations().stream()
				.map(invocation -> new PlanResponse.ToolInvocation(
						invocation.tool(),
						invocation.arguments(),
						invocation.result() == null ? "" : invocation.result()))
				.toList();
	}

	private String systemPrompt(PlanRequest request) {
		StringBuilder sb = new StringBuilder();
		sb.append("""
				你是一位专业的家庭菜谱规划师。你的任务是根据用户的预算、天数和口味限制，规划出可执行的家庭菜单。

				工作流程（必须遵守）：
				1. 先为每道菜选出所需食材。
				2. 在给出菜品之前，调用查询价格工具获取真实单价，再据此估算成本。不要凭空编造价格。
				3. 如果某道菜的预估成本明显超出预算，应主动调整菜品或份量，并说明原因。
				4. 最后一次性输出完整的菜单 JSON。

				硬性要求：
				- 严格输出 JSON，不要输出 Markdown 代码块标记或任何解释性文字。
				- estimatedTotalCost 为各道菜成本之和；estimatedShoppingCost 为合并同类食材后的实际采购成本。
				- spicyLevel 在用户要求不吃辣时必须为 "none"。
				""");
		if (request.avoidSpicyOrDefault()) {
			sb.append("- 用户要求不吃辣：所有菜品不得使用辣椒、花椒、辣酱等辣味调料，spicyLevel 一律填 \"none\"。\n");
		}
		sb.append("""
				- 每道菜需要 3-6 个食材，步骤写成 3-6 步，每步一句话。
				- 食材名称使用中文。
				- summary 用一句话概括整体菜单；tips 给出 2-4 条实用建议。
				""");
		return sb.toString();
	}

	private String userPrompt(PlanRequest request) {
		StringBuilder sb = new StringBuilder();
		sb.append("请为我规划 ").append(request.daysOrDefault()).append(" 天的家庭菜单。\n");
		sb.append("- 预算：").append(request.budget()).append(" 元（这是全部 ").append(request.daysOrDefault())
				.append(" 天的食材采购总预算，请严格控制）\n");
		sb.append("- 每天 ").append(request.mealsPerDayOrDefault()).append(" 道菜\n");
		sb.append("- 用餐人数：").append(request.servingsOrDefault()).append(" 人\n");
		sb.append("- 忌口：").append(request.avoidSpicyOrDefault() ? "不吃辣" : "无限制").append("\n");

		List<String> preferences = request.preferencesOrEmpty();
		if (!preferences.isEmpty()) {
			sb.append("- 口味偏好：").append(String.join("、", preferences)).append("\n");
		}
		List<String> excluded = request.excludedOrEmpty();
		if (!excluded.isEmpty()) {
			sb.append("- 绝对不能用的食材：").append(String.join("、", excluded)).append("\n");
		}
		sb.append("\n请先查询价格再给出菜单。");
		return sb.toString();
	}
}

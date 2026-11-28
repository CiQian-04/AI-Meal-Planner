package com.mealplanner.service;

import com.mealplanner.model.MealPlan;
import com.mealplanner.model.PlanRequest;
import com.mealplanner.model.PlanResponse;
import com.mealplanner.tool.ToolCallRecordingAdvisor;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.converter.BeanOutputConverter;
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
 * <li>The final answer is converted into a {@link MealPlan} with a tolerant JSON parser that
 * accepts a reasoning model's preamble or Markdown fence, and the whole exchange is retried
 * a few times when the model returns empty content.</li>
 * </ol>
 */
@Service
public class MealPlanService {

	private static final Logger log = LoggerFactory.getLogger(MealPlanService.class);
	private static final int MAX_ATTEMPTS = 3;

	private final ChatClient chatClient;
	private final ChatClient plainChatClient;
	private final ShoppingListService shoppingListService;
	private final QuantityNormalizer quantityNormalizer;
	private final BeanOutputConverter<MealPlan> outputConverter = new BeanOutputConverter<>(MealPlan.class);

	public MealPlanService(@Qualifier("chatClient") ChatClient chatClient,
			@Qualifier("plainChatClient") ChatClient plainChatClient,
			ShoppingListService shoppingListService,
			QuantityNormalizer quantityNormalizer) {
		this.chatClient = chatClient;
		this.plainChatClient = plainChatClient;
		this.shoppingListService = shoppingListService;
		this.quantityNormalizer = quantityNormalizer;
	}

	public PlanResponse plan(PlanRequest request) {
		EmptyModelResponseException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return callModel(request, attempt);
			}
			catch (EmptyModelResponseException ex) {
				lastFailure = ex;
				if (attempt < MAX_ATTEMPTS) {
					log.warn("Model response unusable (attempt {}/{}) : {}. Retrying with stricter JSON-only instructions.",
							attempt, MAX_ATTEMPTS, ex.getMessage());
				}
			}
		}
		throw lastFailure;
	}

	private PlanResponse callModel(PlanRequest request, int attempt) {
		// Tool callbacks are registered once on the ChatClient bean, so the model can call
		// them without repeating the wiring here.
		ToolCallRecordingAdvisor.reset();

		String user = userPrompt(request);
		if (attempt > 1) {
			user += "\n\n重要：上一步没有拿到合法 JSON。这次严格只输出一个 JSON 对象，"
					+ "第一个字符必须是 {，最后一个字符必须是 }，不要输出任何解释文字、Markdown 或思考过程。";
		}
		// Keep the generated JSON schema in the system prompt, but read the raw text ourselves
		// so reasoning models that prepend a sentence can still be parsed.
		CallResponseSpec spec = chatClient.prompt()
				.system(systemPrompt(request) + "\n\n" + outputConverter.getFormat())
				.user(user)
				.call();

		// content() actually triggers the advisor chain, so read it before collecting tool calls.
		String raw = spec.content();
		List<PlanResponse.ToolInvocation> invocations = extractToolCalls();
		MealPlan plan;
		try {
			plan = parsePlan(raw);
		}
		catch (EmptyModelResponseException ex) {
			if (invocations.isEmpty()) {
				throw ex;
			}
			log.warn("Model produced no JSON after tool calls; asking once more without tools using the collected prices: {}",
					ex.getMessage());
			String enrichedUser = user + "\n\n已查到的真实价格：\n" + formatInvocations(invocations)
					+ "\n\n严禁再调用任何工具。直接输出完整菜单 JSON。";
			String fallback = plainChatClient.prompt()
					.system(systemPrompt(request) + "\n\n" + outputConverter.getFormat())
					.user(enrichedUser)
					.call()
					.content();
			plan = parsePlan(fallback);
		}
		if (plan.days() == null || plan.days().isEmpty()) {
			throw new EmptyModelResponseException("模型返回的菜单为空");
		}

		// The model writes quantities in whatever convention it feels like; normalise them before
		// anything is merged or priced, so every later number is built on a real amount.
		MealPlan normalized = quantityNormalizer.normalize(plan);
		ShoppingListService.ShoppingList shopping = shoppingListService.buildDetailed(normalized);
		double shoppingCost = shopping.exactCost();
		double packCost = shopping.packCost();

		MealPlan enriched = new MealPlan(
				normalized.days(),
				normalized.estimatedTotalCost(),
				shoppingCost,
				rewriteCostFigures(reconcile(normalized.summary(), normalized.estimatedShoppingCost(), shoppingCost),
						shoppingCost, packCost),
				rewriteTips(normalized.tips(), shoppingCost, packCost),
				invocations.stream().map(PlanResponse.ToolInvocation::tool).distinct().toList());

		log.info("Generated plan for {} days, budget {} CNY, shopping cost {} CNY, pack cost {} CNY, {} tool calls",
				request.daysOrDefault(), request.budget(), shoppingCost, packCost, invocations.size());

		return new PlanResponse(enriched, shopping.items(), invocations, request, packCost);
	}

	/** Signals an empty/unparseable model reply that is worth one retry. */
	private static class EmptyModelResponseException extends RuntimeException {
		EmptyModelResponseException(String message) {
			super(message);
		}

		EmptyModelResponseException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private MealPlan parsePlan(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new EmptyModelResponseException("模型返回了空内容");
		}
		String text = stripCodeFence(raw.trim());
		try {
			MealPlan direct = outputConverter.convert(text);
			if (direct != null && direct.days() != null && !direct.days().isEmpty()) {
				return direct;
			}
		}
		catch (Exception ignored) {
			// Fall through to the tolerant extractor below.
		}
		String json = extractFirstJsonObject(text);
		if (json == null) {
			throw new EmptyModelResponseException("模型没有返回 JSON: " + preview(text));
		}
		try {
			return outputConverter.convert(json);
		}
		catch (Exception ex) {
			throw new EmptyModelResponseException("模型返回的 JSON 无法解析: " + ex.getMessage(), ex);
		}
	}

	private static String stripCodeFence(String text) {
		if (!text.startsWith("```")) {
			return text;
		}
		int firstNewline = text.indexOf('\n');
		String body = firstNewline < 0 ? "" : text.substring(firstNewline + 1);
		int closing = body.lastIndexOf("```");
		if (closing >= 0) {
			body = body.substring(0, closing);
		}
		return body.trim();
	}

	/** Extracts the first balanced JSON object, tolerating prose before or after it. */
	static String extractFirstJsonObject(String text) {
		int start = text.indexOf('{');
		if (start < 0) {
			return null;
		}
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int i = start; i < text.length(); i++) {
			char c = text.charAt(i);
			if (inString) {
				if (escaped) {
					escaped = false;
				}
				else if (c == '\\') {
					escaped = true;
				}
				else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
			}
			else if (c == '{') {
				depth++;
			}
			else if (c == '}') {
				depth--;
				if (depth == 0) {
					return text.substring(start, i + 1);
				}
			}
		}
		return null;
	}

	private static String preview(String text) {
		return text.length() <= 120 ? text : text.substring(0, 120) + "...";
	}

	/**
	 * Rewrites any cost figure the model stated in its summary so it matches the authoritative
	 * total computed here.
	 *
	 * <p>The model only sees the per-call tool results; the shopping list is merged and priced
	 * afterwards, and rounding per line versus rounding the sum can differ by a few cents. A
	 * summary that contradicts the shopping list is worse than no summary, so the model's number
	 * is replaced rather than shown alongside ours.
	 */
	static String reconcile(String summary, double modelCost, double actualCost) {
		if (summary == null || summary.isBlank() || Math.abs(modelCost - actualCost) < 0.005) {
			return summary;
		}
		String formal = String.format(java.util.Locale.ROOT, "%.2f", modelCost);
		String actual = String.format(java.util.Locale.ROOT, "%.2f", actualCost);
		return summary.replace(formal, actual);
	}

	/**
	 * Matches the money the model wrote down: either a currency symbol in front of a number
	 * ({@code ¥59.14}) or a number followed by {@code 元}.
	 */
	private static final Pattern COST_FIGURE = Pattern.compile("[¥￥]\\s*\\d+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?\\s*元");

	/**
	 * Replaces every cost figure the model wrote with the number computed here.
	 *
	 * <p>A figure that talks about whole packs is replaced with the pack cost, every other figure
	 * with the exact cost of the shopping list. Budgets are left untouched - repeating the budget
	 * back is fine, claiming a different cost is not.
	 */
	static String rewriteCostFigures(String text, double exactCost, double packCost) {
		if (text == null || text.isBlank()) {
			return text;
		}
		Matcher matcher = COST_FIGURE.matcher(text);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String before = text.substring(Math.max(0, matcher.start() - 16), matcher.start());
			String after = text.substring(matcher.end(), Math.min(text.length(), matcher.end() + 8));
			String raw = matcher.group().trim();
			if (mentionsBudget(before, after)) {
				// The budget is not a cost, repeating it back is fine.
				matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
				continue;
			}
			boolean symbol = raw.startsWith("¥") || raw.startsWith("￥");
			double value = mentionsWholePacks(before) ? packCost : exactCost;
			String money = String.format(java.util.Locale.ROOT, "%.2f", value);
			String replacement = symbol ? "¥" + money : money + " 元";
			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/** The budget may be mentioned before the number ("预算 1000 元") or after it ("1000 元预算"). */
	private static boolean mentionsBudget(String before, String after) {
		return before.contains("预算") || after.stripLeading().startsWith("预算");
	}

	/** Whole pack wording right in front of the number, as in "按整包采购约需 493 元". */
	private static boolean mentionsWholePacks(String before) {
		return before.contains("整包") || before.contains("包装") || before.contains("整袋");
	}

	/**
	 * Rewrites the tips and appends the authoritative cost breakdown.
	 *
	 * <p>Without this the model happily reports a total it made up, which contradicts the number
	 * the shopping list was actually priced at.
	 */
	static List<String> rewriteTips(List<String> tips, double exactCost, double packCost) {
		List<String> result = new ArrayList<>();
		if (tips != null) {
			for (String tip : tips) {
				result.add(rewriteCostFigures(tip, exactCost, packCost));
			}
		}
		result.add(String.format(java.util.Locale.ROOT,
				"本次采购：按实际用量约需 ¥%.2f，按超市整包购买约需 ¥%.2f。",
				exactCost, packCost));
		return result;
	}

	private static String formatInvocations(List<PlanResponse.ToolInvocation> invocations) {
		StringBuilder sb = new StringBuilder();
		for (PlanResponse.ToolInvocation invocation : invocations) {
			sb.append("- ").append(invocation.tool()).append('(').append(invocation.arguments()).append(") -> ")
					.append(invocation.result() == null ? "" : invocation.result()).append('\n');
		}
		return sb.toString();
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
				- 你只能调用 lookupIngredientPrices 这一个工具，并且最多调用一次；拿到价格后必须立即输出最终 JSON。
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
		sb.append("""
				食材用量（必须严格遵守，直接影响采购金额计算）：
				- quantity 是这道菜实际用量的绝对数值，unit 只能是基础单位：重量用 g（克），体积用 ml（毫升），计数用 个/根/枚/片/张/块。
				- 严禁把 unit 写成价格工具返回的包装规格（例如 500g、10个、1L），也严禁用千克数值配克单位（例如 quantity=1.25, unit="g" 是错的，应写 1250 g）。
				- 调料直接写克或毫升（例如 盐 3、生抽 15、食用油 20），不要写 0.003 这种千克数值。
				- description 只描述这道菜本身（例如"金黄酥软、咸香下饭"），不要写成调味说明或操作步骤。
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

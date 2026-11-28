package com.mealplanner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mealplanner.service.MealPlanService;

/**
 * The summary the model writes and the shopping list computed by the backend must never
 * disagree about the total cost.
 */
class SummaryReconcileTests {

	private static String reconcile(String summary, double modelCost, double actualCost) {
		return (String) ReflectionTestUtils.invokeMethod(
				MealPlanService.class, "reconcile", summary, modelCost, actualCost);
	}

	@Test
	@DisplayName("模型报的成本与实算不符时以实算为准")
	void replacesMismatchedAmount() {
		String summary = "3 天共 9 道菜，全部清淡不辣，实测采购成本 ¥59.14";

		assertThat(reconcile(summary, 59.14, 59.15))
				.isEqualTo("3 天共 9 道菜，全部清淡不辣，实测采购成本 ¥59.15");
	}

	@Test
	@DisplayName("金额一致时保持原文")
	void keepsSummaryWhenAmountsAgree() {
		String summary = "实测采购成本 ¥59.15";

		assertThat(reconcile(summary, 59.15, 59.15)).isEqualTo(summary);
	}

	@Test
	@DisplayName("容差内的分钱误差不触发替换")
	void ignoresSubCentDifference() {
		String summary = "实测采购成本 ¥59.14";

		assertThat(reconcile(summary, 59.14, 59.144)).isEqualTo(summary);
	}

	@Test
	@DisplayName("空 summary 原样返回")
	void handlesBlankSummary() {
		assertThat(reconcile(null, 10.0, 20.0)).isNull();
		assertThat(reconcile("  ", 10.0, 20.0)).isEqualTo("  ");
	}

	private static String rewrite(String text, double exactCost, double packCost) {
		return (String) ReflectionTestUtils.invokeMethod(
				MealPlanService.class, "rewriteCostFigures", text, exactCost, packCost);
	}

	@SuppressWarnings("unchecked")
	private static java.util.List<String> rewriteTips(java.util.List<String> tips, double exactCost, double packCost) {
		return (java.util.List<String>) ReflectionTestUtils.invokeMethod(
				MealPlanService.class, "rewriteTips", tips, exactCost, packCost);
	}

	@Test
	@DisplayName("summary 里模型自报的成本被替换成后端算出的数字")
	void rewritesCostFiguresInSummary() {
		String summary = "10 天 30 道菜，估算总成本约 360 元，实际按整包采购约需 493 元";

		assertThat(rewrite(summary, 412.50, 493.00))
				.isEqualTo("10 天 30 道菜，估算总成本约 412.50 元，实际按整包采购约需 493.00 元");
	}

	@Test
	@DisplayName("带货币符号的金额同样被替换")
	void rewritesSymbolAmounts() {
		assertThat(rewrite("实测采购成本 ¥59.14", 59.15, 70.00))
				.isEqualTo("实测采购成本 ¥59.15");
	}

	@Test
	@DisplayName("预算数字不会被当成成本改写")
	void keepsBudgetFigures() {
		assertThat(rewrite("预算 100 元以内搞定", 412.50, 493.00))
				.isEqualTo("预算 100 元以内搞定");
	}

	@Test
	@DisplayName("写在数字后面的预算同样不被改写")
	void keepsBudgetFiguresWrittenAfterTheNumber() {
		String summary = "实际用菜成本约 360 元，按整包采购约 493 元，远低于 1000 元预算。";

		assertThat(rewrite(summary, 412.50, 493.00))
				.isEqualTo("实际用菜成本约 412.50 元，按整包采购约 493.00 元，远低于 1000 元预算。");
	}

	@Test
	@DisplayName("tips 被改写并补上权威金额")
	void rewritesTipsAndAppendsAuthoritativeFigures() {
		java.util.List<String> tips = rewriteTips(java.util.List.of("整包采购约需 493 元"), 412.50, 493.00);

		assertThat(tips).hasSize(2);
		assertThat(tips.get(0)).isEqualTo("整包采购约需 493.00 元");
		assertThat(tips.get(1)).contains("412.50").contains("493.00");
	}
}

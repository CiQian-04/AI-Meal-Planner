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
}

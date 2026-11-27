package com.mealplanner.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reasoning models sometimes answer with a short preamble or a Markdown fence before the JSON.
 * The tolerant extractor must still find the complete JSON object.
 */
class MealPlanServiceParserTests {

	@Test
	@DisplayName("能从解释性文字后面提取 JSON")
	void extractsJsonAfterPreamble() {
		String text = "I'll look up prices first.\n{\"days\":[],\"tips\":[\"ok\"]} Hope this helps.";

		assertThat(MealPlanService.extractFirstJsonObject(text))
				.isEqualTo("{\"days\":[],\"tips\":[\"ok\"]}");
	}

	@Test
	@DisplayName("字符串里的花括号不会提前结束 JSON")
	void ignoresBracesInsideStrings() {
		String text = "prefix {\"summary\":\"预算 {100} 元\",\"days\":[]} suffix";

		assertThat(MealPlanService.extractFirstJsonObject(text))
				.isEqualTo("{\"summary\":\"预算 {100} 元\",\"days\":[]}");
	}

	@Test
	@DisplayName("纯解释文字返回 null，交给重试逻辑处理")
	void returnsNullWhenNoJson() {
		assertThat(MealPlanService.extractFirstJsonObject("I'll look up prices for the ingredients first."))
				.isNull();
	}
}

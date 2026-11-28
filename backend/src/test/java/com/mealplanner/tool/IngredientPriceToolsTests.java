package com.mealplanner.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the model-facing price tool.
 *
 * <p>The tool is the contract the LLM depends on, so its JSON shape and its cost maths must be
 * stable.
 */
class IngredientPriceToolsTests {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final IngredientPriceTools tools = new IngredientPriceTools(objectMapper);

	@Test
	@DisplayName("已知食材返回单价、单位和分类")
	void knownIngredientReturnsPrice() throws Exception {
		JsonNode result = objectMapper.readTree(tools.lookupIngredientPrice("鸡胸肉"));

		assertThat(result.get("found").asBoolean()).isTrue();
		assertThat(result.get("unitPrice").asDouble()).isEqualTo(9.90);
		assertThat(result.get("unit").asText()).isEqualTo("500g");
		assertThat(result.get("category").asText()).isEqualTo("肉禽");
	}

	@Test
	@DisplayName("未知食材返回 found=false 并给出相近建议")
	void unknownIngredientReturnsAlternatives() throws Exception {
		JsonNode result = objectMapper.readTree(tools.lookupIngredientPrice("不存在的稀有食材"));

		assertThat(result.get("found").asBoolean()).isFalse();
		assertThat(result.get("alternatives").isArray()).isTrue();
		assertThat(result.get("alternatives")).isNotEmpty();
	}

	@Test
	@DisplayName("批量查询返回与条目数一致的结果")
	void batchLookupReturnsOneEntryPerName() throws Exception {
		JsonNode result = objectMapper.readTree(tools.lookupIngredientPrices(java.util.List.of("番茄", "鸡蛋", "不存在的菜")));

		assertThat(result).hasSize(3);
		assertThat(result.get(0).get("name").asText()).isEqualTo("番茄");
		assertThat(result.get(1).get("name").asText()).isEqualTo("鸡蛋");
		assertThat(result.get(2).get("found").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("估算一道菜成本：按单位换算并求和")
	void estimateDishCostSumsIngredientCosts() throws Exception {
		// 鸡胸肉 9.90/500g -> 300g = 5.94 ; 番茄 4.50/500g -> 200g = 1.80 ; 合计 7.74
		JsonNode result = objectMapper.readTree(tools.estimateDishCost("鸡胸肉:300:g,番茄:200:g"));

		assertThat(result.get("total").asDouble()).isEqualTo(7.74);
		assertThat(result.get("items")).hasSize(2);
		assertThat(result.get("items").get(0).get("estimatedCost").asDouble()).isEqualTo(5.94);
	}

	@Test
	@DisplayName("克与千克单位可以正确换算")
	void estimateDishCostHandlesUnitConversion() throws Exception {
		// 1kg 鸡胸肉 = 2 x 500g = 19.80
		JsonNode result = objectMapper.readTree(tools.estimateDishCost("鸡胸肉:1:kg"));

		assertThat(result.get("total").asDouble()).isEqualTo(19.80);
	}

	@Test
	@DisplayName("按包装规格计价：1 份 500g 的鲈鱼就是 28 元")
	void estimateDishCostHandlesPackSizedUnits() throws Exception {
		// 鲈鱼 28.00/500g；模型回显包装规格时数量是"几份"
		JsonNode result = objectMapper.readTree(tools.estimateDishCost("鲈鱼:1:500g,鸡蛋:0.4:10个"));

		assertThat(result.get("total").asDouble()).isEqualTo(31.40);
		assertThat(result.get("items").get(0).get("estimatedCost").asDouble()).isEqualTo(28.00);
		assertThat(result.get("items").get(1).get("estimatedCost").asDouble()).isEqualTo(3.40);
	}

	@Test
	@DisplayName("数量单位与包装单位不匹配时不再算出错误的小数字")
	void estimateDishCostIgnoresMismatchedUnits() throws Exception {
		// 番茄按 500g 计价，用"个"来买无法换算，宁可报 0 也不要报一个看似合理的价格
		JsonNode result = objectMapper.readTree(tools.estimateDishCost("番茄:2:个"));

		assertThat(result.get("total").asDouble()).isZero();
		assertThat(result.get("items").get(0).get("estimatedCost").asDouble()).isZero();
	}

	@Test
	@DisplayName("空输入不会抛异常")
	void estimateDishCostHandlesEmptyInput() throws Exception {
		JsonNode result = objectMapper.readTree(tools.estimateDishCost(""));

		assertThat(result.get("total").asDouble()).isZero();
		assertThat(result.get("items")).isEmpty();
	}
}

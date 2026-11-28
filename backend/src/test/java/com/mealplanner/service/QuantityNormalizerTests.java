package com.mealplanner.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealplanner.model.Ingredient;
import com.mealplanner.model.Recipe;
import com.mealplanner.tool.IngredientPriceTools;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The model writes quantities in three different conventions. The normaliser has to turn all of
 * them into one absolute amount, otherwise the shopping list is priced against nonsense.
 */
class QuantityNormalizerTests {

	private QuantityNormalizer normalizer;

	@BeforeEach
	void setUp() {
		ObjectMapper mapper = new ObjectMapper();
		IngredientPriceTools tools = new IngredientPriceTools(mapper);
		normalizer = new QuantityNormalizer(new IngredientPriceService(tools, mapper));
	}

	private Recipe recipe(Ingredient... ingredients) {
		return new Recipe("测试菜", "", 20, 2, "main", "none", List.of(ingredients), List.of("炒"));
	}

	private Ingredient first(List<Ingredient> ingredients) {
		return ingredients.get(0);
	}

	@Test
	@DisplayName("模型回显包装规格时按包倍数换算")
	void convertsPackSizedUnits() {
		Recipe normalized = normalizer.normalize(recipe(
				new Ingredient("鲈鱼", 1.0, "500g", null),
				new Ingredient("鸡蛋", 0.4, "10个", null),
				new Ingredient("姜", 0.15, "100g", null)));

		assertThat(first(normalized.ingredients()).quantity()).isEqualTo(500.0);
		assertThat(first(normalized.ingredients()).unit()).isEqualTo("g");
		assertThat(normalized.ingredients().get(1).quantity()).isEqualTo(4.0);
		assertThat(normalized.ingredients().get(1).unit()).isEqualTo("个");
		assertThat(normalized.ingredients().get(2).quantity()).isEqualTo(15.0);
	}

	@Test
	@DisplayName("千克数值配克单位时整道菜一起放大 1000 倍")
	void fixesKilogramNumbersLabelledAsGrams() {
		Recipe normalized = normalizer.normalize(recipe(
				new Ingredient("鸡腿", 1.25, "g", null),
				new Ingredient("香菇", 0.35, "g", null),
				new Ingredient("盐", 0.0045, "g", null)));

		assertThat(normalized.ingredients().get(0).quantity()).isEqualTo(1250.0);
		assertThat(normalized.ingredients().get(0).unit()).isEqualTo("g");
		assertThat(normalized.ingredients().get(1).quantity()).isEqualTo(350.0);
		assertThat(normalized.ingredients().get(2).quantity()).isEqualTo(4.5);
	}

	@Test
	@DisplayName("只有主料可疑时同样按千克处理")
	void fixesSingleMainIngredientInKilograms() {
		Recipe normalized = normalizer.normalize(recipe(
				new Ingredient("鸡腿", 1.25, "g", null),
				new Ingredient("盐", 3, "g", null)));

		assertThat(normalized.ingredients().get(0).quantity()).isEqualTo(1250.0);
		assertThat(normalized.ingredients().get(1).quantity()).isEqualTo(3.0);
	}

	@Test
	@DisplayName("孤立的少量调料不会被误判成千克")
	void keepsSmallCondimentAmounts() {
		Recipe normalized = normalizer.normalize(recipe(
				new Ingredient("盐", 0.5, "g", null),
				new Ingredient("鸡胸肉", 300, "g", null)));

		assertThat(normalized.ingredients().get(0).quantity()).isEqualTo(0.5);
	}

	@Test
	@DisplayName("少到不可能的调料用量一定是单位写错了")
	void fixesAbsurdlySmallCondimentAmounts() {
		// 0.003 克盐在任何一道菜里都不存在，那是 0.003 千克 = 3 克
		Recipe normalized = normalizer.normalize(recipe(new Ingredient("盐", 0.003, "g", null)));

		assertThat(normalized.ingredients().get(0).quantity()).isEqualTo(3.0);
	}

	@Test
	@DisplayName("单独出现的主料即使写成千克也会被修正")
	void fixesSingleMainIngredientInKilogramsAlone() {
		Recipe normalized = normalizer.normalize(recipe(new Ingredient("鸡腿", 1.25, "g", null)));

		assertThat(normalized.ingredients().get(0).quantity()).isEqualTo(1250.0);
	}

	@Test
	@DisplayName("本来就是千克的写法不会被二次放大")
	void keepsKilogramsAsWritten() {
		Recipe normalized = normalizer.normalize(recipe(
				new Ingredient("鸡腿", 1.25, "kg", null),
				new Ingredient("土豆", 0.85, "kg", null)));

		assertThat(normalized.ingredients().get(0).quantity()).isEqualTo(1250.0);
		assertThat(normalized.ingredients().get(1).quantity()).isEqualTo(850.0);
	}

	@Test
	@DisplayName("正常的绝对用量原样保留")
	void keepsAbsoluteAmounts() {
		Recipe normalized = normalizer.normalize(recipe(
				new Ingredient("鸡胸肉", 300, "g", null),
				new Ingredient("番茄", 200, "g", null),
				new Ingredient("生抽", 15, "ml", null)));

		assertThat(normalized.ingredients().get(0).quantity()).isEqualTo(300.0);
		assertThat(normalized.ingredients().get(1).quantity()).isEqualTo(200.0);
		assertThat(normalized.ingredients().get(2).quantity()).isEqualTo(15.0);
		assertThat(normalized.ingredients().get(2).unit()).isEqualTo("ml");
	}

	@Test
	@DisplayName("小于一个的计数按整包折算")
	void convertsFractionalCounts() {
		Recipe normalized = normalizer.normalize(recipe(new Ingredient("鸡蛋", 0.4, "个", null)));

		assertThat(normalized.ingredients().get(0).quantity()).isEqualTo(4.0);
	}

	@Test
	@DisplayName("单位价格用价格库里的真实单价覆盖模型自报的价格")
	void replacesUnitPriceWithDatabaseValue() {
		Recipe normalized = normalizer.normalize(recipe(new Ingredient("鸡胸肉", 300, "g", 999.0)));

		assertThat(normalized.ingredients().get(0).estimatedCostPerUnit()).isEqualTo(9.90);
	}

	@Test
	@DisplayName("价格库里没有的食材保留模型原样输出")
	void keepsUnknownIngredientsUntouched() {
		Recipe normalized = normalizer.normalize(recipe(new Ingredient("龙须凤爪", 1.25, "g", null)));

		assertThat(normalized.ingredients().get(0).quantity()).isEqualTo(1.25);
		assertThat(normalized.ingredients().get(0).unit()).isEqualTo("g");
	}
}

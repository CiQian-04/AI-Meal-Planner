package com.mealplanner.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealplanner.model.DayPlan;
import com.mealplanner.model.Ingredient;
import com.mealplanner.model.MealPlan;
import com.mealplanner.model.Recipe;
import com.mealplanner.model.ShoppingItem;
import com.mealplanner.tool.IngredientPriceTools;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for shopping list generation: duplicate merging and cost calculation.
 */
class ShoppingListServiceTests {

	private ShoppingListService service;

	@BeforeEach
	void setUp() {
		ObjectMapper mapper = new ObjectMapper();
		IngredientPriceTools tools = new IngredientPriceTools(mapper);
		service = new ShoppingListService(new IngredientPriceService(tools, mapper));
	}

	private Recipe recipe(String name, Ingredient... ingredients) {
		return new Recipe(name, "", 20, 2, "main", "none", List.of(ingredients), List.of("炒"));
	}

	private MealPlan plan(Recipe... recipes) {
		DayPlan day = new DayPlan(1, "第一天", List.of(recipes), 0.0);
		return new MealPlan(List.of(day), 0.0, 0.0, "测试", List.of(), List.of());
	}

	@Test
	@DisplayName("重复食材合并为一行，数量累加")
	void duplicateIngredientsAreMerged() {
		MealPlan plan = plan(
				recipe("宫保鸡丁", new Ingredient("鸡胸肉", 300, "g", null), new Ingredient("番茄", 200, "g", null)),
				recipe("番茄炒蛋", new Ingredient("番茄", 200, "g", null), new Ingredient("鸡蛋", 3, "个", null)));

		List<ShoppingItem> items = service.build(plan);

		ShoppingItem tomato = items.stream().filter(i -> i.name().equals("番茄")).findFirst().orElseThrow();
		assertThat(tomato.quantity()).isEqualTo(400.0);
		assertThat(tomato.usedIn()).containsExactlyInAnyOrder("宫保鸡丁", "番茄炒蛋");
		assertThat(items).hasSize(3);
	}

	@Test
	@DisplayName("购物清单成本按价格库换算")
	void shoppingCostUsesPriceDatabase() {
		MealPlan plan = plan(recipe("宫保鸡丁", new Ingredient("鸡胸肉", 500, "g", null)));

		List<ShoppingItem> items = service.build(plan);

		// 鸡胸肉 9.90 / 500g -> 500g = 9.90
		assertThat(items.get(0).estimatedCost()).isEqualTo(9.90);
		assertThat(service.totalCost(items)).isEqualTo(9.90);
	}

	@Test
	@DisplayName("未知食材成本为 0 且分类为未知")
	void unknownIngredientHasZeroCost() {
		MealPlan plan = plan(recipe("神秘料理", new Ingredient("龙须凤爪", 100, "g", null)));

		List<ShoppingItem> items = service.build(plan);

		assertThat(items.get(0).estimatedCost()).isZero();
		assertThat(items.get(0).category()).isEqualTo("未知");
	}

	@Test
	@DisplayName("同一食材单位不同时按数量累加")
	void sameIngredientWithDifferentUnitsIsStillMerged() {
		MealPlan plan = plan(
				recipe("菜一", new Ingredient("番茄", 200, "g", null)),
				recipe("菜二", new Ingredient("番茄", 1, "个", null)));

		List<ShoppingItem> items = service.build(plan);

		ShoppingItem tomato = items.stream().filter(i -> i.name().equals("番茄")).findFirst().orElseThrow();
		assertThat(tomato.quantity()).isEqualTo(201.0);
	}
}

package com.mealplanner.controller;

import com.mealplanner.service.IngredientPriceService;
import com.mealplanner.tool.IngredientPriceTools;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints that expose the price data behind the model's tool calls.
 *
 * <p>Useful for debugging: if the UI shows a price you doubt, call
 * {@code /api/v1/prices/{ingredient}} and compare with what the model was told.
 */
@RestController
@RequestMapping("/api/v1/prices")
@Tag(name = "食材价格", description = "与模型 Function Calling 共用的同一份价格数据")
public class PriceController {

	private final IngredientPriceTools priceTools;
	private final IngredientPriceService priceService;

	public PriceController(IngredientPriceTools priceTools, IngredientPriceService priceService) {
		this.priceTools = priceTools;
		this.priceService = priceService;
	}

	@Operation(summary = "查询单个食材价格", description = "返回与工具函数完全一致的 JSON 结果")
	@GetMapping("/{ingredient}")
	public ResponseEntity<String> lookup(@PathVariable String ingredient) {
		return ResponseEntity.ok(priceTools.lookupIngredientPrice(ingredient));
	}

	@Operation(summary = "批量查询食材价格")
	@GetMapping
	public ResponseEntity<String> lookupBatch(@RequestParam("names") List<String> names) {
		return ResponseEntity.ok(priceTools.lookupIngredientPrices(names));
	}

	@Operation(summary = "列出所有已知食材", description = "供前端食材选择器使用")
	@GetMapping("/catalog")
	public ResponseEntity<Map<String, Object>> catalog() {
		return ResponseEntity.ok(Map.of(
				"count", priceService.knownIngredients().size(),
				"ingredients", priceService.knownIngredients()));
	}
}

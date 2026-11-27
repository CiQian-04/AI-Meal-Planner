package com.mealplanner.controller;

import com.mealplanner.model.PlanRequest;
import com.mealplanner.model.PlanResponse;
import com.mealplanner.service.MealPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "菜谱规划", description = "基于 Spring AI 的家庭菜谱规划与购物清单生成")
public class MealPlanController {

	private final MealPlanService mealPlanService;

	public MealPlanController(MealPlanService mealPlanService) {
		this.mealPlanService = mealPlanService;
	}

	@Operation(
			summary = "生成菜谱与购物清单",
			description = """
					根据天数、预算、忌口等条件调用大模型生成家庭菜单。

					模型在规划过程中会自行调用食材价格查询函数（lookupIngredientPrice 等）获取真实单价，
					最终以结构化 JSON 返回菜单与合并后的购物清单。响应中的 toolCalls 字段记录了模型实际
					发起过的函数调用，可用于验证 Function Calling 是否生效。""")
	@ApiResponse(responseCode = "200", description = "生成成功")
	@ApiResponse(responseCode = "400", description = "请求参数不合法",
			content = @Content(schema = @Schema(implementation = com.mealplanner.exception.ApiError.class)))
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PlanResponse> createPlan(@Valid @RequestBody PlanRequest request) {
		return ResponseEntity.ok(mealPlanService.plan(request));
	}
}

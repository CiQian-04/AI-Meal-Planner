package com.mealplanner.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description served at {@code /v3/api-docs} and rendered by Swagger UI at
 * {@code /swagger-ui.html}.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI mealPlannerOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("AI 菜谱规划师 API")
						.version("0.1.0")
						.description("""
								基于 Spring AI 的家庭菜谱规划服务。

								**核心能力**

								- **Function Calling**：模型在规划过程中自行调用 `lookupIngredientPrice`、
							  `lookupIngredientPrices`、`estimateDishCost` 三个函数获取真实食材价格，
							  后端把每次调用记录在响应的 `toolCalls` 字段中。
								- **结构化输出**：通过 `BeanOutputConverter` 把模型输出强制转换为
							  `MealPlan` JSON 结构，避免解析自由文本。
								""")
						.contact(new Contact().name("Meal Planner").url("https://github.com"))
						.license(new License().name("Apache-2.0")))
				.servers(List.of(
						new Server().url("/").description("当前服务")));
	}
}

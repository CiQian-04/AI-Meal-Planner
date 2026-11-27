# 开发指南

## 项目结构

```
meal-planner/
├── backend/                 Spring AI 后端
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/mealplanner/
│       ├── config/           ChatClient、OpenAPI、CORS 配置
│       ├── controller/       REST 接口
│       ├── exception/        统一异常处理
│       ├── model/            领域模型（record）
│       ├── service/          业务逻辑
│       └── tool/             @Tool 函数与调用轨迹记录
├── frontend/                Vue 3 前端
│   ├── package.json
│   ├── Dockerfile
│   ├── nginx.conf
│   └── src/
├── docs/                    MkDocs 文档站点
├── tools/                   离线 mock 模型服务
├── scripts/                 本地运行与验证脚本
├── .devcontainer/           Dev Container 配置
├── .github/workflows/       CI、发版、安全扫描
├── docker-compose.yml
└── release-please-config.json
```

## 后端

### 构建与测试

```bash
cd backend
mvn -B -ntp verify        # 编译 + 测试
mvn spring-boot:run       # 本地启动
```

### 添加新的食材价格 {#ingredient-prices}

价格库在 `IngredientPriceTools` 的静态初始化块里：

```java
static {
    put("鸡胸肉", "500g", 9.90, "肉类");
    //   名称      计价单位   单价    分类
}
```

四个参数分别是：食材名、计价单位（如 `500g`、`10个`）、单价（元）、购物分类。

改完记得同步更新 mock 服务的价目表 `tools/mock_openai_server.py`，否则离线模式下价格会不一致。

### 添加新的工具函数 {#add-a-tool}

在 `IngredientPriceTools` 里加一个 `@Tool` 方法即可：

```java
@Tool(name = "checkNutrition",
      description = "查询食材的营养信息，例如蛋白质含量、热量。参数 name 使用中文食材名。")
public String checkNutrition(
        @ToolParam(description = "食材名称，使用中文", required = true) String name) {
    return toJson(Map.of("name", name, "caloriesPer100g", 120));
}
```

Spring AI 会自动把它注册进工具列表，**不需要改配置**。

方法上的 `@Tool` / `@ToolParam` 来自 `org.springframework.ai.tool.annotation`。

### 调整提示词 {#tune-the-prompt}

系统提示词在 `MealPlanService.systemPrompt()`，用户提示词在 `userPrompt()`。

提示词里的硬性要求直接对应 JSON 字段，例如：

```
- estimatedTotalCost 为各道菜成本之和；estimatedShoppingCost 为合并同类食材后的实际采购成本。
- spicyLevel 在用户要求不吃辣时必须为 "none"。
```

### 新增接口

1. 在 `model` 包写 record（请求/响应）
2. 在 `service` 包写业务逻辑
3. 在 `controller` 包写 REST 接口，用 `@Tag` / `@Operation` 补充 OpenAPI 文档
4. 在 `GlobalExceptionHandler` 里补充异常处理（如有需要）

## 前端 {#frontend}

```bash
cd frontend
npm install
npm run dev         # 开发服务器
npm run build       # 生产构建
npm run type-check  # TypeScript 类型检查
```

### 修改样式 {#styles}

所有样式集中在 `src/assets/main.css`，用 CSS 变量定义主题色：

```css
:root {
  --primary: #e8622c;
  --accent: #0f766e;
  --danger: #b42318;
}
```

### 添加组件

组件放在 `src/components/`，用 `<script setup lang="ts">` 语法：

```vue
<script setup lang="ts">
import type { Recipe } from '@/types'

defineProps<{ recipe: Recipe }>()
</script>

<template>
  <article class="recipe-card">...</article>
</template>
```

类型定义集中在 `src/types.ts`，与后端 DTO 一一对应。

## 离线验证

不需要 API Key 就能跑通完整链路：

```bash
# 后端接口验证
bash scripts/verify.sh

# 浏览器端到端验证（会截图到 C:/tmp/shots）
bash scripts/ui-verify.sh
```

`ui-verify.sh` 使用本机已安装的 Chrome 或 Edge 驱动真实浏览器，模拟填写表单、生成菜谱、
勾选购物清单、导出 TXT 的完整流程，并断言页面渲染结果。

## Dev Container

用 VS Code 打开项目时会自动进入 Dev Container：

- JDK 21 + Node 22 LTS
- 端口 8080（后端）、5173（前端）自动转发
- `scripts/dev-setup.sh` 在创建后自动预热 Maven 依赖与 npm 依赖

## 提交规范

项目使用 Release Please，采用 Conventional Commits：

| 前缀 | 用途 | 版本影响 |
| --- | --- | --- |
| `feat:` | 新功能 | 次版本 |
| `fix:` | 修复问题 | 修订号 |
| `perf:` | 性能优化 | 修订号 |
| `docs:` | 文档 | 修订号 |
| `refactor:` | 重构 | 修订号 |
| `chore:` / `ci:` / `build:` | 杂项 | 不影响 |

提交信息示例：

```bash
git commit -m "feat: 支持按人数自动换算食材用量"
git commit -m "fix: 修正克与千克换算时的除零错误"
```

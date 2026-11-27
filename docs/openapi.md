# OpenAPI 规范

本服务的完整接口描述由 springdoc-openapi 在运行时生成，下面的内容是导出版本。

- 运行时地址：<http://localhost:8080/v3/api-docs>
- 交互式 UI：<http://localhost:8080/swagger-ui.html>
- 仓库内文件：[openapi.json](openapi.json)

重新导出（需要后端已启动）：

```bash
python docs/export_openapi.py
```

## 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/plans` | 生成菜谱与购物清单 |
| `GET` | `/api/v1/prices` | 批量查询食材价格 |
| `GET` | `/api/v1/prices/{ingredient}` | 查询单个食材价格 |
| `GET` | `/api/v1/prices/catalog` | 列出所有已知食材 |

## 主要数据结构

```json
{
  "plan": {
    "days": [
      {
        "day": 1,
        "label": "第 1 天",
        "meals": [
          {
            "name": "番茄炒蛋",
            "description": "经典家常菜，酸甜开胃",
            "cookingMinutes": 15,
            "servings": 2,
            "category": "main",
            "spicyLevel": "none",
            "ingredients": [
              { "name": "番茄", "quantity": 300, "unit": "g" }
            ],
            "steps": ["鸡蛋打散加少许盐", "番茄切块炒出汁"]
          }
        ],
        "estimatedCost": 17.18
      }
    ],
    "estimatedTotalCost": 55.54,
    "estimatedShoppingCost": 59.15,
    "summary": "3 天共 9 道菜，全部清淡不辣，实测采购成本 ¥59.15",
    "tips": ["食材采购价随季节浮动，实际以超市标价为准"],
    "toolCallsUsed": ["lookupIngredientPrices"]
  },
  "shoppingList": [
    {
      "name": "鸡蛋",
      "quantity": 8,
      "unit": "个",
      "estimatedCost": 6.8,
      "category": "肉类",
      "usedIn": ["番茄炒蛋", "西红柿鸡蛋汤"]
    }
  ],
  "toolCalls": [
    {
      "tool": "lookupIngredientPrices",
      "arguments": "{\"names\": [\"番茄\", \"鸡蛋\"]}",
      "result": "[{\"found\":true,\"name\":\"番茄\",\"unit\":\"500g\",\"unitPrice\":4.5}]"
    }
  ],
  "requestEcho": { "days": 3, "avoidSpicy": true, "budget": 100 }
}
```

!!! note "金额以谁为准"
    `shoppingList` 中每一项的 `estimatedCost` 与 `plan.estimatedShoppingCost` 都由后端根据
    价格库计算；`plan.summary` 里模型自己写的金额若与实算不符，会被替换为实算值，
    保证用户读到的两处金额一致。

## 错误响应

| 状态码 | 场景 |
| --- | --- |
| `400` | 参数校验失败（天数超范围、预算为负等） |
| `500` | 模型调用失败或返回内容无法解析为 `MealPlan` |

错误体统一为：

```json
{
  "timestamp": "2026-11-27T03:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "参数校验失败",
  "path": "/api/v1/plans"
}
```

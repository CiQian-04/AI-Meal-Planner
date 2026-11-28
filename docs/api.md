# API 参考

后端基于 springdoc-openapi，运行时可访问：

| 资源 | 地址 |
| --- | --- |
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI JSON | <http://localhost:8080/v3/api-docs> |
| 健康检查 | <http://localhost:8080/actuator/health> |

## POST /api/v1/plans

生成菜谱与购物清单。

**Request body** (`application/json`)

| 字段 | 类型 | 必填 | 默认 | 约束 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `days` | integer | 是 | 3 | 1–14 | 规划天数 |
| `avoidSpicy` | boolean | 是 | true | — | 是否忌口辣味 |
| `budget` | number | 是 | 100 | ≥10 | 食材采购总预算（元） |
| `mealsPerDay` | integer | 否 | 3 | 1–5 | 每天菜品数 |
| `servings` | integer | 否 | 2 | 1–10 | 用餐人数 |
| `preferences` | string[] | 否 | `[]` | — | 口味偏好，如 `["清淡"]` |
| `excludedIngredients` | string[] | 否 | `[]` | — | 忌口食材 |

**Example**

```bash
curl -X POST http://localhost:8080/api/v1/plans \
  -H 'Content-Type: application/json' \
  -d '{
    "days": 3,
    "avoidSpicy": true,
    "budget": 100,
    "mealsPerDay": 3,
    "servings": 2,
    "preferences": ["清淡"],
    "excludedIngredients": []
  }'
```

**200 Response**

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
            "description": "经典家常菜，酸甜开胃，零失败",
            "cookingMinutes": 15,
            "servings": 2,
            "category": "main",
            "spicyLevel": "none",
            "ingredients": [
              { "name": "番茄", "quantity": 300, "unit": "g", "estimatedCostPerUnit": 4.5 }
            ],
            "steps": ["鸡蛋打散加少许盐", "热油下蛋液炒至凝固盛出", "番茄切块炒出汁", "倒回鸡蛋翻炒调味出锅"]
          }
        ],
        "estimatedCost": 17.18
      }
    ],
    "estimatedTotalCost": 55.54,
    "estimatedShoppingCost": 55.55,
    "summary": "3 天共 9 道菜，全部清淡不辣，实测采购成本 ¥55.55",
    "tips": ["食材采购价随季节浮动，实际以超市标价为准"],
    "toolCallsUsed": ["lookupIngredientPrices"]
  },
  "shoppingList": [
    {
      "name": "番茄",
      "quantity": 600.0,
      "unit": "g",
      "estimatedCost": 5.4,
      "category": "蔬菜",
      "usedIn": ["番茄炒蛋", "西红柿鸡蛋汤"]
    }
  ],
  "toolCalls": [
    {
      "tool": "lookupIngredientPrices",
      "arguments": "{\"names\": [\"番茄\", \"鸡蛋\", \"鸡胸肉\"]}",
      "result": "[{\"found\":true,\"name\":\"番茄\",\"unit\":\"500g\",\"unitPrice\":4.5,...}]"
    }
  ],
  "requestEcho": {
    "days": 3,
    "avoidSpicy": true,
    "budget": 100,
    "mealsPerDay": 3,
    "servings": 2,
    "preferences": ["清淡"],
    "excludedIngredients": []
  },
  "shoppingPackCost": 78.9
}
```

!!! note "`shoppingPackCost` 的作用"
    `plan.estimatedShoppingCost` 是**按实际用量**折算的精确成本；`shoppingPackCost` 是**按整包购买**的成本
    （超市只卖整袋整瓶，这个数才是真正要掏的钱）。两个数都由后端算出，tips 末尾也会附上同样口径的说明。

!!! note "`toolCalls` 的作用"
    这个字段记录了模型实际发起过的函数调用，是验证 Function Calling 是否生效的直接证据。
    `result` 字段截断到 400 字符，仅用于展示。

**错误响应**

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "请求参数校验失败",
  "path": "/api/v1/plans",
  "fieldErrors": { "days": "must be less than or equal to 14" }
}
```

## GET /api/v1/prices/{ingredient}

查询单个食材价格。返回内容与模型调用工具时拿到的**完全一致**。

```bash
curl http://localhost:8080/api/v1/prices/鸡胸肉
```

```json
{
  "found": true,
  "name": "鸡胸肉",
  "unit": "500g",
  "unitPrice": 9.9,
  "category": "肉类",
  "alternatives": []
}
```

找不到时 `found` 为 `false`，并给出相近食材建议：

```json
{
  "found": false,
  "name": "鸡翅",
  "unit": "",
  "unitPrice": 0.0,
  "category": "未知",
  "alternatives": [
    { "found": true, "name": "鸡腿", "unit": "500g", "unitPrice": 12.5, "category": "肉类", "alternatives": [] }
  ]
}
```

## GET /api/v1/prices

批量查询。参数 `names` 可重复或用逗号分隔。

```bash
curl "http://localhost:8080/api/v1/prices?names=番茄,鸡蛋"
```

返回数组，每项结构与单查一致。

## GET /api/v1/prices/catalog

列出价格库中所有已知食材，供前端选择器使用。

```json
{
  "count": 46,
  "ingredients": ["五花肉", "冬瓜", "土豆", "大米", "…"]
}
```

## Actuator

| 端点 | 说明 |
| --- | --- |
| `/actuator/health` | 健康状态，容器 healthcheck 使用 |
| `/actuator/info` | 应用信息 |
| `/actuator/metrics` | 指标 |

## 完整 OpenAPI 规范

静态文件见 [`docs/openapi.json`](openapi.json)，
由 `docs/export_openapi.py` 从运行中的后端导出，或在 CI 中自动生成。

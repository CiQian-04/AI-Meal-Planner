# 快速开始

## 环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 21 或更高 |
| Maven | 3.9+ |
| Node.js | 20 或更高 |
| Docker | 24+（可选） |

## 一、用 Docker Compose 起栈（推荐）

```bash
git clone https://github.com/example/meal-planner.git
cd meal-planner

# 配置模型端点（见下文「配置模型」）
export OPENAI_API_KEY=sk-xxxx
export OPENAI_BASE_URL=https://api.openai.com
export OPENAI_MODEL=gpt-4o-mini

docker compose up --build
```

打开 <http://localhost:5173> 即可使用。

!!! note "健康检查"
    `docker compose` 会等待后端 `/actuator/health` 返回 UP 才启动前端依赖，因此首次启动稍慢。

## 二、本地开发

分两个终端：

```bash
# 终端 1：后端
cd backend
mvn spring-boot:run

# 终端 2：前端
cd frontend
npm install
npm run dev
```

前端开发服务器会把 `/api` 代理到 `http://localhost:8080`，无需额外配置跨域。

## 三、配置模型

后端通过三个环境变量对接任意 OpenAI 兼容端点：

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `OPENAI_API_KEY` | API 密钥 | `sk-not-configured` |
| `OPENAI_BASE_URL` | 接口根地址 | `https://api.openai.com` |
| `OPENAI_MODEL` | 模型名 | `gpt-4o-mini` |

### 常见第三方端点

=== "DeepSeek"

    ```bash
    export OPENAI_BASE_URL=https://api.deepseek.com
    export OPENAI_MODEL=deepseek-chat
    export OPENAI_API_KEY=sk-xxxx
    ```

=== "阿里云百炼（Qwen）"

    ```bash
    export OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
    export OPENAI_MODEL=qwen-plus
    export OPENAI_API_KEY=sk-xxxx
    ```

=== "Ollama（本地离线）"

    ```bash
    export OPENAI_BASE_URL=http://localhost:11434
    export OPENAI_MODEL=qwen2.5:7b
    export OPENAI_API_KEY=ollama
    ```

    注意：模型需要支持 function calling，`qwen2.5`、`llama3.1` 等都可以。

=== "vLLM 自建"

    ```bash
    export OPENAI_BASE_URL=http://your-vllm-host:8000
    export OPENAI_MODEL=Qwen/Qwen2.5-7B-Instruct
    export OPENAI_API_KEY=dummy
    ```

## 四、没有 API Key 也能跑

仓库自带一个 OpenAI 兼容的 mock 服务，用于离线开发和 CI：

```bash
# 启动 mock 服务
python tools/mock_openai_server.py --port 8095

# 另开一个终端启动后端，指向 mock
cd backend
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--spring.ai.openai.base-url=http://127.0.0.1:8095 --spring.ai.openai.api-key=mock --spring.ai.openai.chat.options.model=mock-model"
```

!!! warning "mock 服务的行为"
    mock 服务会真的走一遍「模型请求工具 → 工具返回价格 → 模型给出菜单」的完整回路，
    菜单里的价格来自工具的真实返回，因此可以验证 Function Calling 是否接通。
    但它**不是**真正的语言模型，菜谱内容是预置模板。

一键跑完整验证：

```bash
bash scripts/verify.sh
```

该脚本会启动 mock 与后端，检查健康状态、规划接口、参数校验、OpenAPI 文档，并断言响应内容。

## 五、验证 Function Calling 是否生效

规划接口的响应里带 `toolCalls` 字段：

```bash
curl -s -X POST http://localhost:8080/api/v1/plans \
  -H 'Content-Type: application/json' \
  -d '{"days":3,"avoidSpicy":true,"budget":100,"mealsPerDay":3,"servings":2}' \
  | python -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d['toolCalls'], ensure_ascii=False, indent=2))"
```

预期输出：

```json
[
  {
    "tool": "lookupIngredientPrices",
    "arguments": "{\"names\": [\"番茄\", \"鸡蛋\", \"鸡胸肉\"]}",
    "result": "[{\"found\":true,\"name\":\"番茄\",\"unit\":\"500g\",\"unitPrice\":4.5,...}]"
  }
]
```

如果 `toolCalls` 是空数组，说明模型没有发起函数调用——通常是模型不支持 function calling，换一个模型即可。

## 六、下一步

- 了解实现细节 → [架构设计](architecture.md)
- 查看完整接口定义 → [API 参考](api.md)
- 修改价格数据 → [开发指南](development.md#修改食材价格库)

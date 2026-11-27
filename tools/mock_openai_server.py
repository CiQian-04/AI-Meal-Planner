#!/usr/bin/env python3
"""
Mock OpenAI-compatible server used to verify the Spring AI integration offline.

It implements just enough of the ``/v1/chat/completions`` contract for Spring AI to
drive a real function-calling round trip:

1. When the request carries ``tools`` and no tool result yet, the server answers with a
   ``tool_calls`` payload (the model asking for ingredient prices).
2. When the request already contains a ``tool`` role message, the server returns the final
   content: a JSON meal plan built from the prices the tool actually returned.

That second step matters: the plan's numbers come from the tool result, so a broken
tool implementation shows up as wrong costs in the response instead of passing silently.

Usage::

    python tools/mock_openai_server.py --port 8090
    OPENAI_BASE_URL=http://localhost:8090 OPENAI_API_KEY=mock OPENAI_MODEL=mock-model
"""

from __future__ import annotations

import argparse
import json
import re
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# The price table mirrors backend/src/main/java/com/mealplanner/tool/IngredientPriceTools.java
PRICE_DB = {
    "番茄": ("500g", 4.50, "蔬菜"),
    "西红柿": ("500g", 4.50, "蔬菜"),
    "土豆": ("500g", 2.80, "蔬菜"),
    "黄瓜": ("500g", 3.20, "蔬菜"),
    "胡萝卜": ("500g", 2.50, "蔬菜"),
    "白菜": ("500g", 2.90, "蔬菜"),
    "生菜": ("300g", 3.60, "蔬菜"),
    "油麦菜": ("400g", 4.20, "蔬菜"),
    "茼蒿": ("400g", 4.50, "蔬菜"),
    "娃娃菜": ("400g", 5.80, "蔬菜"),
    "菠菜": ("500g", 5.50, "蔬菜"),
    "西兰花": ("500g", 6.80, "蔬菜"),
    "青椒": ("500g", 4.20, "蔬菜"),
    "洋葱": ("500g", 2.20, "蔬菜"),
    "豆腐": ("400g", 3.50, "蔬菜"),
    "茄子": ("500g", 3.80, "蔬菜"),
    "冬瓜": ("500g", 2.60, "蔬菜"),
    "香菇": ("500g", 7.90, "蔬菜"),
    "鸡胸肉": ("500g", 9.90, "肉类"),
    "鸡腿": ("500g", 12.50, "肉类"),
    "猪里脊": ("500g", 19.90, "肉类"),
    "五花肉": ("500g", 22.00, "肉类"),
    "鸡蛋": ("10个", 8.50, "肉类"),
    "大米": ("500g", 2.80, "主食"),
    "面条": ("500g", 4.50, "主食"),
    "挂面": ("500g", 5.20, "主食"),
    "生抽": ("500ml", 7.50, "调味"),
    "老抽": ("500ml", 8.00, "调味"),
    "蚝油": ("500g", 13.00, "调味"),
    "盐": ("500g", 2.50, "调味"),
    "白糖": ("500g", 5.00, "调味"),
    "食用油": ("500ml", 9.90, "调味"),
    "大蒜": ("100g", 3.50, "调味"),
    "姜": ("100g", 3.00, "调味"),
}

# Recipes are picked per day so the generated plan looks like a real menu.
RECIPE_TEMPLATES = [
    {
        "name": "番茄炒蛋",
        "description": "经典家常菜，酸甜开胃，零失败",
        "category": "main",
        "cookingMinutes": 15,
        "ingredients": [("番茄", 300, "g"), ("鸡蛋", 3, "个"), ("白糖", 5, "g"), ("食用油", 15, "ml")],
        "steps": ["鸡蛋打散加少许盐", "热油下蛋液炒至凝固盛出", "番茄切块炒出汁", "倒回鸡蛋翻炒调味出锅"],
    },
    {
        "name": "清炒西兰花",
        "description": "低卡高纤维，清爽解腻",
        "category": "side",
        "cookingMinutes": 12,
        "ingredients": [("西兰花", 250, "g"), ("大蒜", 10, "g"), ("盐", 3, "g"), ("食用油", 10, "ml")],
        "steps": ["西兰花掰小朵焯水", "热油爆香蒜片", "下西兰花大火翻炒", "加盐调味出锅"],
    },
    {
        "name": "香菇滑鸡",
        "description": "鸡胸肉配香菇，嫩滑少油",
        "category": "main",
        "cookingMinutes": 25,
        "ingredients": [("鸡胸肉", 250, "g"), ("香菇", 150, "g"), ("生抽", 10, "ml"), ("姜", 5, "g")],
        "steps": ["鸡胸肉切片用生抽腌10分钟", "香菇切片姜切丝", "热油下姜丝香菇炒香", "下鸡肉炒至变色收汁"],
    },
    {
        "name": "土豆炖牛腩",
        "description": "土豆软糯牛腩入味，炖一锅吃两天",
        "category": "main",
        "cookingMinutes": 60,
        "ingredients": [("猪里脊", 300, "g"), ("土豆", 400, "g"), ("胡萝卜", 150, "g"), ("老抽", 10, "ml")],
        "steps": ["牛腩冷水下锅焯水", "加姜片炖40分钟", "下土豆胡萝卜", "调色调味收汁"],
    },
    {
        "name": "蒜蓉生菜",
        "description": "3分钟出锅，解腻神器",
        "category": "side",
        "cookingMinutes": 8,
        "ingredients": [("生菜", 300, "g"), ("大蒜", 15, "g"), ("生抽", 8, "ml")],
        "steps": ["生菜掰开洗净", "蒜末爆香", "大火下生菜", "淋生抽出锅"],
    },
    {
        "name": "冬瓜排骨汤",
        "description": "清甜滋补，少油少盐",
        "category": "soup",
        "cookingMinutes": 45,
        "ingredients": [("冬瓜", 400, "g"), ("猪里脊", 200, "g"), ("姜", 8, "g"), ("盐", 4, "g")],
        "steps": ["排骨焯水去血沫", "加姜片炖30分钟", "下冬瓜再炖10分钟", "加盐调味"],
    },
    {
        "name": "青椒土豆丝",
        "description": "爽脆下饭的快手素菜",
        "category": "side",
        "cookingMinutes": 15,
        "ingredients": [("土豆", 350, "g"), ("青椒", 100, "g"), ("盐", 3, "g"), ("食用油", 12, "ml")],
        "steps": ["土豆切细丝泡水去淀粉", "青椒切丝", "热油大火快炒土豆丝", "加青椒调味出锅"],
    },
    {
        "name": "西红柿鸡蛋汤",
        "description": "五分钟快手汤，酸鲜开胃",
        "category": "soup",
        "cookingMinutes": 15,
        "ingredients": [("西红柿", 200, "g"), ("鸡蛋", 2, "个"), ("盐", 3, "g"), ("食用油", 8, "ml")],
        "steps": ["西红柿切块炒出汁", "加水煮开", "淋入蛋液", "加盐出锅"],
    },
]


def price_for(name: str):
    return PRICE_DB.get(name.strip())


def unit_cost(name: str, quantity: float, unit: str) -> float:
    """Cost of `quantity` of `unit` given the price table, mirroring the Java helper."""
    entry = price_for(name)
    if entry is None:
        return 0.0
    pack_size, pack_price, _ = entry
    pack_qty = float(re.sub(r"[^0-9.]", "", pack_size) or 0)
    pack_unit = re.sub(r"[0-9.]", "", pack_size)

    def base(qty, u):
        u = (u or "").lower()
        if u in ("kg", "千克", "公斤"):
            return qty * 1000
        if u in ("g", "克"):
            return qty
        if u in ("l", "升"):
            return qty * 1000
        if u in ("ml", "毫升"):
            return qty
        return qty

    pack_base = base(pack_qty, pack_unit)
    if pack_base <= 0:
        return 0.0
    return round(base(quantity, unit) / pack_base * pack_price, 2)


def build_tool_call(tool_name: str, arguments: dict) -> dict:
    return {
        "id": f"call_{uuid.uuid4().hex[:12]}",
        "type": "function",
        "function": {"name": tool_name, "arguments": json.dumps(arguments, ensure_ascii=False)},
    }


def build_plan(days: int, meals_per_day: int, budget: float, tool_results: list) -> dict:
    """Assemble the structured plan, pricing it with the values the tool returned."""
    # Only use prices that actually came back through the tool, so the mock mirrors reality.
    known = {}
    for result in tool_results:
        try:
            payload = json.loads(result)
        except (json.JSONDecodeError, TypeError):
            continue
        if isinstance(payload, list):
            for item in payload:
                if item.get("found"):
                    known[item["name"]] = item
        elif isinstance(payload, dict) and payload.get("found"):
            known[payload["name"]] = payload

    day_plans = []
    total = 0.0
    template_index = 0

    for day in range(1, days + 1):
        meals = []
        day_cost = 0.0
        for _ in range(meals_per_day):
            template = RECIPE_TEMPLATES[template_index % len(RECIPE_TEMPLATES)]
            template_index += 1

            ingredients = []
            meal_cost = 0.0
            for ing_name, qty, unit in template["ingredients"]:
                price = known.get(ing_name)
                unit_price = price["unitPrice"] if price else 0.0
                cost = unit_cost(ing_name, qty, unit)
                meal_cost += cost
                ingredients.append(
                    {
                        "name": ing_name,
                        "quantity": qty,
                        "unit": unit,
                        "estimatedCostPerUnit": unit_price,
                    }
                )

            meals.append(
                {
                    "name": template["name"],
                    "description": template["description"],
                    "cookingMinutes": template["cookingMinutes"],
                    "servings": 2,
                    "category": template["category"],
                    "spicyLevel": "none",
                    "ingredients": ingredients,
                    "steps": template["steps"],
                }
            )
            day_cost += meal_cost

        day_cost = round(day_cost, 2)
        total += day_cost
        day_plans.append(
            {
                "day": day,
                "label": f"第 {day} 天",
                "meals": meals,
                "estimatedCost": day_cost,
            }
        )

    shopping = {}
    for day in day_plans:
        for meal in day["meals"]:
            for ing in meal["ingredients"]:
                key = ing["name"]
                entry = shopping.setdefault(key, {"qty": 0.0, "unit": ing["unit"], "cost": 0.0})
                entry["qty"] += ing["quantity"]
                entry["cost"] += unit_cost(key, ing["quantity"], ing["unit"])

    shopping_cost = round(sum(v["cost"] for v in shopping.values()), 2)

    return {
        "days": day_plans,
        "estimatedTotalCost": round(total, 2),
        "estimatedShoppingCost": shopping_cost,
        "summary": f"{days} 天共 {days * meals_per_day} 道菜，全部清淡不辣，实测采购成本 ¥{shopping_cost:.2f}",
        "tips": [
            "食材采购价随季节浮动，实际以超市标价为准",
            "鸡胸肉和鸡蛋是本菜单的成本大头，可多买备用",
            "蔬菜建议当天买当天吃，减少浪费",
        ],
        "toolCallsUsed": [],
    }


def parse_user_prompt(messages: list) -> dict:
    """Recover the planning parameters from the user prompt the backend sends."""
    user_text = ""
    for message in messages:
        if message.get("role") == "user":
            user_text = message.get("content") or ""
            break

    def find_int(pattern, default):
        match = re.search(pattern, user_text)
        return int(match.group(1)) if match else default

    days = find_int(r"规划\s*(\d+)\s*天", 3)
    budget = find_int(r"预算[：:]\s*(\d+)", 100)
    meals = find_int(r"每天\s*(\d+)\s*道菜", 3)
    servings = find_int(r"用餐人数[：:]\s*(\d+)", 2)
    return {"days": days, "budget": budget, "mealsPerDay": meals, "servings": servings}


class Handler(BaseHTTPRequestHandler):
    server_version = "MockOpenAI/1.0"

    def log_message(self, fmt, *args):  # keep the console readable
        print(f"[mock-openai] {fmt % args}", flush=True)

    def _read_body(self) -> bytes:
        """Read the request body, handling both Content-Length and chunked encoding.

        Spring AI's RestClient sends chunked requests, so relying on Content-Length alone
        silently yields an empty body.
        """
        encoding = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in encoding:
            chunks = []
            while True:
                size_line = self.rfile.readline().strip()
                if not size_line:
                    break
                try:
                    size = int(size_line.split(b";")[0], 16)
                except ValueError:
                    break
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
            return b"".join(chunks)

        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def _send(self, payload: dict, status: int = 200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/") in ("/v1/models", "/models"):
            self._send(
                {
                    "object": "list",
                    "data": [
                        {"id": "mock-model", "object": "model", "created": 0, "owned_by": "mock"}
                    ],
                }
            )
            return
        self._send({"error": {"message": f"unsupported path {self.path}"}}, 404)

    def do_POST(self):
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self._send({"error": {"message": f"unsupported path {self.path}"}}, 404)
            return

        length = int(self.headers.get("Content-Length", 0))
        raw_body = self._read_body()
        print(f"[mock-openai] body bytes={len(raw_body)}", flush=True)
        try:
            request = json.loads(raw_body or b"{}")
        except json.JSONDecodeError as ex:
            print(f"[mock-openai] undecodable body: {ex}", flush=True)
            self._send({"error": {"message": f"invalid JSON body: {ex}"}}, 400)
            return
        messages = request.get("messages", [])
        tools = request.get("tools") or []
        print(
            f"[mock-openai] roles={[m.get('role') for m in messages]} tools={len(tools)}",
            flush=True,
        )

        # Has the model already seen a tool result? Then it is time to answer for real.
        tool_messages = [m for m in messages if m.get("role") == "tool"]
        if tools and not tool_messages:
            tool_names = [t.get("function", {}).get("name") for t in tools]
            tool_name = "lookupIngredientPrices" if "lookupIngredientPrices" in tool_names else tool_names[0]

            # The model asks for the prices of the ingredients it is about to plan with.
            ingredients = ["番茄", "鸡蛋", "鸡胸肉", "西兰花", "土豆", "猪里脊", "香菇", "食用油"]
            arguments = {"names": ingredients}
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [build_tool_call(tool_name, arguments)],
            }
            self._send(self._completion(message, finish_reason="tool_calls"))
            return

        if tool_messages:
            results = [m.get("content") or "" for m in tool_messages]
            params = parse_user_prompt(messages)
            plan = build_plan(params["days"], params["mealsPerDay"], params["budget"], results)
            message = {"role": "assistant", "content": json.dumps(plan, ensure_ascii=False)}
            self._send(self._completion(message, finish_reason="stop"))
            return

        # No tools configured: answer with a plain message so the endpoint stays usable.
        message = {"role": "assistant", "content": "mock server ready"}
        self._send(self._completion(message, finish_reason="stop"))

    def _completion(self, message: dict, finish_reason: str) -> dict:
        return {
            "id": f"chatcmpl-{uuid.uuid4().hex[:12]}",
            "object": "chat.completion",
            "created": 0,
            "model": "mock-model",
            "choices": [
                {
                    "index": 0,
                    "message": message,
                    "finish_reason": finish_reason,
                }
            ],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        }


def main():
    parser = argparse.ArgumentParser(description="Mock OpenAI-compatible server for offline testing")
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[mock-openai] listening on http://{args.host}:{args.port}/v1")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock-openai] stopped")
        server.shutdown()


if __name__ == "__main__":
    main()

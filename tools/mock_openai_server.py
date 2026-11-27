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
    # 蔬菜
    "丝瓜": ("500g", 5.00, "蔬菜"),
    "冬瓜": ("500g", 2.60, "蔬菜"),
    "南瓜": ("500g", 3.50, "蔬菜"),
    "圆白菜": ("500g", 2.80, "蔬菜"),
    "土豆": ("500g", 2.80, "蔬菜"),
    "娃娃菜": ("400g", 5.80, "蔬菜"),
    "山药": ("500g", 7.00, "蔬菜"),
    "平菇": ("500g", 5.50, "蔬菜"),
    "木耳": ("100g", 6.00, "蔬菜"),
    "油菜": ("400g", 4.00, "蔬菜"),
    "油麦菜": ("400g", 4.20, "蔬菜"),
    "洋葱": ("500g", 2.20, "蔬菜"),
    "玉米": ("2根", 6.00, "蔬菜"),
    "生菜": ("300g", 3.60, "蔬菜"),
    "番茄": ("500g", 4.50, "蔬菜"),
    "白菜": ("500g", 2.90, "蔬菜"),
    "秋葵": ("300g", 8.50, "蔬菜"),
    "空心菜": ("400g", 4.50, "蔬菜"),
    "竹笋": ("500g", 7.50, "蔬菜"),
    "胡萝卜": ("500g", 2.50, "蔬菜"),
    "芋头": ("500g", 5.50, "蔬菜"),
    "芹菜": ("500g", 4.20, "蔬菜"),
    "苦瓜": ("500g", 4.80, "蔬菜"),
    "茄子": ("500g", 3.80, "蔬菜"),
    "茼蒿": ("400g", 4.50, "蔬菜"),
    "莲藕": ("500g", 6.50, "蔬菜"),
    "莴笋": ("500g", 3.40, "蔬菜"),
    "菜花": ("500g", 4.80, "蔬菜"),
    "菠菜": ("500g", 5.50, "蔬菜"),
    "西兰花": ("500g", 6.80, "蔬菜"),
    "西红柿": ("500g", 4.50, "蔬菜"),
    "西葫芦": ("500g", 4.00, "蔬菜"),
    "豆腐": ("400g", 3.50, "蔬菜"),
    "豆芽": ("500g", 2.50, "蔬菜"),
    "豆角": ("500g", 5.20, "蔬菜"),
    "金针菇": ("300g", 4.50, "蔬菜"),
    "青椒": ("500g", 4.20, "蔬菜"),
    "韭菜": ("300g", 4.50, "蔬菜"),
    "香菇": ("500g", 7.90, "蔬菜"),
    "黄瓜": ("500g", 3.20, "蔬菜"),

    # 肉类
    "五花肉": ("500g", 22.00, "肉类"),
    "午餐肉": ("340g", 15.00, "肉类"),
    "培根": ("200g", 12.00, "肉类"),
    "火腿": ("200g", 12.00, "肉类"),
    "牛肉": ("500g", 35.00, "肉类"),
    "牛肉馅": ("500g", 35.00, "肉类"),
    "牛腩": ("500g", 42.00, "肉类"),
    "猪排骨": ("500g", 32.00, "肉类"),
    "猪肉馅": ("500g", 22.00, "肉类"),
    "猪里脊": ("500g", 19.90, "肉类"),
    "羊肉": ("500g", 45.00, "肉类"),
    "鸡爪": ("500g", 15.00, "肉类"),
    "鸡翅": ("500g", 18.00, "肉类"),
    "鸡胸肉": ("500g", 9.90, "肉类"),
    "鸡腿": ("500g", 12.50, "肉类"),
    "鸡蛋": ("10个", 8.50, "肉类"),
    "鸭肉": ("500g", 16.00, "肉类"),

    # 海鲜
    "三文鱼": ("250g", 45.00, "海鲜"),
    "带鱼": ("500g", 22.00, "海鲜"),
    "海带": ("300g", 5.00, "海鲜"),
    "草鱼": ("500g", 14.00, "海鲜"),
    "虾": ("500g", 30.00, "海鲜"),
    "虾仁": ("250g", 26.00, "海鲜"),
    "蛤蜊": ("500g", 12.00, "海鲜"),
    "鱿鱼": ("300g", 18.00, "海鲜"),
    "鲈鱼": ("500g", 28.00, "海鲜"),

    # 主食
    "大米": ("500g", 2.80, "主食"),
    "小米": ("500g", 6.00, "主食"),
    "年糕": ("400g", 7.00, "主食"),
    "挂面": ("500g", 5.20, "主食"),
    "春饼": ("10张", 6.00, "主食"),
    "燕麦": ("500g", 8.00, "主食"),
    "粉丝": ("200g", 4.80, "主食"),
    "糙米": ("500g", 4.50, "主食"),
    "花卷": ("10个", 10.00, "主食"),
    "面条": ("500g", 4.50, "主食"),
    "饺子皮": ("500g", 8.50, "主食"),
    "馒头": ("10个", 10.00, "主食"),

    # 调味
    "大葱": ("200g", 3.50, "调味"),
    "大蒜": ("100g", 3.50, "调味"),
    "姜": ("100g", 3.00, "调味"),
    "料酒": ("500ml", 9.50, "调味"),
    "淀粉": ("200g", 3.50, "调味"),
    "生抽": ("500ml", 7.50, "调味"),
    "番茄酱": ("200g", 5.00, "调味"),
    "白糖": ("500g", 5.00, "调味"),
    "盐": ("500g", 2.50, "调味"),
    "老抽": ("500ml", 8.00, "调味"),
    "胡椒粉": ("100g", 8.00, "调味"),
    "花椒": ("100g", 12.00, "调味"),
    "蚝油": ("500g", 13.00, "调味"),
    "豆瓣酱": ("500g", 12.00, "调味"),
    "醋": ("500ml", 6.50, "调味"),
    "食用油": ("500ml", 9.90, "调味"),
    "香油": ("250ml", 11.00, "调味"),
    "黄豆酱": ("500g", 10.00, "调味"),
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

    {
        "name": "蒜香西葫芦",
        "description": "清爽蒜香，五分钟出锅",
        "category": "side",
        "cookingMinutes": 10,
        "ingredients": [("西葫芦", 400, "g"), ("大蒜", 15, "g"), ("盐", 3, "g"), ("食用油", 10, "ml")],
        "steps": ["西葫芦洗净切薄片", "蒜末用热油爆香", "下西葫芦大火翻炒", "加盐调味出锅"],
    },

    {
        "name": "南瓜小米粥",
        "description": "暖胃易消化，早餐首选",
        "category": "soup",
        "cookingMinutes": 30,
        "ingredients": [("南瓜", 300, "g"), ("小米", 80, "g"), ("白糖", 5, "g")],
        "steps": ["小米淘洗干净", "南瓜去皮切块", "两者加水同煮25分钟", "按口味加糖搅匀"],
    },

    {
        "name": "红烧鸡翅",
        "description": "色泽红亮，甜咸下饭",
        "category": "main",
        "cookingMinutes": 30,
        "ingredients": [("鸡翅", 500, "g"), ("生抽", 15, "ml"), ("老抽", 8, "ml"), ("姜", 10, "g"), ("白糖", 8, "g")],
        "steps": ["鸡翅划刀焯水", "姜片爆香下鸡翅煎至金黄", "加生抽老抽和糖", "加水没过鸡翅炖20分钟收汁"],
    },

    {
        "name": "韭菜炒蛋",
        "description": "春季家常，鲜香嫩滑",
        "category": "main",
        "cookingMinutes": 12,
        "ingredients": [("韭菜", 250, "g"), ("鸡蛋", 3, "个"), ("盐", 3, "g"), ("食用油", 12, "ml")],
        "steps": ["韭菜洗净切段", "鸡蛋打散炒至凝固盛出", "下韭菜大火快炒", "倒回鸡蛋加盐炒匀"],
    },

    {
        "name": "酸辣土豆丝",
        "description": "爽脆开胃，经典下饭菜",
        "category": "side",
        "cookingMinutes": 15,
        "ingredients": [("土豆", 400, "g"), ("青椒", 80, "g"), ("醋", 15, "ml"), ("盐", 3, "g"), ("食用油", 12, "ml")],
        "steps": ["土豆切细丝泡水去淀粉", "青椒切丝", "热油爆香后下土豆丝大火翻炒", "淋醋加盐炒匀出锅"],
    },

    {
        "name": "香菇青菜",
        "description": "清淡鲜美，营养素菜",
        "category": "side",
        "cookingMinutes": 12,
        "ingredients": [("油菜", 400, "g"), ("香菇", 150, "g"), ("大蒜", 10, "g"), ("盐", 3, "g")],
        "steps": ["油菜洗净对半切", "香菇切片", "蒜末爆香先炒香菇", "下油菜加盐炒至断生"],
    },

    {
        "name": "冬瓜虾仁汤",
        "description": "清甜鲜美，低脂高蛋白",
        "category": "soup",
        "cookingMinutes": 20,
        "ingredients": [("冬瓜", 400, "g"), ("虾仁", 150, "g"), ("姜", 8, "g"), ("盐", 4, "g")],
        "steps": ["冬瓜去皮切薄片", "虾仁洗净去虾线", "姜片加水煮开下冬瓜", "煮10分钟后下虾仁加盐"],
    },

    {
        "name": "木耳炒山药",
        "description": "脆爽清甜，养胃小炒",
        "category": "side",
        "cookingMinutes": 15,
        "ingredients": [("木耳", 50, "g"), ("山药", 300, "g"), ("大蒜", 10, "g"), ("盐", 3, "g"), ("食用油", 10, "ml")],
        "steps": ["木耳提前泡发洗净", "山药去皮切薄片", "蒜末爆香下山药翻炒", "加木耳和盐炒匀出锅"],
    },

    {
        "name": "芹菜炒香干",
        "description": "豆香浓郁，家常快手",
        "category": "main",
        "cookingMinutes": 15,
        "ingredients": [("芹菜", 300, "g"), ("豆腐", 250, "g"), ("生抽", 10, "ml"), ("盐", 2, "g"), ("食用油", 12, "ml")],
        "steps": ["芹菜洗净切段焯水", "豆腐切条煎至微黄", "下芹菜大火翻炒", "加生抽和盐炒匀"],
    },

    {
        "name": "西红柿炖牛腩",
        "description": "酸甜软烂，下饭硬菜",
        "category": "main",
        "cookingMinutes": 60,
        "ingredients": [("牛腩", 500, "g"), ("番茄", 400, "g"), ("洋葱", 100, "g"), ("姜", 10, "g"), ("老抽", 8, "ml")],
        "steps": ["牛腩冷水下锅焯水", "洋葱姜片炒香下番茄炒出汁", "加牛腩和热水炖40分钟", "加老抽调味收汁"],
    },

    {
        "name": "莲藕排骨汤",
        "description": "清甜滋润，秋冬养生",
        "category": "soup",
        "cookingMinutes": 60,
        "ingredients": [("莲藕", 400, "g"), ("猪排骨", 400, "g"), ("姜", 10, "g"), ("盐", 5, "g")],
        "steps": ["排骨焯水去血沫", "莲藕去皮切滚刀块", "加姜片和水炖50分钟", "出锅前加盐调味"],
    },

    {
        "name": "清蒸鲈鱼",
        "description": "鲜嫩少油，原汁原味",
        "category": "main",
        "cookingMinutes": 25,
        "ingredients": [("鲈鱼", 500, "g"), ("姜", 15, "g"), ("生抽", 12, "ml"), ("食用油", 10, "ml")],
        "steps": ["鲈鱼处理干净两面划刀", "铺姜片上锅蒸12分钟", "倒掉盘中腥水", "淋生抽和热油"],
    },

    {
        "name": "手撕包菜",
        "description": "干香微辣，饭店味道",
        "category": "side",
        "cookingMinutes": 12,
        "ingredients": [("圆白菜", 400, "g"), ("大蒜", 15, "g"), ("醋", 10, "ml"), ("生抽", 10, "ml"), ("食用油", 12, "ml")],
        "steps": ["圆白菜手撕成片洗净沥干", "蒜片爆香", "下包菜大火快炒", "沿锅边淋醋加生抽炒匀"],
    },

    {
        "name": "玉米排骨汤",
        "description": "清甜可口，老少皆宜",
        "category": "soup",
        "cookingMinutes": 50,
        "ingredients": [("玉米", 2, "根"), ("猪排骨", 400, "g"), ("胡萝卜", 150, "g"), ("姜", 8, "g"), ("盐", 5, "g")],
        "steps": ["排骨焯水去血沫", "玉米切段胡萝卜切块", "加姜片和水炖40分钟", "出锅前加盐调味"],
    },

    {
        "name": "蒜蓉粉丝虾",
        "description": "鲜香入味，宴客硬菜",
        "category": "main",
        "cookingMinutes": 20,
        "ingredients": [("虾", 400, "g"), ("粉丝", 100, "g"), ("大蒜", 30, "g"), ("生抽", 12, "ml"), ("食用油", 15, "ml")],
        "steps": ["粉丝提前泡软铺盘底", "虾去壳开背去虾线摆上", "蒜蓉用热油炒香铺在虾上", "上锅蒸8分钟淋生抽"],
    },

    {
        "name": "青椒炒鸡丁",
        "description": "鲜辣下饭，快手家常",
        "category": "main",
        "cookingMinutes": 20,
        "ingredients": [("鸡胸肉", 350, "g"), ("青椒", 150, "g"), ("生抽", 12, "ml"), ("淀粉", 8, "g"), ("食用油", 12, "ml")],
        "steps": ["鸡胸肉切丁用生抽淀粉抓匀", "青椒切块", "热油下鸡丁滑炒至变色", "加青椒炒匀出锅"],
    },

    {
        "name": "海带蛋花汤",
        "description": "五分钟快手汤，清淡鲜美",
        "category": "soup",
        "cookingMinutes": 8,
        "ingredients": [("海带", 100, "g"), ("鸡蛋", 2, "个"), ("盐", 3, "g"), ("香油", 5, "ml")],
        "steps": ["海带切细丝", "鸡蛋打散", "水烧开下海带煮3分钟淋入蛋液", "加盐和香油出锅"],
    },

    {
        "name": "蚂蚁上树",
        "description": "粉丝吸满肉香，经典川菜",
        "category": "main",
        "cookingMinutes": 20,
        "ingredients": [("粉丝", 150, "g"), ("猪肉馅", 200, "g"), ("豆瓣酱", 15, "g"), ("生抽", 10, "ml"), ("大葱", 20, "g")],
        "steps": ["粉丝温水泡软", "肉馅炒散至出油", "加豆瓣酱和生抽炒香", "加水放粉丝收汁撒葱花"],
    },

    {
        "name": "凉拌黄瓜",
        "description": "清爽解腻，三分钟上桌",
        "category": "side",
        "cookingMinutes": 8,
        "ingredients": [("黄瓜", 400, "g"), ("大蒜", 20, "g"), ("醋", 15, "ml"), ("香油", 8, "ml"), ("盐", 3, "g")],
        "steps": ["黄瓜拍裂切段", "加盐腌5分钟倒掉水分", "蒜末醋香油调成汁", "倒入黄瓜拌匀"],
    },

    {
        "name": "南瓜蒸排骨",
        "description": "香甜软糯，少油健康",
        "category": "main",
        "cookingMinutes": 35,
        "ingredients": [("猪排骨", 400, "g"), ("南瓜", 400, "g"), ("生抽", 12, "ml"), ("淀粉", 10, "g"), ("大蒜", 10, "g")],
        "steps": ["排骨斩块用生抽淀粉蒜末腌20分钟", "南瓜去皮切块铺盘底", "排骨摆在南瓜上", "上锅蒸25分钟"],
    },]


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

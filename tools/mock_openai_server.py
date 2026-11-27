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
    "上海青": ("500g", 4.00, "蔬菜"),
    "丝瓜": ("500g", 5.00, "蔬菜"),
    "冬瓜": ("500g", 2.60, "蔬菜"),
    "包菜": ("1kg", 5.00, "蔬菜"),
    "南瓜": ("500g", 3.50, "蔬菜"),
    "圆白菜": ("500g", 2.80, "蔬菜"),
    "土豆": ("500g", 2.80, "蔬菜"),
    "大白菜": ("1kg", 4.00, "蔬菜"),
    "娃娃菜": ("400g", 5.80, "蔬菜"),
    "山药": ("500g", 7.00, "蔬菜"),
    "彩椒": ("500g", 8.00, "蔬菜"),
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
    "芦笋": ("200g", 12.00, "蔬菜"),
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
    "豆芽": ("500g", 2.50, "蔬菜"),
    "豆角": ("500g", 5.20, "蔬菜"),
    "青椒": ("500g", 4.20, "蔬菜"),
    "韭菜": ("300g", 4.50, "蔬菜"),
    "黄瓜": ("500g", 3.20, "蔬菜"),
    "圣女果": ("250g", 6.00, "蔬菜"),
    "柠檬": ("1个", 3.00, "蔬菜"),

    # 菌菇
    "口蘑": ("250g", 8.00, "菌菇"),
    "平菇": ("500g", 5.50, "菌菇"),
    "木耳": ("100g", 6.00, "菌菇"),
    "木耳干": ("50g", 10.00, "菌菇"),
    "杏鲍菇": ("300g", 7.00, "菌菇"),
    "白玉菇": ("150g", 6.00, "菌菇"),
    "蟹味菇": ("150g", 6.00, "菌菇"),
    "金针菇": ("300g", 4.50, "菌菇"),
    "香菇": ("500g", 7.90, "菌菇"),

    # 肉禽
    "五花肉": ("500g", 22.00, "肉禽"),
    "午餐肉": ("340g", 15.00, "肉禽"),
    "培根": ("200g", 12.00, "肉禽"),
    "排骨": ("500g", 35.00, "肉禽"),
    "火腿": ("200g", 12.00, "肉禽"),
    "火腿肠": ("150g", 4.00, "肉禽"),
    "牛排": ("200g", 35.00, "肉禽"),
    "牛肉": ("500g", 35.00, "肉禽"),
    "牛肉里脊": ("500g", 45.00, "肉禽"),
    "牛肉馅": ("500g", 35.00, "肉禽"),
    "牛腩": ("500g", 42.00, "肉禽"),
    "猪排骨": ("500g", 32.00, "肉禽"),
    "猪肉里脊": ("500g", 22.00, "肉禽"),
    "猪肉馅": ("500g", 22.00, "肉禽"),
    "猪肝": ("250g", 10.00, "肉禽"),
    "猪里脊": ("500g", 19.90, "肉禽"),
    "羊肉": ("500g", 45.00, "肉禽"),
    "羊肉卷": ("500g", 35.00, "肉禽"),
    "鸡爪": ("500g", 15.00, "肉禽"),
    "鸡翅": ("500g", 18.00, "肉禽"),
    "鸡翅中": ("500g", 25.00, "肉禽"),
    "鸡胗": ("300g", 12.00, "肉禽"),
    "鸡胸肉": ("500g", 9.90, "肉禽"),
    "鸡腿": ("500g", 12.50, "肉禽"),
    "鸭肉": ("500g", 16.00, "肉禽"),
    "鸭腿": ("500g", 18.00, "肉禽"),

    # 蛋豆
    "冻豆腐": ("400g", 6.00, "蛋豆"),
    "千张": ("200g", 6.00, "蛋豆"),
    "咸蛋": ("4枚", 8.00, "蛋豆"),
    "嫩豆腐": ("400g", 4.00, "蛋豆"),
    "皮蛋": ("4枚", 8.00, "蛋豆"),
    "腐竹": ("100g", 8.00, "蛋豆"),
    "豆皮": ("150g", 5.00, "蛋豆"),
    "豆腐": ("400g", 3.50, "蛋豆"),
    "香干": ("200g", 6.00, "蛋豆"),
    "鸡蛋": ("10个", 8.50, "蛋豆"),
    "鸭血": ("300g", 5.00, "蛋豆"),
    "鹌鹑蛋": ("10枚", 15.00, "蛋豆"),

    # 水产
    "三文鱼": ("250g", 45.00, "水产"),
    "带鱼": ("500g", 22.00, "水产"),
    "干贝": ("50g", 25.00, "水产"),
    "海带": ("300g", 5.00, "水产"),
    "紫菜": ("10g", 8.00, "水产"),
    "草鱼": ("500g", 14.00, "水产"),
    "虾": ("500g", 30.00, "水产"),
    "虾仁": ("250g", 26.00, "水产"),
    "虾米": ("50g", 15.00, "水产"),
    "蛤蜊": ("500g", 12.00, "水产"),
    "鱿鱼": ("300g", 18.00, "水产"),
    "鲈鱼": ("500g", 28.00, "水产"),
    "龙利鱼柳": ("500g", 20.00, "水产"),

    # 粮油
    "大米": ("500g", 2.80, "粮油"),
    "小米": ("500g", 6.00, "粮油"),
    "年糕": ("400g", 7.00, "粮油"),
    "意大利面": ("500g", 8.00, "粮油"),
    "挂面": ("500g", 5.20, "粮油"),
    "春饼": ("10张", 6.00, "粮油"),
    "燕麦": ("500g", 8.00, "粮油"),
    "燕麦片": ("500g", 10.00, "粮油"),
    "粉丝": ("200g", 4.80, "粮油"),
    "糙米": ("500g", 4.50, "粮油"),
    "花卷": ("10个", 10.00, "粮油"),
    "面条": ("500g", 4.50, "粮油"),
    "面粉": ("1kg", 6.00, "粮油"),
    "饺子皮": ("500g", 8.50, "粮油"),
    "馒头": ("10个", 10.00, "粮油"),

    # 调味
    "大葱": ("200g", 3.50, "调味"),
    "大蒜": ("100g", 3.50, "调味"),
    "姜": ("100g", 3.00, "调味"),
    "孜然粉": ("50g", 6.00, "调味"),
    "干辣椒": ("50g", 6.00, "调味"),
    "料酒": ("500ml", 9.50, "调味"),
    "橄榄油": ("500ml", 35.00, "调味"),
    "沙拉酱": ("200g", 10.00, "调味"),
    "淀粉": ("200g", 3.50, "调味"),
    "生抽": ("500ml", 7.50, "调味"),
    "番茄酱": ("200g", 5.00, "调味"),
    "白糖": ("500g", 5.00, "调味"),
    "盐": ("500g", 2.50, "调味"),
    "米醋": ("500ml", 6.00, "调味"),
    "老抽": ("500ml", 8.00, "调味"),
    "胡椒粉": ("100g", 8.00, "调味"),
    "芝麻酱": ("200g", 12.00, "调味"),
    "花椒": ("100g", 12.00, "调味"),
    "花生油": ("1L", 25.00, "调味"),
    "菜籽油": ("1L", 18.00, "调味"),
    "蚝油": ("500g", 13.00, "调味"),
    "蜂蜜": ("500g", 25.00, "调味"),
    "豆瓣酱": ("500g", 12.00, "调味"),
    "醋": ("500ml", 6.50, "调味"),
    "食用油": ("500ml", 9.90, "调味"),
    "香油": ("250ml", 11.00, "调味"),
    "香醋": ("500ml", 8.00, "调味"),
    "黄豆酱": ("500g", 10.00, "调味"),
    "黑胡椒粉": ("50g", 8.00, "调味"),
    "可乐": ("330ml", 3.00, "调味"),
    "蒸鱼豉油": ("500ml", 10.00, "调味"),

    # 乳品
    "纯牛奶": ("1L", 10.00, "乳品"),
    "芝士片": ("200g", 18.00, "乳品"),
    "酸奶": ("500g", 10.00, "乳品"),
    "黄油": ("200g", 20.00, "乳品"),
}

# Recipes are picked per day so the generated plan looks like a real menu.
RECIPE_TEMPLATES = [
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
        "name": "蒜蓉生菜",
        "description": "3分钟出锅，解腻神器",
        "category": "side",
        "cookingMinutes": 8,
        "ingredients": [("生菜", 300, "g"), ("大蒜", 15, "g"), ("生抽", 8, "ml")],
        "steps": ["生菜掰开洗净", "蒜末爆香", "大火下生菜", "淋生抽出锅"],
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
    },
    {
        "name": "蒜香西葫芦",
        "description": "12 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 12,
        "ingredients": [("西葫芦", 300, "g"), ("大蒜", 15, "g"), ("盐", 3, "g"), ("菜籽油", 10, "g")],
        "steps": ["西葫芦切片", "蒜末爆香", "下锅翻炒", "加盐出锅"],
    },
    {
        "name": "番茄炒蛋",
        "description": "10 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 10,
        "ingredients": [("西红柿", 250, "g"), ("鸡蛋", 3, "个"), ("白糖", 3, "g"), ("盐", 3, "g"), ("菜籽油", 15, "g")],
        "steps": ["西红柿切块", "鸡蛋炒熟盛出", "番茄炒出汁", "回锅调味出锅"],
    },
    {
        "name": "清炒上海青",
        "description": "8 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 8,
        "ingredients": [("上海青", 400, "g"), ("大蒜", 10, "g"), ("盐", 3, "g"), ("菜籽油", 10, "g")],
        "steps": ["青菜洗净沥干", "蒜末爆香", "大火快炒", "加盐出锅"],
    },
    {
        "name": "酸辣土豆丝",
        "description": "12 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 12,
        "ingredients": [("土豆", 300, "g"), ("干辣椒", 3, "g"), ("米醋", 8, "ml"), ("盐", 3, "g"), ("菜籽油", 12, "g")],
        "steps": ["土豆切丝泡水", "干辣椒爆香", "下锅大火翻炒", "沿锅边加醋", "加盐出锅"],
    },
    {
        "name": "地三鲜",
        "description": "25 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 25,
        "ingredients": [("土豆", 200, "g"), ("茄子", 200, "g"), ("青椒", 80, "g"), ("生抽", 10, "ml"), ("盐", 3, "g"), ("菜籽油", 25, "g")],
        "steps": ["土豆茄子切块", "茄子拍粉过油", "土豆煎熟", "青椒炒香", "同锅加生抽调味"],
    },
    {
        "name": "蒜蓉西兰花",
        "description": "12 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 12,
        "ingredients": [("西兰花", 350, "g"), ("大蒜", 15, "g"), ("盐", 3, "g"), ("橄榄油", 8, "g")],
        "steps": ["西兰花焯水", "蒜末炒香", "下锅翻匀", "加盐出锅"],
    },
    {
        "name": "蚝油生菜",
        "description": "8 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 8,
        "ingredients": [("生菜", 400, "g"), ("蚝油", 15, "g"), ("大蒜", 10, "g"), ("菜籽油", 10, "g")],
        "steps": ["生菜焯水摆盘", "蒜末爆香", "加蚝油与少量水煮开", "淋汁"],
    },
    {
        "name": "清炒芦笋虾仁",
        "description": "15 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 15,
        "ingredients": [("芦笋", 200, "g"), ("虾仁", 150, "g"), ("大蒜", 8, "g"), ("盐", 3, "g"), ("橄榄油", 10, "g")],
        "steps": ["芦笋切段焯水", "虾仁煎熟", "蒜末爆香", "合炒调味出锅"],
    },
    {
        "name": "香煎鸡胸肉",
        "description": "18 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 18,
        "ingredients": [("鸡胸肉", 250, "g"), ("黑胡椒粉", 3, "g"), ("盐", 3, "g"), ("橄榄油", 10, "g")],
        "steps": ["鸡胸肉拍平腌制", "热锅少油", "中小火每面煎4分钟", "醒肉切片"],
    },
    {
        "name": "黑椒牛柳",
        "description": "18 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 18,
        "ingredients": [("牛肉里脊", 200, "g"), ("彩椒", 100, "g"), ("洋葱", 80, "g"), ("黑胡椒粉", 4, "g"), ("生抽", 10, "ml"), ("菜籽油", 15, "g")],
        "steps": ["牛肉切条腌10分钟", "彩椒洋葱切块", "大火滑炒牛肉", "下配菜加黑椒生抽出锅"],
    },
    {
        "name": "土豆炖牛腩",
        "description": "60 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 60,
        "ingredients": [("牛腩", 500, "g"), ("土豆", 300, "g"), ("胡萝卜", 100, "g"), ("洋葱", 80, "g"), ("生抽", 15, "ml"), ("料酒", 15, "ml"), ("盐", 5, "g")],
        "steps": ["牛腩焯水", "炒香洋葱", "加料酒生抽炖40分钟", "放土豆胡萝卜再炖15分钟", "加盐收汁"],
    },
    {
        "name": "红烧肉",
        "description": "55 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 55,
        "ingredients": [("五花肉", 500, "g"), ("白糖", 20, "g"), ("生抽", 15, "ml"), ("老抽", 8, "ml"), ("料酒", 20, "ml"), ("姜", 10, "g")],
        "steps": ["五花肉切块焯水", "炒糖色", "下肉上色", "加料酒生抽老抽", "小火炖45分钟收汁"],
    },
    {
        "name": "可乐鸡翅",
        "description": "30 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 30,
        "ingredients": [("鸡翅中", 400, "g"), ("可乐", 300, "ml"), ("生抽", 15, "ml"), ("料酒", 10, "ml"), ("姜", 8, "g")],
        "steps": ["鸡翅划口焯水", "煎两面微黄", "加可乐生抽料酒", "小火焖20分钟", "大火收汁"],
    },
    {
        "name": "蒜蓉粉丝蒸虾",
        "description": "18 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 18,
        "ingredients": [("虾仁", 200, "g"), ("粉丝", 50, "g"), ("大蒜", 25, "g"), ("生抽", 12, "ml"), ("菜籽油", 15, "g")],
        "steps": ["粉丝泡软垫底", "蒜蓉一半炸香", "虾仁摆面", "淋生抽蒜蓉", "上汽蒸8分钟"],
    },
    {
        "name": "清蒸鲈鱼/龙利鱼",
        "description": "15 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 15,
        "ingredients": [("龙利鱼柳", 300, "g"), ("姜", 10, "g"), ("大葱", 20, "g"), ("蒸鱼豉油", 15, "ml"), ("菜籽油", 15, "g")],
        "steps": ["鱼柳改花刀", "姜葱垫底", "上汽蒸8分钟", "倒腥水淋豉油", "浇热油"],
    },
    {
        "name": "香煎三文鱼",
        "description": "12 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 12,
        "ingredients": [("三文鱼", 200, "g"), ("黑胡椒粉", 3, "g"), ("盐", 2, "g"), ("柠檬", 20, "g"), ("橄榄油", 8, "g")],
        "steps": ["三文鱼擦干调味", "热锅下油", "皮面先煎3分钟", "翻面2分钟", "挤柠檬汁"],
    },
    {
        "name": "虾仁滑蛋",
        "description": "10 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 10,
        "ingredients": [("虾仁", 150, "g"), ("鸡蛋", 4, "个"), ("纯牛奶", 20, "ml"), ("盐", 3, "g"), ("菜籽油", 12, "g")],
        "steps": ["虾仁煎七成熟", "蛋液加牛奶盐", "回锅小火推炒", "凝固即出锅"],
    },
    {
        "name": "番茄豆腐汤",
        "description": "15 分钟家常菜，步骤清晰易操作",
        "category": "soup",
        "cookingMinutes": 15,
        "ingredients": [("西红柿", 200, "g"), ("嫩豆腐", 300, "g"), ("鸡蛋", 1, "个"), ("盐", 3, "g"), ("香油", 3, "ml")],
        "steps": ["番茄炒出沙", "加水煮开", "下豆腐煮3分钟", "淋蛋液", "调味点香油"],
    },
    {
        "name": "紫菜蛋花汤",
        "description": "8 分钟家常菜，步骤清晰易操作",
        "category": "soup",
        "cookingMinutes": 8,
        "ingredients": [("紫菜", 5, "g"), ("鸡蛋", 2, "个"), ("虾米", 5, "g"), ("盐", 2, "g"), ("香油", 3, "ml")],
        "steps": ["水开下紫菜虾米", "调味", "淋入蛋液成花", "关火点香油"],
    },
    {
        "name": "冬瓜排骨汤",
        "description": "50 分钟家常菜，步骤清晰易操作",
        "category": "soup",
        "cookingMinutes": 50,
        "ingredients": [("排骨", 400, "g"), ("冬瓜", 400, "g"), ("姜", 10, "g"), ("料酒", 15, "ml"), ("盐", 4, "g")],
        "steps": ["排骨焯水", "加姜料酒炖30分钟", "下冬瓜再炖15分钟", "加盐出锅"],
    },
    {
        "name": "玉米胡萝卜鸡汤",
        "description": "55 分钟家常菜，步骤清晰易操作",
        "category": "soup",
        "cookingMinutes": 55,
        "ingredients": [("鸡腿", 400, "g"), ("玉米", 1, "根"), ("胡萝卜", 100, "g"), ("姜", 10, "g"), ("盐", 4, "g")],
        "steps": ["鸡腿焯水", "加姜炖25分钟", "放玉米胡萝卜", "再炖20分钟", "加盐"],
    },
    {
        "name": "西兰花豆腐蛋汤",
        "description": "15 分钟家常菜，步骤清晰易操作",
        "category": "soup",
        "cookingMinutes": 15,
        "ingredients": [("西兰花", 150, "g"), ("豆腐", 250, "g"), ("鸡蛋", 2, "个"), ("盐", 3, "g"), ("香油", 3, "ml")],
        "steps": ["西兰花焯水", "水开下豆腐", "淋蛋液", "加西兰花调味点香油"],
    },
    {
        "name": "口蘑鸡汤",
        "description": "35 分钟家常菜，步骤清晰易操作",
        "category": "soup",
        "cookingMinutes": 35,
        "ingredients": [("鸡腿", 300, "g"), ("口蘑", 150, "g"), ("姜", 8, "g"), ("盐", 3, "g"), ("料酒", 10, "ml")],
        "steps": ["鸡腿焯水", "加姜料酒炖20分钟", "下口蘑炖10分钟", "加盐出锅"],
    },
    {
        "name": "酸辣汤",
        "description": "15 分钟家常菜，步骤清晰易操作",
        "category": "soup",
        "cookingMinutes": 15,
        "ingredients": [("豆腐", 200, "g"), ("木耳干", 10, "g"), ("鸡蛋", 2, "个"), ("香醋", 15, "ml"), ("黑胡椒粉", 2, "g"), ("盐", 3, "g")],
        "steps": ["木耳泡切丝", "水开下豆腐木耳", "调味加醋", "淋蛋液", "撒胡椒粉"],
    },
    {
        "name": "干贝冬瓜汤",
        "description": "25 分钟家常菜，步骤清晰易操作",
        "category": "soup",
        "cookingMinutes": 25,
        "ingredients": [("干贝", 20, "g"), ("冬瓜", 350, "g"), ("姜", 5, "g"), ("盐", 2, "g")],
        "steps": ["干贝泡软", "冬瓜切块", "水开同煮15分钟", "加盐出锅"],
    },
    {
        "name": "麻婆豆腐",
        "description": "18 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 18,
        "ingredients": [("嫩豆腐", 400, "g"), ("猪肉馅", 80, "g"), ("豆瓣酱", 20, "g"), ("花椒", 3, "g"), ("生抽", 8, "ml"), ("菜籽油", 18, "g")],
        "steps": ["豆腐焯水", "肉末炒散", "下豆瓣酱花椒出红油", "加少量水", "下豆腐轻推收汁"],
    },
    {
        "name": "家常豆腐",
        "description": "20 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 20,
        "ingredients": [("豆腐", 350, "g"), ("木耳干", 10, "g"), ("青椒", 60, "g"), ("生抽", 12, "ml"), ("蚝油", 8, "g"), ("菜籽油", 15, "g")],
        "steps": ["豆腐切块煎至金黄", "木耳青椒炒香", "下调料水", "回锅豆腐焖3分钟"],
    },
    {
        "name": "香干芹菜",
        "description": "12 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 12,
        "ingredients": [("香干", 200, "g"), ("芹菜", 200, "g"), ("生抽", 8, "ml"), ("盐", 2, "g"), ("菜籽油", 10, "g")],
        "steps": ["香干切片", "芹菜切段焯水", "下锅同炒", "加生抽盐出锅"],
    },
    {
        "name": "千张炒肉丝",
        "description": "18 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 18,
        "ingredients": [("千张", 200, "g"), ("猪肉里脊", 120, "g"), ("青椒", 50, "g"), ("生抽", 10, "ml"), ("菜籽油", 12, "g")],
        "steps": ["肉丝滑熟", "千张切丝焯水", "青椒炒香", "合炒加生抽出锅"],
    },
    {
        "name": "腐竹木耳炒蛋",
        "description": "15 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 15,
        "ingredients": [("腐竹", 80, "g"), ("木耳干", 15, "g"), ("鸡蛋", 3, "个"), ("生抽", 8, "ml"), ("盐", 2, "g"), ("菜籽油", 15, "g")],
        "steps": ["腐竹木耳泡发焯水", "鸡蛋炒熟", "合炒", "调味出锅"],
    },
    {
        "name": "青椒肉丝",
        "description": "15 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 15,
        "ingredients": [("猪肉里脊", 180, "g"), ("青椒", 150, "g"), ("生抽", 10, "ml"), ("料酒", 5, "ml"), ("盐", 2, "g"), ("菜籽油", 15, "g")],
        "steps": ["肉丝腌10分钟", "滑熟盛出", "青椒炒断生", "回锅调味出锅"],
    },
    {
        "name": "洋葱炒牛肉",
        "description": "16 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 16,
        "ingredients": [("牛肉里脊", 200, "g"), ("洋葱", 200, "g"), ("黑胡椒粉", 3, "g"), ("生抽", 10, "ml"), ("菜籽油", 15, "g")],
        "steps": ["牛肉切片腌", "洋葱切丝", "大火炒牛肉", "下洋葱", "加黑椒生抽出锅"],
    },
    {
        "name": "芹菜炒猪肝",
        "description": "18 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 18,
        "ingredients": [("猪肝", 250, "g"), ("芹菜", 150, "g"), ("料酒", 10, "ml"), ("生抽", 8, "ml"), ("姜", 8, "g"), ("菜籽油", 18, "g")],
        "steps": ["猪肝切片泡血水", "加料酒姜抓匀", "滑炒至变色", "下芹菜", "调味出锅"],
    },
    {
        "name": "香辣鸡胗",
        "description": "20 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 20,
        "ingredients": [("鸡胗", 300, "g"), ("干辣椒", 5, "g"), ("花椒", 2, "g"), ("料酒", 10, "ml"), ("盐", 3, "g"), ("菜籽油", 20, "g")],
        "steps": ["鸡胗焯水切花", "爆香干辣椒花椒", "大火爆炒", "加料酒盐出锅"],
    },
    {
        "name": "卤味鸡爪",
        "description": "45 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 45,
        "ingredients": [("鸡爪", 500, "g"), ("生抽", 20, "ml"), ("老抽", 10, "ml"), ("料酒", 20, "ml"), ("白糖", 10, "g"), ("姜", 10, "g")],
        "steps": ["鸡爪剪甲焯水", "炒香糖色", "加料酒生抽老抽", "加水卤35分钟", "浸泡入味"],
    },
    {
        "name": "鸭血豆腐煲",
        "description": "20 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 20,
        "ingredients": [("鸭血", 300, "g"), ("豆腐", 250, "g"), ("豆瓣酱", 15, "g"), ("大蒜", 10, "g"), ("生抽", 8, "ml"), ("菜籽油", 15, "g")],
        "steps": ["鸭血豆腐切块焯水", "豆瓣酱蒜爆香", "加少量水", "下食材焖8分钟"],
    },
    {
        "name": "清蒸蛋羹",
        "description": "12 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 12,
        "ingredients": [("鸡蛋", 3, "个"), ("盐", 2, "g"), ("生抽", 5, "ml"), ("香油", 3, "ml")],
        "steps": ["蛋液加温水盐", "过筛去泡", "盖膜扎孔", "上汽蒸10分钟", "淋生抽香油"],
    },
    {
        "name": "番茄炖牛腩面",
        "description": "50 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 50,
        "ingredients": [("牛腩", 300, "g"), ("西红柿", 250, "g"), ("挂面", 120, "g"), ("洋葱", 50, "g"), ("生抽", 15, "ml"), ("盐", 4, "g")],
        "steps": ["牛腩焯水", "番茄洋葱炒软", "加生抽炖40分钟", "煮面", "浇汤"],
    },
    {
        "name": "葱油拌面",
        "description": "15 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 15,
        "ingredients": [("挂面", 120, "g"), ("大葱", 30, "g"), ("生抽", 15, "ml"), ("白糖", 5, "g"), ("菜籽油", 20, "g")],
        "steps": ["小葱熬葱油", "加生抽糖成汁", "煮面过水", "拌匀装盘"],
    },
    {
        "name": "意大利面番茄肉酱",
        "description": "35 分钟家常菜，步骤清晰易操作",
        "category": "main",
        "cookingMinutes": 35,
        "ingredients": [("意大利面", 120, "g"), ("猪肉馅", 120, "g"), ("西红柿", 250, "g"), ("洋葱", 50, "g"), ("番茄酱", 30, "g"), ("黑胡椒粉", 2, "g")],
        "steps": ["煮面留汤", "肉末炒散", "番茄洋葱炒出汁", "加番茄酱黑胡椒", "拌面"],
    },
    {
        "name": "南瓜小米粥",
        "description": "30 分钟家常菜，步骤清晰易操作",
        "category": "soup",
        "cookingMinutes": 30,
        "ingredients": [("小米", 60, "g"), ("南瓜", 200, "g")],
        "steps": ["小米淘洗", "南瓜切块", "同煮20分钟", "搅至微稠"],
    },
    {
        "name": "牛奶燕麦粥",
        "description": "12 分钟家常菜，步骤清晰易操作",
        "category": "soup",
        "cookingMinutes": 12,
        "ingredients": [("燕麦片", 60, "g"), ("纯牛奶", 400, "ml"), ("蜂蜜", 10, "g")],
        "steps": ["燕麦加水煮3分钟", "倒入牛奶", "小火2分钟", "关火加蜂蜜"],
    },
    {
        "name": "芝士焗土豆泥",
        "description": "25 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 25,
        "ingredients": [("土豆", 400, "g"), ("纯牛奶", 60, "ml"), ("黄油", 15, "g"), ("芝士片", 40, "g"), ("盐", 2, "g")],
        "steps": ["土豆蒸熟压泥", "加黄油牛奶盐", "铺芝士", "180℃烤10分钟"],
    },
    {
        "name": "蔬菜沙拉",
        "description": "10 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 10,
        "ingredients": [("生菜", 100, "g"), ("黄瓜", 80, "g"), ("玉米", 50, "g"), ("圣女果", 60, "g"), ("沙拉酱", 20, "g")],
        "steps": ["蔬菜洗净沥干", "玉米煮熟剥粒", "切配装盘", "淋沙拉酱"],
    },
    {
        "name": "白灼秋葵",
        "description": "10 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 10,
        "ingredients": [("秋葵", 200, "g"), ("生抽", 10, "ml"), ("大蒜", 8, "g"), ("菜籽油", 12, "g")],
        "steps": ["秋葵焯水2分钟", "切蒂摆盘", "淋生抽", "浇蒜油"],
    },
    {
        "name": "凉拌木耳黄瓜",
        "description": "15 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 15,
        "ingredients": [("木耳干", 20, "g"), ("黄瓜", 150, "g"), ("香醋", 12, "ml"), ("生抽", 8, "ml"), ("香油", 5, "ml"), ("大蒜", 8, "g")],
        "steps": ["木耳泡发焯熟", "黄瓜拍块", "加蒜香醋生抽香油", "拌匀冷藏更脆"],
    },
    {
        "name": "皮蛋豆腐",
        "description": "8 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 8,
        "ingredients": [("皮蛋", 2, "枚"), ("嫩豆腐", 300, "g"), ("生抽", 10, "ml"), ("香醋", 5, "ml"), ("香油", 3, "ml"), ("大葱", 5, "g")],
        "steps": ["豆腐倒盘切块", "皮蛋切瓣", "淋生抽香醋香油", "撒葱花"],
    },
    {
        "name": "咸蛋焗南瓜",
        "description": "25 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 25,
        "ingredients": [("南瓜", 350, "g"), ("咸蛋", 2, "枚"), ("黄油", 10, "g"), ("盐", 1, "g")],
        "steps": ["南瓜切块蒸熟", "咸蛋取黄压碎", "黄油炒蛋黄起泡", "裹南瓜"],
    },
    {
        "name": "香菇油菜",
        "description": "12 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 12,
        "ingredients": [("香菇", 150, "g"), ("上海青", 300, "g"), ("蚝油", 12, "ml"), ("大蒜", 8, "g"), ("菜籽油", 10, "g")],
        "steps": ["香菇切片焯水", "上海青焯水摆盘", "蒜香蚝油成汁", "浇上"],
    },
    {
        "name": "干煸菜花",
        "description": "16 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 16,
        "ingredients": [("菜花", 350, "g"), ("五花肉", 80, "g"), ("干辣椒", 4, "g"), ("生抽", 8, "ml"), ("盐", 2, "g"), ("菜籽油", 12, "g")],
        "steps": ["菜花焯水沥干", "五花肉煸出油", "下干辣椒", "大火煸菜花", "调味出锅"],
    },
    {
        "name": "蒜蓉蒸茄子",
        "description": "18 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 18,
        "ingredients": [("茄子", 350, "g"), ("大蒜", 25, "g"), ("生抽", 12, "ml"), ("香油", 5, "ml"), ("大葱", 5, "g")],
        "steps": ["茄子切条蒸8分钟", "蒜蓉一半炸香", "调生抽汁", "淋茄条撒葱花"],
    },
    {
        "name": "红烧冬瓜",
        "description": "18 分钟家常菜，步骤清晰易操作",
        "category": "side",
        "cookingMinutes": 18,
        "ingredients": [("冬瓜", 400, "g"), ("生抽", 10, "ml"), ("老抽", 4, "ml"), ("白糖", 5, "g"), ("菜籽油", 12, "g")],
        "steps": ["冬瓜切厚片", "煎至微黄", "加生抽老抽糖", "加水焖8分钟", "收汁"],
    },
    {
        "name": "丝瓜蛋汤",
        "description": "12 分钟家常菜，步骤清晰易操作",
        "category": "soup",
        "cookingMinutes": 12,
        "ingredients": [("丝瓜", 250, "g"), ("鸡蛋", 2, "个"), ("盐", 3, "g"), ("香油", 3, "ml")],
        "steps": ["丝瓜削皮切块", "水开下丝瓜煮3分钟", "淋蛋液", "调味点香油"],
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

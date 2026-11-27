#!/usr/bin/env bash
# End-to-end verification of the backend against the bundled mock OpenAI server.
#
# Everything runs inside one shell session on purpose: background processes from a previous
# command are not reachable in this sandbox, so the mock, the backend and the HTTP calls all
# have to share a single session.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOCK_PORT="${MOCK_PORT:-8095}"
BACKEND_PORT="${BACKEND_PORT:-8080}"
OUT="${OUT:-$(mktemp -d 2>/dev/null || echo /tmp/meal-planner-$$)}"
mkdir -p "$OUT"

# Python runs on Windows and needs the native path, not the Git Bash one.
if command -v cygpath > /dev/null 2>&1; then
  WIN_OUT="$(cygpath -w "$OUT")"
else
  WIN_OUT="$OUT"
fi

# On this Windows host the JDK and Maven live outside PATH. On CI both are already set up,
# so only prepend them when they actually exist.
LOCAL_JDK="/c/Program Files/Java/jdk-21"
LOCAL_MAVEN="/c/Users/${USER:-$(id -un)}/.workbuddy-ai/binaries/apache-maven-3.9.9/bin"
if [[ -z "${JAVA_HOME:-}" && -d "$LOCAL_JDK" ]]; then
  export JAVA_HOME="$LOCAL_JDK"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
[[ -d "$LOCAL_MAVEN" ]] && export PATH="$LOCAL_MAVEN:$PATH"
java -version 2>&1 | head -1

cleanup() {
  [[ -n "${BACKEND_PID:-}" ]] && kill "$BACKEND_PID" 2>/dev/null
  [[ -n "${MOCK_PID:-}" ]] && kill "$MOCK_PID" 2>/dev/null
}
trap cleanup EXIT

fail=0
step() { printf '\n=== %s ===\n' "$1"; }
check() {
  if [[ "$1" == "0" ]]; then printf '  [PASS] %s\n' "$2"; else printf '  [FAIL] %s\n' "$2"; fail=1; fi
}

step "启动 mock OpenAI server (:$MOCK_PORT)"
python -u "$ROOT/tools/mock_openai_server.py" --port "$MOCK_PORT" > "$OUT/mock.log" 2>&1 &
MOCK_PID=$!
for _ in $(seq 1 40); do
  curl -fsS "http://127.0.0.1:$MOCK_PORT/v1/models" > /dev/null 2>&1 && break
  sleep 0.5
done
curl -fsS "http://127.0.0.1:$MOCK_PORT/v1/models" > /dev/null 2>&1
check $? "mock server 已就绪"

step "启动后端 (:$BACKEND_PORT)"
java -Dfile.encoding=UTF-8 -jar "$ROOT/backend/target/meal-planner-backend-0.1.0.jar" \
  --server.port="$BACKEND_PORT" \
  --spring.ai.openai.base-url="http://127.0.0.1:$MOCK_PORT" \
  --spring.ai.openai.api-key=mock \
  --spring.ai.openai.chat.options.model=mock-model \
  > "$OUT/backend.log" 2>&1 &
BACKEND_PID=$!
for _ in $(seq 1 80); do
  curl -fsS "http://127.0.0.1:$BACKEND_PORT/actuator/health" > /dev/null 2>&1 && break
  sleep 0.5
done
curl -fsS "http://127.0.0.1:$BACKEND_PORT/actuator/health" > /dev/null 2>&1
check $? "后端健康检查通过"

step "POST /api/v1/plans （3 天 / 不吃辣 / 预算 100 元）"
code=$(curl -s --max-time 120 -o "$OUT/plan.json" -w '%{http_code}' \
  -X POST "http://127.0.0.1:$BACKEND_PORT/api/v1/plans" \
  -H 'Content-Type: application/json' \
  -d '{"days":3,"avoidSpicy":true,"budget":100,"mealsPerDay":3,"servings":2,"preferences":["清淡"],"excludedIngredients":[]}')
echo "  HTTP $code"
check "$([ "$code" = "200" ] && echo 0 || echo 1)" "规划接口返回 200"

if [[ "$code" == "200" ]]; then
  python - "$WIN_OUT/plan.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
plan = d["plan"]
print(f"  toolCalls       : {len(d.get('toolCalls', []))} 次")
for c in d.get("toolCalls", []):
    print(f"     -> {c['tool']}({c['arguments'][:80]})")
print(f"  days           : {len(plan['days'])}")
for day in plan["days"]:
    print(f"     第{day['day']}天 {day['label']}: {[m['name'] for m in day['meals']]}  ¥{day['estimatedCost']}")
print(f"  shoppingList    : {len(d['shoppingList'])} 项")
for i in d["shoppingList"][:6]:
    print(f"     {i['name']} {i['quantity']}{i['unit']} ¥{i['estimatedCost']} ({i['category']})")
print(f"  shoppingCost    : ¥{plan['estimatedShoppingCost']}")
print(f"  summary         : {plan['summary']}")
print(f"  tips            : {len(plan.get('tips', []))} 条")
spicy = {m["spicyLevel"] for day in plan["days"] for m in day["meals"]}
print(f"  spicyLevel set  : {spicy}")
PY
  echo
  python - "$WIN_OUT/plan.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
plan = d["plan"]
ok = True
def check(cond, msg):
    global ok
    print(f"  [{'PASS' if cond else 'FAIL'}] {msg}")
    ok = ok and cond
check(len(d.get("toolCalls", [])) > 0, "模型发起了 Function Calling（toolCalls 非空）")
check(all(c.get("result") for c in d.get("toolCalls", [])), "每次工具调用的返回值都被记录")
check(len(plan["days"]) == 3, "返回 3 天菜单")
check(len(d["shoppingList"]) > 0, "购物清单非空")
check(plan["estimatedShoppingCost"] > 0, "采购成本已计算")
check(plan["estimatedShoppingCost"] <= 100, f"采购成本未超预算（¥{plan['estimatedShoppingCost']} <= 100）")
check(f"{plan['estimatedShoppingCost']:.2f}" in plan["summary"],
      f"summary 中的金额与实算一致（¥{plan['estimatedShoppingCost']}）")
check({m["spicyLevel"] for day in plan["days"] for m in day["meals"]} == {"none"}, "所有菜品均不辣")
check(all(day["meals"] for day in plan["days"]), "每天都有菜品")
sys.exit(0 if ok else 1)
PY
  check $? "响应内容断言"
else
  echo "  --- 后端错误日志 ---"
  grep -iE "Exception|Caused by" "$OUT/backend.log" | head -10
  check 1 "规划接口调用"
fi

step "GET /api/v1/prices/catalog"
code=$(curl -s --max-time 20 -o "$OUT/catalog.json" -w '%{http_code}' "http://127.0.0.1:$BACKEND_PORT/api/v1/prices/catalog")
count=$(python -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['count'])" "$WIN_OUT/catalog.json" 2>/dev/null || echo 0)
echo "  HTTP $code, $count 种食材"
check "$([ "$code" = "200" ] && [ "$count" -gt 0 ] && echo 0 || echo 1)" "食材价格目录可访问"

step "GET /v3/api-docs"
code=$(curl -s --max-time 20 -o "$OUT/openapi.json" -w '%{http_code}' "http://127.0.0.1:$BACKEND_PORT/v3/api-docs")
paths=$(python -c "import json,sys;print(len(json.load(open(sys.argv[1],encoding='utf-8'))['paths']))" "$WIN_OUT/openapi.json" 2>/dev/null || echo 0)
echo "  HTTP $code, $paths 个 path"
check "$([ "$code" = "200" ] && [ "$paths" -gt 0 ] && echo 0 || echo 1)" "OpenAPI 文档已生成"

step "参数校验（非法输入应返回 400）"
code=$(curl -s --max-time 20 -o /dev/null -w '%{http_code}' \
  -X POST "http://127.0.0.1:$BACKEND_PORT/api/v1/plans" \
  -H 'Content-Type: application/json' -d '{"days":99,"avoidSpicy":true,"budget":-5}')
check "$([ "$code" = "400" ] && echo 0 || echo 1)" "非法参数返回 400（实际 $code）"

printf '\n============================\n'
if [[ $fail -eq 0 ]]; then
  echo "  全部检查通过"
else
  echo "  存在失败项"
fi
printf '============================\n'
exit $fail

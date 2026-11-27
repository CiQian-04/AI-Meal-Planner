#!/usr/bin/env bash
# Drive the real UI in a browser against the running backend and capture screenshots.
#
# As with verify.sh, the mock server, the backend, the frontend and the browser run all have to
# share a single shell session: processes from a previous command are not reachable here.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOCK_PORT="${MOCK_PORT:-8095}"
BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
SHOT_DIR="${SHOT_DIR:-C:/tmp/shots}"

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Java/jdk-21}"
export PATH="$JAVA_HOME/bin:/c/Users/辞浅_/.workbuddy-ai/binaries/apache-maven-3.9.9/bin:$PATH"

cleanup() {
  [[ -n "${FRONTEND_PID:-}" ]] && kill "$FRONTEND_PID" 2>/dev/null
  [[ -n "${BACKEND_PID:-}" ]] && kill "$BACKEND_PID" 2>/dev/null
  [[ -n "${MOCK_PID:-}" ]] && kill "$MOCK_PID" 2>/dev/null
}
trap cleanup EXIT

echo "==> mock OpenAI server on :$MOCK_PORT"
python -u "$ROOT/tools/mock_openai_server.py" --port "$MOCK_PORT" > /c/tmp/mock.log 2>&1 &
MOCK_PID=$!
for _ in $(seq 1 40); do
  curl -fsS "http://127.0.0.1:$MOCK_PORT/v1/models" > /dev/null 2>&1 && break
  sleep 0.5
done

echo "==> backend on :$BACKEND_PORT"
java -Dfile.encoding=UTF-8 -jar "$ROOT/backend/target/meal-planner-backend-0.1.0.jar" \
  --server.port="$BACKEND_PORT" \
  --spring.ai.openai.base-url="http://127.0.0.1:$MOCK_PORT" \
  --spring.ai.openai.api-key=mock \
  --spring.ai.openai.chat.options.model=mock-model \
  > /c/tmp/backend.log 2>&1 &
BACKEND_PID=$!
for _ in $(seq 1 80); do
  curl -fsS "http://127.0.0.1:$BACKEND_PORT/actuator/health" > /dev/null 2>&1 && break
  sleep 0.5
done
curl -fsS "http://127.0.0.1:$BACKEND_PORT/actuator/health" > /dev/null 2>&1 || {
  echo "backend failed to start"; tail -20 /c/tmp/backend.log; exit 1
}
echo "    backend is up"

echo "==> frontend dev server on :$FRONTEND_PORT"
(cd "$ROOT/frontend" && npx vite --host 127.0.0.1 --port "$FRONTEND_PORT" > /c/tmp/frontend.log 2>&1) &
FRONTEND_PID=$!
for _ in $(seq 1 80); do
  curl -fsS "http://127.0.0.1:$FRONTEND_PORT/" > /dev/null 2>&1 && break
  sleep 0.5
done
curl -fsS "http://127.0.0.1:$FRONTEND_PORT/" > /dev/null 2>&1 || {
  echo "frontend failed to start"; tail -20 /c/tmp/frontend.log; exit 1
}
echo "    frontend is up"

echo "==> running browser verification"
cd "$ROOT/frontend"
UI_BASE_URL="http://127.0.0.1:$FRONTEND_PORT" UI_SHOT_DIR="$SHOT_DIR" \
  node scripts/ui-verify.mjs
status=$?

echo "==> screenshots in $SHOT_DIR"

# Optional: record the demo frames used for the README GIF.
if [[ "${RECORD:-0}" == "1" ]]; then
  echo "==> recording demo frames"
  cd "$ROOT/frontend"
  UI_BASE_URL="http://127.0.0.1:$FRONTEND_PORT" UI_FRAME_DIR="${FRAME_DIR:-C:/tmp/frames}" \
    node scripts/record-demo.mjs
fi

exit $status

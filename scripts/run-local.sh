#!/usr/bin/env bash
# Start the mock OpenAI server and the backend together.
#
# Both processes must live in the same sandbox session: a background process started in a
# previous command is no longer reachable once that command returns.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOCK_PORT="${MOCK_PORT:-8095}"
BACKEND_PORT="${BACKEND_PORT:-8080}"

# Git Bash needs POSIX-style paths; the Windows form is not understood by `java` here.
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Java/jdk-21}"
export PATH="$JAVA_HOME/bin:/c/Users/辞浅_/.workbuddy-ai/binaries/apache-maven-3.9.9/bin:$PATH"

cleanup() {
  [[ -n "${MOCK_PID:-}" ]] && kill "$MOCK_PID" 2>/dev/null
  [[ -n "${BACKEND_PID:-}" ]] && kill "$BACKEND_PID" 2>/dev/null
}
trap cleanup EXIT

echo "==> starting mock OpenAI server on :$MOCK_PORT"
python -u "$ROOT/tools/mock_openai_server.py" --port "$MOCK_PORT" > /c/tmp/mock.log 2>&1 &
MOCK_PID=$!

# Wait until the mock answers.
for _ in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:$MOCK_PORT/v1/models" > /dev/null 2>&1; then
    echo "    mock is up"
    break
  fi
  sleep 0.5
done

echo "==> starting backend on :$BACKEND_PORT"
java -Dfile.encoding=UTF-8 -jar "$ROOT/backend/target/meal-planner-backend-0.1.0.jar" \
  --server.port="$BACKEND_PORT" \
  --spring.ai.openai.base-url="http://127.0.0.1:$MOCK_PORT" \
  --spring.ai.openai.api-key=mock \
  --spring.ai.openai.chat.options.model=mock-model \
  > /c/tmp/backend.log 2>&1 &
BACKEND_PID=$!

echo "==> waiting for backend health"
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$BACKEND_PORT/actuator/health" > /dev/null 2>&1; then
    echo "    backend is up"
    break
  fi
  sleep 0.5
done

echo "==> READY. Press Ctrl+C to stop both."
echo "    API      http://127.0.0.1:$BACKEND_PORT/api/v1/plans"
echo "    Swagger  http://127.0.0.1:$BACKEND_PORT/swagger-ui.html"
wait

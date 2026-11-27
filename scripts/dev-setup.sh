#!/usr/bin/env bash
# Prepare the workspace after the dev container is created.
set -euo pipefail

echo "==> Java version"
java -version

echo "==> Maven version"
mvn -version

echo "==> Node version"
node --version
npm --version

echo "==> Caching backend dependencies"
mvn -f backend/pom.xml -B -q dependency:go-offline || true

echo "==> Installing frontend dependencies"
if [ -f frontend/package-lock.json ]; then
  (cd frontend && npm ci --no-audit --no-fund) || (cd frontend && npm install --no-audit --no-fund)
else
  (cd frontend && npm install --no-audit --no-fund)
fi

cat <<'EOF'

Dev container ready.

  Terminal 1  ->  mvn -f backend/pom.xml spring-boot:run
  Terminal 2  ->  cd frontend && npm run dev

  Frontend    ->  http://localhost:5173
  API docs    ->  http://localhost:8080/swagger-ui.html

  Without an LLM key, start the bundled mock server first:
    python tools/mock_openai_server.py --port 8090
  then export:
    export OPENAI_BASE_URL=http://localhost:8090 OPENAI_API_KEY=mock OPENAI_MODEL=mock-model

EOF

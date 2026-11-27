@echo off
chcp 65001 >nul
setlocal EnableExtensions

REM ============================================================
REM AI Meal Planner - Windows one-click launcher
REM
REM Double-click this file. It opens two windows:
REM   1) backend (with mock model when no API key is set)
REM   2) frontend dev server
REM Close both windows to stop everything.
REM
REM The script calls itself with an argument for each role, which avoids
REM the fragile nested-quote handling of `start cmd /k "complex command"`.
REM ============================================================

set "ROOT=%~dp0.."

if /I "%~1"=="mock"     goto :do_mock
if /I "%~1"=="backend"  goto :do_backend
if /I "%~1"=="frontend" goto :do_frontend

REM ---------------- main ----------------
echo ============================================================
echo   AI Meal Planner
echo ============================================================
echo.

set "JAR_FOUND=no"
for %%f in ("%ROOT%\backend\target\meal-planner-backend-*.jar") do set "JAR_FOUND=yes"
if "%JAR_FOUND%"=="no" (
  echo [ERROR] Backend jar not found. Build it first:
  echo   cd backend ^&^& mvn clean package -DskipTests
  echo.
  pause
  exit /b 1
)

echo Starting windows...
if defined OPENAI_API_KEY (
  if not "%OPENAI_API_KEY%"=="sk-not-configured" (
    echo   - backend (real model)
    start "MealPlanner - Backend" cmd /k ""%~f0" backend"
  ) else (
    echo   - mock model, backend, frontend
    start "MealPlanner - MockModel" cmd /k ""%~f0" mock"
    start "MealPlanner - Backend" cmd /k ""%~f0" backend"
  )
) else (
  echo   - mock model, backend, frontend
  start "MealPlanner - MockModel" cmd /k ""%~f0" mock"
  start "MealPlanner - Backend" cmd /k ""%~f0" backend"
)
start "MealPlanner - Frontend" cmd /k ""%~f0" frontend"

echo.
echo Wait ~15 seconds, then open:
echo   UI       http://127.0.0.1:5173
echo   Swagger  http://127.0.0.1:8080/swagger-ui.html
echo.
echo Close the new windows to stop the services.
echo.
pause
exit /b 0

REM ---------------- mock role ----------------
:do_mock
cd /d "%ROOT%" || exit /b 1
echo [MOCK] OpenAI-compatible mock server on http://127.0.0.1:8095
echo.
python -u "%ROOT%\tools\mock_openai_server.py" --port 8095
exit /b 0

REM ---------------- backend role ----------------
:do_backend
cd /d "%ROOT%" || exit /b 1

set "JAVA_EXE=java"
if exist "C:\Program Files\Java\jdk-21\bin\java.exe" set "JAVA_EXE=C:\Program Files\Java\jdk-21\bin\java.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

set "JAR="
for %%f in ("%ROOT%\backend\target\meal-planner-backend-*.jar") do set "JAR=%%~f"
if not defined JAR (
  echo [ERROR] Backend jar not found.
  pause
  exit /b 1
)

if defined OPENAI_API_KEY (
  if not "%OPENAI_API_KEY%"=="sk-not-configured" (
    echo [MODE] Real model - %OPENAI_BASE_URL% / %OPENAI_MODEL%
    echo.
    "%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%"
    exit /b 0
  )
)

echo [MODE] Mock model - no API key detected.
echo        To use DeepSeek, set OPENAI_API_KEY before launching.
echo        Waiting for the mock window to be ready...
ping -n 4 127.0.0.1 >nul
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%" --server.port=8080 --spring.ai.openai.base-url=http://127.0.0.1:8095 --spring.ai.openai.api-key=mock --spring.ai.openai.chat.options.model=mock-model
exit /b 0

REM ---------------- frontend role ----------------
:do_frontend
cd /d "%ROOT%\frontend" || exit /b 1
echo [FRONTEND] Vite dev server on http://127.0.0.1:5173
echo.
npx vite --host 127.0.0.1 --port 5173
exit /b 0

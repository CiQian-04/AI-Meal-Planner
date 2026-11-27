@echo off
chcp 65001 >nul
REM Start AI Meal Planner locally on Windows.
REM Usage: double-click this file, or run: scripts\start-local.bat
REM It opens two windows: one for mock+backend, one for the frontend.

cd /d "%~dp0.."

echo Starting AI Meal Planner...
echo.

start "Meal Planner - Backend + Mock" "C:\Program Files\Git\bin\bash.exe" -c "cd '%~dp0..'; bash scripts/run-local.sh; exec bash"
start "Meal Planner - Frontend" cmd /c "cd /d "%~dp0.." && cd frontend && npx vite --host 127.0.0.1 --port 5173 & pause"

echo Two windows are starting:
echo   Frontend UI    http://127.0.0.1:5173
echo   Swagger API    http://127.0.0.1:8080/swagger-ui.html
echo.
echo Wait ~10 seconds for both to boot, then open the UI URL in your browser.
echo Close the two windows to stop the services.
pause

@echo off
REM One-command start for Windows. Creates the venv, installs deps, seeds .env, runs.
setlocal
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo Python not found. Install Python 3.11+ from python.org and retry.
  exit /b 1
)

if not exist ".venv" (
  echo Creating .venv ...
  python -m venv .venv
)

call .venv\Scripts\activate.bat
python -m pip install --quiet --upgrade pip
python -m pip install --quiet -r requirements.txt

if not exist ".env" (
  copy .env.example .env >nul
  echo Created backend\.env — put your GEMINI_API_KEY in it, then run this script again.
)

REM 0.0.0.0 so a phone on the same Wi-Fi can reach it.
if "%SARA_HOST%"=="" set SARA_HOST=0.0.0.0
if "%SARA_PORT%"=="" set SARA_PORT=8000

python -m app.main
endlocal
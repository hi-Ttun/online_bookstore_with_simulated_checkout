@echo off
title Online Bookstore Launcher
echo ========================================================
echo   DANG KHOI DONG HE THONG ONLINE BOOKSTORE...
echo ========================================================
echo.

cd /d "%~dp0"

echo [1/2] Khoi dong Backend Mock Server (cong 8080)...
start "Bookstore Backend (8080)" cmd /k "node frontend/mock/server.mjs"

echo [2/2] Khoi dong Frontend React Web (cong 5173)...
start "Bookstore Frontend (5173)" cmd /k "cd frontend && npm run dev"

echo.
echo Dang mo trinh duyet tai http://localhost:5173 ...
timeout /t 3 /nobreak >nul
start http://localhost:5173

echo.
echo ========================================================
echo   HE THONG DA KHOI DONG THANH CONG!
echo   - Web Frontend : http://localhost:5173
echo   - Backend API  : http://localhost:8080
echo   (Dong 2 cua so den neu muon tat he thong)
echo ========================================================

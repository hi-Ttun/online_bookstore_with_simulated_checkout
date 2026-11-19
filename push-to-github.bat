@echo off
title Push to GitHub - hi-Ttun/online_bookstore_with_simulated_checkout
echo ========================================================
echo   DANG DAY TOAN BO MA NGUON LEN GITHUB (BRANCH MAIN)...
echo ========================================================
echo.

cd /d "%~dp0"

git push origin main

echo.
if %ERRORLEVEL% equ 0 (
    echo ========================================================
    echo   DA DAY LEN GITHUB THANH CONG!
    echo ========================================================
) else (
    echo ========================================================
    echo   CO LOI XAY RA KHI PUSH.
    echo   Vui long kiem tra xac thuc tai khoan hi-Ttun.
    echo ========================================================
)
echo.
pause

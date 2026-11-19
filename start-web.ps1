Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  DANG KHOI DONG HE THONG ONLINE BOOKSTORE..." -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

$root = $PSScriptRoot
Set-Location $root

Write-Host "`n[1/2] Khoi dong Backend Mock Server (cong 8080)..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root'; node frontend/mock/server.mjs"

Write-Host "[2/2] Khoi dong Frontend React Web (cong 5173)..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root/frontend'; npm run dev"

Start-Sleep -Seconds 3
Start-Process "http://localhost:5173"

Write-Host "`n========================================================" -ForegroundColor Green
Write-Host "  HE THONG DA KHOI DONG THANH CONG!" -ForegroundColor Green
Write-Host "  - Web Frontend : http://localhost:5173" -ForegroundColor Green
Write-Host "  - Backend API  : http://localhost:8080" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green

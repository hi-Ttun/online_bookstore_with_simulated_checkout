#!/bin/sh
# Sinh /config.js từ biến môi trường khi container khởi động (chạy trước nginx).
# Firebase API key là giá trị công khai của web app (không phải bí mật); không đặt mật khẩu hay khóa riêng ở đây.
set -eu

json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

cat > /usr/share/nginx/html/config.js <<EOF
window.__APP_CONFIG__ = {
  API_URL: "$(json_escape "${API_URL:-}")",
  AUTH_MODE: "$(json_escape "${AUTH_MODE:-firebase}")",
  FIREBASE_API_KEY: "$(json_escape "${FIREBASE_API_KEY:-}")",
  FIREBASE_AUTH_DOMAIN: "$(json_escape "${FIREBASE_AUTH_DOMAIN:-}")",
  FIREBASE_PROJECT_ID: "$(json_escape "${FIREBASE_PROJECT_ID:-}")"
};
EOF
echo "app-config: API_URL=${API_URL:-<same origin>} AUTH_MODE=${AUTH_MODE:-firebase}"

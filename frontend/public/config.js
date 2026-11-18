// Cấu hình mặc định cho môi trường local (npm run dev).
// Khi chạy bằng Docker / Cloud Run, file này được sinh lại từ biến môi trường (xem docker-entrypoint.sh).
window.__APP_CONFIG__ = {
  // Để trống = gọi cùng origin (Vite proxy sang http://localhost:8080).
  API_URL: "",
  // "dev": lấy token qua POST /dev/token (chỉ có ở Backend profile local)
  // "firebase": đăng nhập Firebase Authentication / Identity Platform
  AUTH_MODE: "dev",
  FIREBASE_API_KEY: "",
  FIREBASE_AUTH_DOMAIN: "",
  FIREBASE_PROJECT_ID: "",
};

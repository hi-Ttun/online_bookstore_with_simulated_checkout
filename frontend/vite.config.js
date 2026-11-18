import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Khi chạy local (npm run dev), Vite chuyển tiếp /api, /dev, /healthz sang Backend ở cổng 8080.
// Nhờ vậy trình duyệt gọi cùng origin, không vướng CORS (kể cả /dev/token vốn không có CORS).
const BACKEND = process.env.BACKEND_URL || "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": BACKEND,
      "/dev": BACKEND,
      "/healthz": BACKEND,
    },
  },
});

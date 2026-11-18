// Đọc cấu hình lúc chạy từ window.__APP_CONFIG__ (public/config.js).
const raw = window.__APP_CONFIG__ || {};

export const config = {
  apiUrl: (raw.API_URL || "").replace(/\/+$/, ""),
  authMode: raw.AUTH_MODE === "firebase" ? "firebase" : "dev",
  firebase: {
    apiKey: raw.FIREBASE_API_KEY || "",
    authDomain: raw.FIREBASE_AUTH_DOMAIN || "",
    projectId: raw.FIREBASE_PROJECT_ID || "",
  },
};

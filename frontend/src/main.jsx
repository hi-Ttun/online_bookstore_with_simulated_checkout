import React from "react";
import ReactDOM from "react-dom/client";
import { HashRouter } from "react-router-dom";
import App from "./App";
import { AuthProvider } from "./lib/auth";
import { CartProvider } from "./lib/cart";
import "./styles.css";

// HashRouter (đường dẫn dạng /#/cart): trang tĩnh chạy được trên Cloud Run (nginx) hoặc Cloud Storage
// mà không cần cấu hình chuyển hướng mọi đường dẫn về index.html.
ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <HashRouter>
      <AuthProvider>
        <CartProvider>
          <App />
        </CartProvider>
      </AuthProvider>
    </HashRouter>
  </React.StrictMode>,
);

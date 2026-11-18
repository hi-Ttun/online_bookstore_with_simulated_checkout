import { useEffect } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import Header from "./components/Header";
import { RequireAuth } from "./components/ui";
import { useAuth } from "./lib/auth";
import Catalogue from "./pages/Catalogue";
import Cart from "./pages/Cart";
import Checkout from "./pages/Checkout";
import Login from "./pages/Login";
import MyOrders from "./pages/MyOrders";
import OrderDetail from "./pages/OrderDetail";
import AdminLayout from "./pages/admin/AdminLayout";
import AdminOrders from "./pages/admin/AdminOrders";
import Fulfillment from "./pages/admin/Fulfillment";
import Inventory from "./pages/admin/Inventory";
import Movements from "./pages/admin/Movements";
import Operations from "./pages/admin/Operations";

export default function App() {
  const { sessionExpired, user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  // 401 từ API => chuyển tới trang đăng nhập (HANDOVER.md mục 4.1).
  useEffect(() => {
    if (sessionExpired && !user && location.pathname !== "/login") {
      navigate("/login", { state: { from: location.pathname, expired: true } });
    }
  }, [sessionExpired, user, location.pathname, navigate]);

  return (
    <>
      <Header />
      <main className="container">
        <Routes>
          <Route path="/" element={<Catalogue />} />
          <Route path="/cart" element={<Cart />} />
          <Route path="/login" element={<Login />} />
          <Route
            path="/checkout"
            element={
              <RequireAuth>
                <Checkout />
              </RequireAuth>
            }
          />
          <Route
            path="/orders"
            element={
              <RequireAuth>
                <MyOrders />
              </RequireAuth>
            }
          />
          <Route
            path="/orders/:id"
            element={
              <RequireAuth>
                <OrderDetail />
              </RequireAuth>
            }
          />
          <Route
            path="/admin"
            element={
              <RequireAuth operatorOnly>
                <AdminLayout />
              </RequireAuth>
            }
          >
            <Route index element={<Navigate to="ops" replace />} />
            <Route path="ops" element={<Operations />} />
            <Route path="orders" element={<AdminOrders />} />
            <Route path="fulfillment" element={<Fulfillment />} />
            <Route path="inventory" element={<Inventory />} />
            <Route path="movements" element={<Movements />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      <footer className="footer">
        Final Project 04 – Cloud Computing · Online Bookstore with Simulated Checkout · Nhóm 11
      </footer>
    </>
  );
}

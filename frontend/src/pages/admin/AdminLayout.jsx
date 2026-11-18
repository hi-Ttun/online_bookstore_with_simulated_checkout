// Trang Store Operator (HANDOVER.md mục 4.4). Menu chỉ hiện với operator, nhưng Backend vẫn tự chặn API (403).
import { NavLink, Outlet } from "react-router-dom";

const TABS = [
  { to: "ops", label: "Vận hành" },
  { to: "fulfillment", label: "Cần giao hàng" },
  { to: "orders", label: "Lịch sử đơn hàng" },
  { to: "inventory", label: "Tồn kho" },
  { to: "movements", label: "Biến động kho" },
];

export default function AdminLayout() {
  return (
    <div className="page">
      <h1>Quản trị cửa hàng</h1>
      <nav className="tabs">
        {TABS.map((t) => (
          <NavLink key={t.to} to={t.to} className={({ isActive }) => `tab ${isActive ? "is-active" : ""}`}>
            {t.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </div>
  );
}

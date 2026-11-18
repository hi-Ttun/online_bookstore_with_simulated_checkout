import { Link, NavLink, useNavigate } from "react-router-dom";
import { useAuth } from "../lib/auth";
import { useCart } from "../lib/cart";

export default function Header() {
  const { user, isOperator, logout, mode } = useAuth();
  const { count, clear } = useCart();
  const navigate = useNavigate();

  return (
    <header className="header">
      <div className="header-inner">
        <Link to="/" className="brand">
          <img src="/favicon.svg" alt="" width="28" height="28" />
          <span>Nhà sách trực tuyến</span>
        </Link>
        <nav className="nav">
          <NavLink to="/" end>
            Danh mục
          </NavLink>
          {user && <NavLink to="/orders">Đơn của tôi</NavLink>}
          {isOperator && <NavLink to="/admin">Quản trị</NavLink>}
          <NavLink to="/cart" className="cart-link">
            Giỏ hàng {count > 0 && <span className="cart-count">{count}</span>}
          </NavLink>
        </nav>
        <div className="user-box">
          {mode === "dev" && <span className="badge badge-warn" title="Đăng nhập bằng /dev/token">DEV</span>}
          {user ? (
            <>
              <span className="user-email" title={user.email}>
                {user.email}
                {isOperator && <span className="badge badge-done">Operator</span>}
              </span>
              <button
                className="btn btn-ghost btn-sm"
                onClick={async () => {
                  await logout();
                  clear(); // máy dùng chung: không để giỏ hàng lại cho người sau
                  navigate("/");
                }}
              >
                Đăng xuất
              </button>
            </>
          ) : (
            <Link to="/login" className="btn btn-sm">
              Đăng nhập
            </Link>
          )}
        </div>
      </div>
    </header>
  );
}

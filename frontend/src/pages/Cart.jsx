import { Link, useNavigate } from "react-router-dom";
import { BookCover, Empty } from "../components/ui";
import { useAuth } from "../lib/auth";
import { MAX_LINES, useCart } from "../lib/cart";
import { formatMoney } from "../lib/format";

export default function Cart() {
  const { items, totalMinor, count, setQuantity, remove, clear } = useCart();
  const { user } = useAuth();
  const navigate = useNavigate();

  if (items.length === 0) {
    return (
      <div className="page">
        <h1>Giỏ hàng</h1>
        <Empty>
          Giỏ hàng đang trống. <Link to="/">Xem danh mục sách →</Link>
        </Empty>
      </div>
    );
  }

  const overStock = items.some((i) => i.available != null && i.quantity > i.available);

  return (
    <div className="page">
      <div className="page-head">
        <h1>Giỏ hàng</h1>
        <button className="btn btn-ghost btn-sm" onClick={clear}>
          Xóa giỏ hàng
        </button>
      </div>

      <div className="layout-2">
        <div className="card">
          <table className="table">
            <thead>
              <tr>
                <th>Sách</th>
                <th className="num">Đơn giá</th>
                <th className="center">Số lượng</th>
                <th className="num">Thành tiền</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {items.map((i) => (
                <tr key={i.productId}>
                  <td>
                    <div className="line-book">
                      <BookCover product={i} size="sm" />
                      <div>
                        <div className="strong">{i.title}</div>
                        <div className="small muted">
                          {i.author} · {i.sku}
                        </div>
                        {i.available != null && i.quantity > i.available && (
                          <div className="small text-bad">Chỉ còn {i.available} cuốn</div>
                        )}
                      </div>
                    </div>
                  </td>
                  <td className="num">{formatMoney(i.priceMinor, i.currency)}</td>
                  <td className="center">
                    <div className="qty">
                      <button
                        className="btn btn-sm"
                        onClick={() => setQuantity(i.productId, i.quantity - 1)}
                        disabled={i.quantity <= 1}
                        aria-label="Giảm"
                      >
                        −
                      </button>
                      <input
                        className="input qty-input"
                        type="number"
                        min={1}
                        max={i.available ?? 100}
                        value={i.quantity}
                        onChange={(e) => setQuantity(i.productId, Number(e.target.value) || 1)}
                      />
                      <button
                        className="btn btn-sm"
                        onClick={() => setQuantity(i.productId, i.quantity + 1)}
                        disabled={i.available != null && i.quantity >= i.available}
                        aria-label="Tăng"
                      >
                        +
                      </button>
                    </div>
                  </td>
                  <td className="num strong">{formatMoney(i.priceMinor * i.quantity, i.currency)}</td>
                  <td className="center">
                    <button className="btn btn-ghost btn-sm" onClick={() => remove(i.productId)}>
                      Xóa
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="small muted">
            Tối đa {MAX_LINES} đầu sách mỗi đơn. Số lượng tồn được kiểm tra lại khi thanh toán.
          </p>
        </div>

        <aside className="card summary">
          <h3>Tóm tắt</h3>
          <div className="row-between">
            <span>Số cuốn</span>
            <span>{count}</span>
          </div>
          <div className="row-between total">
            <span>Tổng cộng</span>
            <span>{formatMoney(totalMinor)}</span>
          </div>
          <button
            className="btn btn-primary btn-block"
            disabled={overStock}
            onClick={() => navigate(user ? "/checkout" : "/login", { state: { from: "/checkout" } })}
          >
            {user ? "Tiến hành thanh toán" : "Đăng nhập để thanh toán"}
          </button>
          <Link to="/" className="btn btn-ghost btn-block">
            Tiếp tục mua sách
          </Link>
        </aside>
      </div>
    </div>
  );
}

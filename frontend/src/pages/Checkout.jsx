// Trang thanh toán: POST /api/checkout với header Idempotency-Key (HANDOVER.md mục 4.2).
import { useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ErrorBox } from "../components/ui";
import { Catalog, Orders } from "../lib/api";
import { useAuth } from "../lib/auth";
import { useCart } from "../lib/cart";
import { formatMoney, SCENARIOS } from "../lib/format";
import { keyFor, rememberLast, resetKey } from "../lib/idempotency";

export default function Checkout() {
  const { items, totalMinor, clear, syncAvailability } = useCart();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [scenario, setScenario] = useState("SUCCESS");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);
  const [hint, setHint] = useState(null);
  const [keyVersion, setKeyVersion] = useState(0);

  const body = useMemo(
    () => ({
      items: items.map((i) => ({ productId: i.productId, quantity: i.quantity })),
      paymentScenario: scenario,
    }),
    [items, scenario],
  );
  // Key giữ nguyên khi bấm lại / lỗi mạng; chỉ đổi khi giỏ hàng hoặc kịch bản thay đổi.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const idempotencyKey = useMemo(() => (items.length ? keyFor(body) : null), [body, keyVersion]);

  if (items.length === 0) {
    return (
      <div className="page">
        <h1>Thanh toán</h1>
        <div className="empty">
          Giỏ hàng trống. <Link to="/">Chọn sách →</Link>
        </div>
      </div>
    );
  }

  const submit = async () => {
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    setHint(null);
    try {
      const res = await Orders.checkout(idempotencyKey, body);
      const receipt = res.data;
      rememberLast(idempotencyKey, body, receipt.orderId);
      clear();
      navigate(`/orders/${receipt.orderId}`, {
        replace: true,
        state: { created: { httpStatus: res.status, replayed: res.replayed, correlationId: res.correlationId } },
      });
    } catch (e) {
      setError(e);
      if (e.code === "OUT_OF_STOCK") {
        const pid = e.details && e.details.productId;
        const book = items.find((i) => i.productId === Number(pid));
        setHint(
          `Sách "${book ? book.title : `#${pid}`}" không đủ hàng. Danh mục đã được tải lại — hãy giảm số lượng hoặc bỏ sách này.`,
        );
        try {
          syncAvailability(await Catalog.list());
        } catch {
          /* bỏ qua */
        }
      } else if (e.code === "IDEMPOTENCY_KEY_REUSED") {
        resetKey(body);
        setKeyVersion((v) => v + 1);
        setHint("Mã thanh toán đã dùng cho một đơn khác nên hệ thống đã tạo mã mới. Bấm “Đặt hàng” lần nữa.");
      } else if (e.status === 0 || e.status >= 500) {
        setHint(
          "Chưa xác định được đơn đã tạo hay chưa. Bấm “Đặt hàng” lần nữa: hệ thống gửi lại cùng Idempotency-Key nên không tạo đơn trùng.",
        );
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="page">
      <h1>Thanh toán</h1>
      <div className="layout-2">
        <div className="stack">
          <section className="card">
            <h3>1. Kịch bản thanh toán mô phỏng</h3>
            <p className="small muted">Chọn kết quả mà cổng thanh toán mô phỏng sẽ trả về cho đơn này.</p>
            <div className="scenarios">
              {SCENARIOS.map((s) => (
                <label key={s.value} className={`scenario ${scenario === s.value ? "is-active" : ""} sc-${s.value}`}>
                  <input
                    type="radio"
                    name="scenario"
                    value={s.value}
                    checked={scenario === s.value}
                    onChange={() => setScenario(s.value)}
                  />
                  <div>
                    <div className="strong">{s.label}</div>
                    <div className="small muted">{s.hint}</div>
                    <code className="small">{s.value}</code>
                  </div>
                </label>
              ))}
            </div>
          </section>

          <section className="card">
            <h3>2. Kiểm tra đơn hàng</h3>
            <table className="table compact">
              <tbody>
                {items.map((i) => (
                  <tr key={i.productId}>
                    <td>
                      {i.title} <span className="muted">× {i.quantity}</span>
                    </td>
                    <td className="num">{formatMoney(i.priceMinor * i.quantity, i.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </div>

        <aside className="card summary">
          <h3>Xác nhận</h3>
          <div className="row-between">
            <span>Khách hàng</span>
            <span className="small">{user && user.email}</span>
          </div>
          <div className="row-between total">
            <span>Tổng cộng</span>
            <span>{formatMoney(totalMinor)}</span>
          </div>
          <div className="key-box">
            <div className="small muted">Idempotency-Key của lượt thanh toán này</div>
            <code className="small break">{idempotencyKey}</code>
          </div>
          {hint && <div className="alert alert-warn small">{hint}</div>}
          <ErrorBox error={error} />
          <button className="btn btn-primary btn-block btn-lg" onClick={submit} disabled={submitting}>
            {submitting ? "Đang gửi..." : "Đặt hàng"}
          </button>
          <Link to="/cart" className="btn btn-ghost btn-block">
            ← Quay lại giỏ hàng
          </Link>
        </aside>
      </div>
    </div>
  );
}

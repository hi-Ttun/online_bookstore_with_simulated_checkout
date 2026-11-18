// Chi tiết đơn: hỏi lại GET /api/orders/{id} mỗi 1–2 giây cho tới khi có trạng thái cuối (HANDOVER.md mục 4.2).
import { useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import Receipt from "../components/Receipt";
import { ErrorBox, Spinner } from "../components/ui";
import { Orders } from "../lib/api";
import { FINAL_STATUSES } from "../lib/format";
import { lastCheckout } from "../lib/idempotency";
import { usePolling } from "../lib/usePolling";

const STATUS_NOTE = {
  PENDING_PAYMENT: "Đơn đã được tạo và sách đã được giữ cho bạn. Hệ thống đang gọi cổng thanh toán...",
  PAYMENT_UNKNOWN:
    "Cổng thanh toán chưa trả lời kịp. Đây KHÔNG phải lỗi: hệ thống đang đối soát và thường có kết quả sau 10–30 giây.",
  PAID: "Thanh toán thành công. Đơn đang chờ cửa hàng giao.",
  PAYMENT_FAILED: "Thanh toán thất bại. Sách đã được hoàn lại vào kho, bạn không bị trừ tiền.",
  FULFILLED: "Đơn hàng đã được giao.",
};

export default function OrderDetail() {
  const { id } = useParams();
  const location = useLocation();
  const created = location.state && location.state.created;
  const [status, setStatus] = useState(null);

  const interval = status && FINAL_STATUSES.includes(status) ? 0 : status === "PAYMENT_UNKNOWN" ? 3000 : 1500;
  const { data, error, loading, reload } = usePolling(
    async () => {
      const d = await Orders.get(id);
      setStatus(d.status);
      return d;
    },
    interval,
    [id],
  );

  return (
    <div className="page narrow-lg">
      <div className="page-head">
        <h1>Chi tiết đơn hàng</h1>
        <Link to="/orders" className="btn btn-ghost">
          ← Đơn của tôi
        </Link>
      </div>

      {created && (
        <div className="alert alert-ok">
          {created.replayed
            ? "Yêu cầu được gửi lại: hệ thống trả về đúng đơn đã tạo trước đó (không tạo đơn mới)."
            : "Đặt hàng thành công!"}{" "}
          <span className="small muted">
            HTTP {created.httpStatus}
            {created.correlationId && (
              <>
                {" "}
                · correlationId <code>{created.correlationId}</code>
              </>
            )}
          </span>
        </div>
      )}

      <ErrorBox error={error} onRetry={reload} />
      {loading && !data && <Spinner />}

      {data && (
        <>
          <div className={`alert ${data.status === "PAYMENT_FAILED" ? "alert-bad" : "alert-info"}`}>
            {!FINAL_STATUSES.includes(data.status) && <span className="spinner" aria-hidden />}{" "}
            {STATUS_NOTE[data.status]}
          </div>
          <div className="card">
            <Receipt order={data} />
          </div>
          <IdempotencyDemo orderId={data.orderId} />
        </>
      )}
    </div>
  );
}

/**
 * Công cụ minh họa Test 2 ngay trên giao diện (phục vụ buổi demo):
 *  - Gửi lại cùng key + cùng nội dung  => 200, Idempotent-Replayed: true, cùng orderId.
 *  - Gửi lại cùng key + nội dung khác   => 409 IDEMPOTENCY_KEY_REUSED.
 */
function IdempotencyDemo({ orderId }) {
  const last = lastCheckout();
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  if (!last || last.orderId !== orderId) return null;

  const send = async (changeBody) => {
    setBusy(true);
    const body = changeBody
      ? {
          ...last.body,
          items: last.body.items.map((it, i) =>
            i === 0 ? { ...it, quantity: it.quantity >= 100 ? it.quantity - 1 : it.quantity + 1 } : it,
          ),
        }
      : last.body;
    try {
      const res = await Orders.checkout(last.key, body);
      setResult({
        ok: true,
        text: `HTTP ${res.status} · Idempotent-Replayed: ${res.replayed} · orderId ${res.data.orderId}${
          res.data.orderId === orderId ? " (trùng với đơn hiện tại ✓)" : " (KHÁC đơn hiện tại ✗)"
        }`,
        correlationId: res.correlationId,
      });
    } catch (e) {
      setResult({ ok: false, text: `HTTP ${e.status} · ${e.code}: ${e.message}`, correlationId: e.correlationId });
    } finally {
      setBusy(false);
    }
  };

  return (
    <details className="card demo">
      <summary>Công cụ demo Test 2 – Idempotency</summary>
      <p className="small">
        Idempotency-Key đã dùng: <code className="break">{last.key}</code>
      </p>
      <div className="row gap">
        <button className="btn btn-sm" disabled={busy} onClick={() => send(false)}>
          Gửi lại cùng key, cùng nội dung
        </button>
        <button className="btn btn-sm" disabled={busy} onClick={() => send(true)}>
          Gửi lại cùng key, đổi số lượng
        </button>
      </div>
      {result && (
        <div className={`alert small ${result.ok ? "alert-ok" : "alert-warn"}`}>
          {result.text}
          {result.correlationId && (
            <div className="muted">
              correlationId: <code>{result.correlationId}</code>
            </div>
          )}
        </div>
      )}
      <p className="small muted">
        Kỳ vọng: lần 1 trả 200 cùng orderId; lần 2 trả 409 IDEMPOTENCY_KEY_REUSED (không tạo đơn mới).
      </p>
    </details>
  );
}

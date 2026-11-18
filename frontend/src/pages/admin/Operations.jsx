// Thanh vận hành: GET /api/admin/ops/summary + nút "Đối soát ngay" (POST /api/admin/reconcile).
// Dùng trong diễn tập sự cố: khi tắt outbox relay, outboxPending và outboxOldestAgeSeconds tăng dần.
import { useState } from "react";
import { ErrorBox, OrderStatusBadge, Spinner } from "../../components/ui";
import { Admin } from "../../lib/api";
import { ORDER_STATUS } from "../../lib/format";
import { usePolling } from "../../lib/usePolling";

const OUTBOX_ALERT_SECONDS = 60; // trùng ngưỡng cảnh báo đề xuất ở HANDOVER.md mục 6.2

export default function Operations() {
  const [auto, setAuto] = useState(true);
  const { data, error, loading, reload } = usePolling(() => Admin.ops(), auto ? 3000 : 0);
  const [rec, setRec] = useState(null);
  const [busy, setBusy] = useState(false);

  const reconcile = async () => {
    setBusy(true);
    setRec(null);
    try {
      const r = await Admin.reconcile();
      setRec({ ok: true, text: `Đối soát xong: ${r.resolved} giao dịch được xác định kết quả.` });
      await reload();
    } catch (e) {
      setRec({ ok: false, error: e });
    } finally {
      setBusy(false);
    }
  };

  const ageWarn = data && data.outboxOldestAgeSeconds > OUTBOX_ALERT_SECONDS;

  return (
    <div className="stack">
      <div className="row-between">
        <label className="check">
          <input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} /> Tự làm mới (3 giây)
        </label>
        <button className="btn btn-ghost btn-sm" onClick={reload}>
          ↻ Làm mới
        </button>
      </div>
      <ErrorBox error={error} onRetry={reload} />
      {loading && !data && <Spinner />}
      {data && (
        <>
          <div className="stats">
            <div className={`stat ${data.outboxPending > 0 ? "stat-warn" : ""}`}>
              <div className="stat-label">Sự kiện outbox chờ gửi</div>
              <div className="stat-value">{data.outboxPending}</div>
              <div className="small muted">outboxPending</div>
            </div>
            <div className={`stat ${ageWarn ? "stat-bad" : ""}`}>
              <div className="stat-label">Tuổi sự kiện cũ nhất</div>
              <div className="stat-value">{data.outboxOldestAgeSeconds}s</div>
              <div className="small muted">
                outboxOldestAgeSeconds {ageWarn && `· vượt ngưỡng cảnh báo ${OUTBOX_ALERT_SECONDS}s`}
              </div>
            </div>
            <div className={`stat ${data.paymentsUnknown > 0 ? "stat-warn" : ""}`}>
              <div className="stat-label">Thanh toán chờ đối soát</div>
              <div className="stat-value">{data.paymentsUnknown}</div>
              <div className="small muted">paymentsUnknown</div>
            </div>
          </div>

          {ageWarn && (
            <div className="alert alert-bad">
              Outbox đang tồn đọng quá {OUTBOX_ALERT_SECONDS} giây. Kiểm tra service <code>bookstore-relay</code> (đang
              tắt hoặc lỗi quyền Pub/Sub), xem cột <code>last_error</code> trong bảng <code>outbox_event</code>, rồi
              khởi động lại relay.
            </div>
          )}

          <div className="layout-2">
            <section className="card">
              <h3>Số đơn theo trạng thái</h3>
              <table className="table compact">
                <tbody>
                  {Object.keys(ORDER_STATUS).map((s) => (
                    <tr key={s}>
                      <td>
                        <OrderStatusBadge status={s} />
                      </td>
                      <td className="num strong">{(data.ordersByStatus && data.ordersByStatus[s]) || 0}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
            <section className="card">
              <h3>Đối soát thanh toán</h3>
              <p className="small muted">
                Thanh toán bị Timeout được đánh dấu “chưa rõ” và hệ thống tự đối soát định kỳ. Bấm nút để đối soát
                ngay các giao dịch đã chờ đủ lâu.
              </p>
              <button className="btn btn-primary" disabled={busy} onClick={reconcile}>
                {busy ? "Đang đối soát..." : "Đối soát ngay"}
              </button>
              {rec && rec.ok && <div className="alert alert-ok small">{rec.text}</div>}
              {rec && !rec.ok && <ErrorBox error={rec.error} />}
            </section>
          </div>
        </>
      )}
    </div>
  );
}

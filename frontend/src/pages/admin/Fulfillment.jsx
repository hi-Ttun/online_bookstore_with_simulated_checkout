// Fulfillment dashboard: đơn đã thanh toán (status=PAID) chờ giao; nút "Đã giao" → POST /api/admin/orders/{id}/fulfill.
import { useState } from "react";
import { Empty, ErrorBox, Spinner } from "../../components/ui";
import { Admin } from "../../lib/api";
import { formatMoney, formatTime, shortId } from "../../lib/format";
import { usePolling } from "../../lib/usePolling";

export default function Fulfillment() {
  const { data, error, loading, reload, setData } = usePolling(() => Admin.orders("PAID"), 5000);
  const [busyId, setBusyId] = useState(null);
  const [msg, setMsg] = useState(null);
  const [opError, setOpError] = useState(null);

  const fulfill = async (o) => {
    setBusyId(o.orderId);
    setMsg(null);
    setOpError(null);
    try {
      await Admin.fulfill(o.orderId);
      setData((list) => (list || []).filter((x) => x.orderId !== o.orderId));
      setMsg(`Đã đánh dấu giao đơn ${shortId(o.orderId)}.`);
    } catch (e) {
      // 409 INVALID_STATE: đơn không còn ở trạng thái PAID (details.status)
      setOpError(e);
      reload();
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="stack">
      <div className="row-between">
        <p className="muted">Đơn đã thanh toán thành công, đang chờ giao. Danh sách tự làm mới 5 giây/lần.</p>
        <button className="btn btn-ghost btn-sm" onClick={reload}>
          ↻ Làm mới
        </button>
      </div>
      {msg && <div className="alert alert-ok">{msg}</div>}
      <ErrorBox error={opError} />
      <ErrorBox error={error} onRetry={reload} />
      {loading && !data && <Spinner />}
      {data && data.length === 0 && <Empty>Không có đơn nào cần giao. 🎉</Empty>}
      {data && data.length > 0 && (
        <div className="card scroll-x">
          <table className="table">
            <thead>
              <tr>
                <th>Mã đơn</th>
                <th>Khách hàng</th>
                <th>Đặt lúc</th>
                <th className="num">Số cuốn</th>
                <th className="num">Tổng tiền</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {data.map((o) => (
                <tr key={o.orderId}>
                  <td>
                    <code title={o.orderId}>{shortId(o.orderId)}</code>
                  </td>
                  <td className="small">{o.customerId}</td>
                  <td className="small">{formatTime(o.createdAt)}</td>
                  <td className="num">{o.itemCount}</td>
                  <td className="num strong">{formatMoney(o.totalMinor, o.currency)}</td>
                  <td className="num">
                    <button className="btn btn-primary btn-sm" disabled={busyId === o.orderId} onClick={() => fulfill(o)}>
                      {busyId === o.orderId ? "..." : "Đã giao"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

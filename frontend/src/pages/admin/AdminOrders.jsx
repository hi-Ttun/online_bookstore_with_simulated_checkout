// Lịch sử đơn hàng: GET /api/admin/orders?status= → chi tiết GET /api/admin/orders/{id}.
import { useState } from "react";
import { Link } from "react-router-dom";
import Receipt from "../../components/Receipt";
import { Empty, ErrorBox, OrderStatusBadge, Spinner } from "../../components/ui";
import { Admin } from "../../lib/api";
import { formatMoney, formatTime, ORDER_STATUS, shortId } from "../../lib/format";
import { usePolling } from "../../lib/usePolling";

export default function AdminOrders() {
  const [status, setStatus] = useState("");
  const { data, error, loading, reload } = usePolling(() => Admin.orders(status), 5000, [status]);
  const [selected, setSelected] = useState(null);

  return (
    <div className="stack">
      <div className="row-between">
        <div className="toolbar">
          <select className="input" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Tất cả trạng thái</option>
            {Object.entries(ORDER_STATUS).map(([k, v]) => (
              <option key={k} value={k}>
                {v.label}
              </option>
            ))}
          </select>
          <span className="small muted">{data ? `${data.length} đơn (tối đa 200 đơn mới nhất)` : ""}</span>
        </div>
        <button className="btn btn-ghost btn-sm" onClick={reload}>
          ↻ Làm mới
        </button>
      </div>
      <ErrorBox error={error} onRetry={reload} />
      {loading && !data && <Spinner />}
      {data && data.length === 0 && <Empty>Không có đơn nào.</Empty>}

      <div className={selected ? "layout-2 wide-left" : ""}>
        {data && data.length > 0 && (
          <div className="card scroll-x">
            <table className="table hover">
              <thead>
                <tr>
                  <th>Mã đơn</th>
                  <th>Khách hàng</th>
                  <th>Thời gian</th>
                  <th>Kịch bản</th>
                  <th>Trạng thái</th>
                  <th className="num">Số cuốn</th>
                  <th className="num">Tổng tiền</th>
                  <th>Giao lúc</th>
                </tr>
              </thead>
              <tbody>
                {data.map((o) => (
                  <tr
                    key={o.orderId}
                    className={`clickable ${selected === o.orderId ? "is-selected" : ""}`}
                    onClick={() => setSelected(o.orderId)}
                  >
                    <td>
                      <code title={o.orderId}>{shortId(o.orderId)}</code>
                    </td>
                    <td className="small">{o.customerId}</td>
                    <td className="small">{formatTime(o.createdAt)}</td>
                    <td>
                      <code className="small">{o.paymentScenario}</code>
                    </td>
                    <td>
                      <OrderStatusBadge status={o.status} />
                    </td>
                    <td className="num">{o.itemCount}</td>
                    <td className="num">{formatMoney(o.totalMinor, o.currency)}</td>
                    <td className="small">{o.fulfilledAt ? formatTime(o.fulfilledAt) : "–"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {selected && <OrderPanel id={selected} onClose={() => setSelected(null)} />}
      </div>
    </div>
  );
}

function OrderPanel({ id, onClose }) {
  const { data, error, loading, reload } = usePolling(() => Admin.order(id), 3000, [id]);
  return (
    <aside className="card">
      <div className="row-between">
        <h3>Chi tiết đơn</h3>
        <button className="btn btn-ghost btn-sm" onClick={onClose}>
          Đóng ✕
        </button>
      </div>
      <ErrorBox error={error} onRetry={reload} />
      {loading && !data && <Spinner />}
      {data && <Receipt order={data} />}
      {data && (
        <Link className="small" to={`/admin/movements?orderId=${data.orderId}`}>
          Xem biến động kho của đơn này →
        </Link>
      )}
    </aside>
  );
}

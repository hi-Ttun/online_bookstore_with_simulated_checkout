import { Link, useNavigate } from "react-router-dom";
import { Empty, ErrorBox, OrderStatusBadge, Spinner } from "../components/ui";
import { Orders } from "../lib/api";
import { formatMoney, formatTime, shortId } from "../lib/format";
import { usePolling } from "../lib/usePolling";

export default function MyOrders() {
  // Làm mới 5 giây/lần để thấy đơn chuyển trạng thái.
  const { data, error, loading, reload } = usePolling(() => Orders.mine(), 5000);
  const navigate = useNavigate();

  return (
    <div className="page">
      <div className="page-head">
        <h1>Đơn hàng của tôi</h1>
        <button className="btn btn-ghost" onClick={reload}>
          ↻ Làm mới
        </button>
      </div>
      <ErrorBox error={error} onRetry={reload} />
      {loading && !data && <Spinner />}
      {data && data.length === 0 && (
        <Empty>
          Bạn chưa có đơn hàng nào. <Link to="/">Mua sách →</Link>
        </Empty>
      )}
      {data && data.length > 0 && (
        <div className="card">
          <table className="table hover">
            <thead>
              <tr>
                <th>Mã đơn</th>
                <th>Thời gian</th>
                <th>Trạng thái</th>
                <th className="num">Tổng tiền</th>
              </tr>
            </thead>
            <tbody>
              {data.map((o) => (
                <tr key={o.orderId} onClick={() => navigate(`/orders/${o.orderId}`)} className="clickable">
                  <td>
                    <code title={o.orderId}>{shortId(o.orderId)}</code>
                  </td>
                  <td>{formatTime(o.createdAt)}</td>
                  <td>
                    <OrderStatusBadge status={o.status} />
                  </td>
                  <td className="num strong">{formatMoney(o.totalMinor, o.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

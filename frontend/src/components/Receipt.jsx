// Biên nhận đơn hàng (OrderReceipt) dùng chung cho khách hàng và Store Operator.
import { formatMoney, formatTime, SCENARIOS } from "../lib/format";
import { OrderStatusBadge, PaymentStatusBadge } from "./ui";

export default function Receipt({ order }) {
  const scenario = SCENARIOS.find((s) => s.value === order.paymentScenario);
  return (
    <div className="receipt">
      <div className="receipt-head">
        <div>
          <div className="small muted">Mã đơn</div>
          <code className="break">{order.orderId}</code>
        </div>
        <OrderStatusBadge status={order.status} />
      </div>
      <div className="receipt-meta">
        <div>
          <span className="muted">Thời gian đặt: </span>
          {formatTime(order.createdAt)}
        </div>
        <div>
          <span className="muted">Kịch bản thanh toán: </span>
          {scenario ? scenario.label : order.paymentScenario}
        </div>
      </div>

      <table className="table compact">
        <thead>
          <tr>
            <th>Sách</th>
            <th className="center">SL</th>
            <th className="num">Đơn giá</th>
            <th className="num">Thành tiền</th>
          </tr>
        </thead>
        <tbody>
          {order.lines.map((l) => (
            <tr key={l.productId}>
              <td>{l.title}</td>
              <td className="center">{l.quantity}</td>
              <td className="num">{formatMoney(l.unitPriceMinor, order.currency)}</td>
              <td className="num">{formatMoney(l.lineTotalMinor, order.currency)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td colSpan={3} className="num strong">
              Tổng cộng
            </td>
            <td className="num strong">{formatMoney(order.totalMinor, order.currency)}</td>
          </tr>
        </tfoot>
      </table>

      <h4>Lịch sử thanh toán</h4>
      {order.paymentAttempts.length === 0 ? (
        <p className="small muted">Chưa gọi cổng thanh toán (sự kiện đơn hàng đang được xử lý).</p>
      ) : (
        <table className="table compact">
          <thead>
            <tr>
              <th>#</th>
              <th>Trạng thái</th>
              <th>Bắt đầu</th>
              <th>Có kết quả</th>
            </tr>
          </thead>
          <tbody>
            {order.paymentAttempts.map((p, i) => (
              <tr key={i}>
                <td>{i + 1}</td>
                <td>
                  <PaymentStatusBadge status={p.status} />
                </td>
                <td>{formatTime(p.createdAt)}</td>
                <td>{formatTime(p.resolvedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

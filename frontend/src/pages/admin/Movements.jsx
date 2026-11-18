// Biến động kho: GET /api/admin/inventory/movements?productId=
// Dùng để kiểm chứng Test 3: đơn thanh toán thất bại có đúng 1 dòng RELEASE.
import { useMemo } from "react";
import { useSearchParams } from "react-router-dom";
import { Empty, ErrorBox, MovementBadge, Spinner } from "../../components/ui";
import { Admin } from "../../lib/api";
import { formatTime, MOVEMENT_KIND, shortId } from "../../lib/format";
import { usePolling } from "../../lib/usePolling";

export default function Movements() {
  const [params, setParams] = useSearchParams();
  const productId = params.get("productId") || "";
  const orderId = params.get("orderId") || "";
  const kind = params.get("kind") || "";

  const inv = usePolling(() => Admin.inventory(), 0);
  const { data, error, loading, reload } = usePolling(() => Admin.movements(productId), 5000, [productId]);

  const set = (k, v) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v);
    else next.delete(k);
    setParams(next, { replace: true });
  };

  const rows = useMemo(
    () => (data || []).filter((m) => (!orderId || m.orderId === orderId) && (!kind || m.kind === kind)),
    [data, orderId, kind],
  );

  const counts = useMemo(() => {
    const c = {};
    rows.forEach((m) => (c[m.kind] = (c[m.kind] || 0) + 1));
    return c;
  }, [rows]);

  return (
    <div className="stack">
      <div className="row-between">
        <div className="toolbar">
          <select className="input" value={productId} onChange={(e) => set("productId", e.target.value)}>
            <option value="">Tất cả sách</option>
            {(inv.data || []).map((p) => (
              <option key={p.productId} value={p.productId}>
                {p.sku} – {p.title}
              </option>
            ))}
          </select>
          <select className="input" value={kind} onChange={(e) => set("kind", e.target.value)}>
            <option value="">Mọi loại</option>
            {Object.entries(MOVEMENT_KIND).map(([k, v]) => (
              <option key={k} value={k}>
                {k} – {v.label}
              </option>
            ))}
          </select>
          {orderId && (
            <button className="chip" onClick={() => set("orderId", "")}>
              Đơn {shortId(orderId)} ✕
            </button>
          )}
        </div>
        <button className="btn btn-ghost btn-sm" onClick={reload}>
          ↻ Làm mới
        </button>
      </div>

      <div className="chips">
        {Object.keys(MOVEMENT_KIND).map((k) => (
          <span key={k} className="small">
            <MovementBadge kind={k} /> {counts[k] || 0}
          </span>
        ))}
        <span className="small muted">(200 biến động mới nhất)</span>
      </div>

      <ErrorBox error={error} onRetry={reload} />
      {loading && !data && <Spinner />}
      {data && rows.length === 0 && <Empty>Không có biến động phù hợp.</Empty>}
      {rows.length > 0 && (
        <div className="card scroll-x">
          <table className="table">
            <thead>
              <tr>
                <th>#</th>
                <th>Thời gian</th>
                <th>SKU</th>
                <th>Loại</th>
                <th className="num">Số lượng</th>
                <th>Đơn hàng</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <tr key={m.id}>
                  <td className="small muted">{m.id}</td>
                  <td className="small">{formatTime(m.createdAt)}</td>
                  <td>
                    <code>{m.sku}</code>
                  </td>
                  <td>
                    <MovementBadge kind={m.kind} />
                  </td>
                  <td className="num strong">{m.quantity}</td>
                  <td>
                    {m.orderId ? (
                      <button className="link" onClick={() => set("orderId", m.orderId)} title={m.orderId}>
                        <code>{shortId(m.orderId)}</code>
                      </button>
                    ) : (
                      <span className="muted small">(dữ liệu mẫu)</span>
                    )}
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

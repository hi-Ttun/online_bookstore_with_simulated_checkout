// Tồn kho: GET /api/admin/inventory, làm mới 3–5 giây/lần (HANDOVER.md mục 4.4).
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { ErrorBox, Spinner } from "../../components/ui";
import { Admin } from "../../lib/api";
import { formatTime } from "../../lib/format";
import { usePolling } from "../../lib/usePolling";

export default function Inventory() {
  const [auto, setAuto] = useState(true);
  const { data, error, loading, reload } = usePolling(() => Admin.inventory(), auto ? 4000 : 0);
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState("all");

  const rows = useMemo(() => {
    if (!data) return [];
    const term = q.trim().toLowerCase();
    return data.filter((r) => {
      if (term && !r.title.toLowerCase().includes(term) && !r.sku.toLowerCase().includes(term)) return false;
      if (filter === "reserved") return r.reserved > 0;
      if (filter === "out") return r.available === 0;
      if (filter === "low") return r.available > 0 && r.available <= 5;
      return true;
    });
  }, [data, q, filter]);

  const totals = useMemo(
    () =>
      (data || []).reduce(
        (t, r) => ({ onHand: t.onHand + r.onHand, reserved: t.reserved + r.reserved, available: t.available + r.available }),
        { onHand: 0, reserved: 0, available: 0 },
      ),
    [data],
  );

  return (
    <div className="stack">
      <div className="row-between">
        <div className="toolbar">
          <input
            className="input"
            type="search"
            placeholder="Tìm sách / SKU..."
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          <select className="input" value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="all">Tất cả</option>
            <option value="reserved">Đang giữ hàng</option>
            <option value="low">Sắp hết (≤ 5)</option>
            <option value="out">Hết hàng</option>
          </select>
          <label className="check">
            <input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} /> Tự làm mới (4 giây)
          </label>
        </div>
        <button className="btn btn-ghost btn-sm" onClick={reload}>
          ↻ Làm mới
        </button>
      </div>

      {data && (
        <div className="stats">
          <div className="stat">
            <div className="stat-label">Thực có (onHand)</div>
            <div className="stat-value">{totals.onHand}</div>
          </div>
          <div className="stat">
            <div className="stat-label">Đang giữ (reserved)</div>
            <div className="stat-value">{totals.reserved}</div>
          </div>
          <div className="stat">
            <div className="stat-label">Còn bán được (available)</div>
            <div className="stat-value">{totals.available}</div>
          </div>
        </div>
      )}

      <ErrorBox error={error} onRetry={reload} />
      {loading && !data && <Spinner />}
      {data && (
        <div className="card scroll-x">
          <table className="table">
            <thead>
              <tr>
                <th>SKU</th>
                <th>Sách</th>
                <th className="num">Thực có</th>
                <th className="num">Đang giữ</th>
                <th className="num">Còn bán</th>
                <th>Cập nhật</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.productId} className={r.available === 0 ? "row-out" : ""}>
                  <td>
                    <code>{r.sku}</code>
                  </td>
                  <td>{r.title}</td>
                  <td className="num">{r.onHand}</td>
                  <td className={`num ${r.reserved > 0 ? "text-warn strong" : ""}`}>{r.reserved}</td>
                  <td className={`num strong ${r.available === 0 ? "text-bad" : ""}`}>{r.available}</td>
                  <td className="small">{formatTime(r.updatedAt)}</td>
                  <td>
                    <Link className="small" to={`/admin/movements?productId=${r.productId}`}>
                      Biến động →
                    </Link>
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

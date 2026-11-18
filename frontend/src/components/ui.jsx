import { useState } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../lib/auth";
import { MOVEMENT_KIND, ORDER_STATUS, PAYMENT_STATUS } from "../lib/format";

export function Badge({ tone = "info", children }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function OrderStatusBadge({ status }) {
  const s = ORDER_STATUS[status] || { label: status, tone: "info" };
  return <Badge tone={s.tone}>{s.label}</Badge>;
}

export function PaymentStatusBadge({ status }) {
  const s = PAYMENT_STATUS[status] || { label: status, tone: "info" };
  return <Badge tone={s.tone}>{s.label}</Badge>;
}

export function MovementBadge({ kind }) {
  const s = MOVEMENT_KIND[kind] || { label: kind, tone: "info" };
  return <Badge tone={s.tone}>{s.label}</Badge>;
}

/** Hiển thị lỗi API kèm mã lỗi và correlationId để tra log (HANDOVER.md mục 4.3). */
export function ErrorBox({ error, onRetry }) {
  if (!error) return null;
  return (
    <div className="alert alert-bad" role="alert">
      <div>
        <strong>{error.message}</strong>
        {error.code && <span className="muted"> ({error.code})</span>}
      </div>
      {error.details && error.details.errors && (
        <ul className="error-list">
          {[].concat(error.details.errors).map((e, i) => (
            <li key={i}>{typeof e === "string" ? e : JSON.stringify(e)}</li>
          ))}
        </ul>
      )}
      {error.correlationId && (
        <div className="small muted">
          Mã tra cứu (correlationId): <code>{error.correlationId}</code>
        </div>
      )}
      {onRetry && (
        <button className="btn btn-sm" onClick={onRetry}>
          Thử lại
        </button>
      )}
    </div>
  );
}

export function Spinner({ label = "Đang tải..." }) {
  return (
    <div className="spinner-row">
      <span className="spinner" aria-hidden /> {label}
    </div>
  );
}

/** Ảnh bìa: dùng imageUrl nếu có, nếu không thì vẽ bìa mặc định (imageUrl hiện để trống). */
const PALETTE = ["#1f4e5f", "#7a3b2e", "#3d5a40", "#5b3f6b", "#8a6a2f", "#2f4a7a", "#6b2f3f", "#35605a"];

export function BookCover({ product, size = "md" }) {
  const [broken, setBroken] = useState(false);
  if (product.imageUrl && !broken) {
    return (
      <img
        className={`cover cover-${size}`}
        src={product.imageUrl}
        alt={product.title}
        onError={() => setBroken(true)}
        loading="lazy"
      />
    );
  }
  const id = product.id ?? product.productId ?? 0;
  const bg = PALETTE[id % PALETTE.length];
  return (
    <div className={`cover cover-${size} cover-generated`} style={{ background: bg }} aria-label={product.title}>
      <div className="cover-title">{product.title}</div>
      {product.author && <div className="cover-author">{product.author}</div>}
    </div>
  );
}

/** Chặn trang cần đăng nhập; operatorOnly để ẩn trang quản trị (Backend vẫn tự kiểm tra quyền). */
export function RequireAuth({ children, operatorOnly = false }) {
  const { user, ready, isOperator } = useAuth();
  const location = useLocation();
  if (!ready) return <Spinner />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (operatorOnly && !isOperator) {
    return (
      <div className="page">
        <div className="alert alert-warn">
          Trang này chỉ dành cho Store Operator. Tài khoản <b>{user.email}</b> không có quyền truy cập (403).
        </div>
      </div>
    );
  }
  return children;
}

export function Empty({ children }) {
  return <div className="empty">{children}</div>;
}

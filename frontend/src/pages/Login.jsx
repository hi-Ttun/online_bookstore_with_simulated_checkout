import { useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { ErrorBox } from "../components/ui";
import { firebaseMessage, useAuth } from "../lib/auth";

const DEMO_USERS = [
  { user: "alice", label: "Khách hàng alice" },
  { user: "bob", label: "Khách hàng bob" },
  { user: "operator", label: "Store Operator" },
];

export default function Login() {
  const { user, loginDev, loginFirebase, mode } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const from = (location.state && location.state.from) || "/";
  const expired = location.state && location.state.expired;

  const [username, setUsername] = useState("alice");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [register, setRegister] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  if (user) return <Navigate to={from === "/login" ? "/" : from} replace />;

  const run = async (fn) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      navigate(from === "/login" ? "/" : from, { replace: true });
    } catch (e) {
      setError(e.code && String(e.code).startsWith("auth/") ? { message: firebaseMessage(e) } : e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page narrow">
      <div className="card">
        <h1>{register ? "Đăng ký" : "Đăng nhập"}</h1>
        {expired && <div className="alert alert-warn">Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.</div>}

        {mode === "firebase" ? (
          <form
            className="stack"
            onSubmit={(e) => {
              e.preventDefault();
              run(() => loginFirebase(email, password, register));
            }}
          >
            <label className="field">
              Email
              <input
                className="input"
                type="email"
                required
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </label>
            <label className="field">
              Mật khẩu
              <input
                className="input"
                type="password"
                required
                minLength={6}
                autoComplete={register ? "new-password" : "current-password"}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
            <ErrorBox error={error} />
            <button className="btn btn-primary btn-block" disabled={busy}>
              {busy ? "Đang xử lý..." : register ? "Tạo tài khoản" : "Đăng nhập"}
            </button>
            <button type="button" className="btn btn-ghost btn-block" onClick={() => setRegister(!register)}>
              {register ? "Đã có tài khoản? Đăng nhập" : "Chưa có tài khoản? Đăng ký khách hàng"}
            </button>
            <p className="small muted">
              Đăng nhập qua Google Identity Platform. Tài khoản Store Operator do nhóm cấp sẵn (email đã xác minh).
            </p>
          </form>
        ) : (
          <form
            className="stack"
            onSubmit={(e) => {
              e.preventDefault();
              run(() => loginDev(username.trim().toLowerCase()));
            }}
          >
            <div className="alert alert-warn small">
              Chế độ phát triển: token lấy từ <code>POST /dev/token</code> của Backend (profile local). Trên GCP dùng
              Firebase / Identity Platform.
            </div>
            <label className="field">
              Tên người dùng
              <input
                className="input"
                pattern="[a-z0-9-]{1,40}"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </label>
            <div className="chips">
              {DEMO_USERS.map((d) => (
                <button
                  type="button"
                  key={d.user}
                  className="chip"
                  onClick={() => run(() => loginDev(d.user))}
                  disabled={busy}
                >
                  {d.label}
                </button>
              ))}
            </div>
            <ErrorBox error={error} />
            <button className="btn btn-primary btn-block" disabled={busy}>
              {busy ? "Đang xử lý..." : "Đăng nhập"}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}

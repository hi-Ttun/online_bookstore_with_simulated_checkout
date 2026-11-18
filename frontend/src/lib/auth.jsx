// Đăng nhập (HANDOVER.md mục 4.1).
//  - Chế độ "firebase": Firebase Authentication / Identity Platform; SDK tự làm mới ID token gần hết hạn.
//  - Chế độ "dev": lấy token qua POST /dev/token?user=... (chỉ có khi Backend chạy profile local).
// Quyền Store Operator do Backend quyết định; Frontend chỉ thử gọi 1 API quản trị để biết có hiện menu hay không.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { config } from "./config";
import { Admin, ApiError, apiRequest, setAuthHooks } from "./api";

const AuthContext = createContext(null);
const DEV_KEY = "bookstore.devSession";

let firebaseAuthPromise = null;
function loadFirebase() {
  if (!firebaseAuthPromise) {
    firebaseAuthPromise = Promise.all([import("firebase/app"), import("firebase/auth")]).then(
      ([appMod, authMod]) => {
        const app = appMod.initializeApp(config.firebase);
        return { auth: authMod.getAuth(app), mod: authMod };
      },
    );
  }
  return firebaseAuthPromise;
}

function readDevSession() {
  try {
    const s = JSON.parse(localStorage.getItem(DEV_KEY));
    if (s && s.expiresAt > Date.now()) return s;
  } catch {
    /* bỏ qua */
  }
  localStorage.removeItem(DEV_KEY);
  return null;
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null); // { email, uid }
  const [ready, setReady] = useState(false);
  const [isOperator, setIsOperator] = useState(false);
  const [sessionExpired, setSessionExpired] = useState(false);
  const fbRef = useRef(null);

  const getToken = useCallback(async () => {
    if (config.authMode === "firebase") {
      const fb = fbRef.current;
      const current = fb && fb.auth.currentUser;
      return current ? current.getIdToken() : null;
    }
    const s = readDevSession();
    return s ? s.token : null;
  }, []);

  const clearLocal = useCallback(() => {
    localStorage.removeItem(DEV_KEY);
    setUser(null);
    setIsOperator(false);
  }, []);

  // Đăng ký hook cho lớp API: 401 => xóa phiên, chuyển tới trang đăng nhập.
  useEffect(() => {
    setAuthHooks({
      getToken,
      onUnauthorized: () => {
        setSessionExpired(true);
        if (config.authMode === "dev") clearLocal();
      },
    });
  }, [getToken, clearLocal]);

  // Kiểm tra quyền operator: 200 => có quyền, 403 => khách hàng.
  const probeOperator = useCallback(async () => {
    try {
      await Admin.ops();
      setIsOperator(true);
    } catch (e) {
      setIsOperator(false);
      if (!(e instanceof ApiError) || (e.status !== 403 && e.status !== 401)) console.warn(e);
    }
  }, []);

  // Khôi phục phiên khi tải trang.
  useEffect(() => {
    if (config.authMode === "firebase") {
      let unsub = () => {};
      loadFirebase()
        .then((fb) => {
          fbRef.current = fb;
          unsub = fb.mod.onAuthStateChanged(fb.auth, (u) => {
            setUser(u ? { email: u.email, uid: u.uid } : null);
            setReady(true);
          });
        })
        .catch((e) => {
          console.error("Không khởi tạo được Firebase", e);
          setReady(true);
        });
      return () => unsub();
    }
    const s = readDevSession();
    if (s) setUser({ email: s.email, uid: s.sub });
    setReady(true);
    return undefined;
  }, []);

  useEffect(() => {
    if (user) {
      setSessionExpired(false);
      probeOperator();
    } else {
      setIsOperator(false);
    }
  }, [user, probeOperator]);

  const loginDev = useCallback(async (username) => {
    const { data } = await apiRequest(`/dev/token?user=${encodeURIComponent(username)}`, { method: "POST" });
    const session = {
      token: data.accessToken,
      email: data.email,
      sub: data.sub,
      expiresAt: Date.now() + (data.expiresIn - 30) * 1000,
    };
    localStorage.setItem(DEV_KEY, JSON.stringify(session));
    setUser({ email: session.email, uid: session.sub });
  }, []);

  const loginFirebase = useCallback(async (email, password, register = false) => {
    const fb = await loadFirebase();
    fbRef.current = fb;
    const fn = register ? fb.mod.createUserWithEmailAndPassword : fb.mod.signInWithEmailAndPassword;
    await fn(fb.auth, email, password);
  }, []);

  const logout = useCallback(async () => {
    if (config.authMode === "firebase" && fbRef.current) {
      await fbRef.current.mod.signOut(fbRef.current.auth);
    }
    clearLocal();
  }, [clearLocal]);

  const value = useMemo(
    () => ({ user, ready, isOperator, sessionExpired, loginDev, loginFirebase, logout, mode: config.authMode }),
    [user, ready, isOperator, sessionExpired, loginDev, loginFirebase, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}

/** Thông báo lỗi Firebase dễ hiểu. */
export function firebaseMessage(e) {
  const map = {
    "auth/invalid-credential": "Email hoặc mật khẩu không đúng.",
    "auth/invalid-email": "Email không hợp lệ.",
    "auth/user-not-found": "Tài khoản không tồn tại.",
    "auth/wrong-password": "Mật khẩu không đúng.",
    "auth/email-already-in-use": "Email đã được đăng ký.",
    "auth/weak-password": "Mật khẩu cần ít nhất 6 ký tự.",
    "auth/too-many-requests": "Thử quá nhiều lần, vui lòng đợi rồi thử lại.",
  };
  return map[e && e.code] || (e && e.message) || "Đăng nhập thất bại.";
}

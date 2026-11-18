// Giỏ hàng lưu ở Frontend (localStorage). Thêm cùng một sách nhiều lần thì gộp số lượng (HANDOVER.md mục 4.2).
// Giới hạn theo Backend: tối đa 20 dòng, mỗi dòng 1–100 cuốn.
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

const CartContext = createContext(null);
const KEY = "bookstore.cart";
export const MAX_LINES = 20;
export const MAX_QTY = 100;

function load() {
  try {
    const v = JSON.parse(localStorage.getItem(KEY));
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

export function CartProvider({ children }) {
  const [items, setItems] = useState(load);

  useEffect(() => {
    localStorage.setItem(KEY, JSON.stringify(items));
  }, [items]);

  // Đồng bộ giữa các tab trình duyệt.
  useEffect(() => {
    const onStorage = (e) => e.key === KEY && setItems(load());
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  /** Thêm sách; trả về thông báo lỗi nếu không thêm được. */
  const add = useCallback(
    (product, qty = 1) => {
      const existing = items.find((i) => i.productId === product.id);
      if (!existing && items.length >= MAX_LINES) return `Giỏ hàng tối đa ${MAX_LINES} đầu sách.`;
      const limit = Math.min(MAX_QTY, product.available);
      const current = existing ? existing.quantity : 0;
      if (current + qty > limit) return `Chỉ còn ${product.available} cuốn "${product.title}".`;
      setItems((prev) => {
        const found = prev.find((i) => i.productId === product.id);
        if (found) {
          return prev.map((i) =>
            i.productId === product.id ? { ...i, quantity: i.quantity + qty, available: product.available } : i,
          );
        }
        return [
          ...prev,
          {
            productId: product.id,
            sku: product.sku,
            title: product.title,
            author: product.author,
            priceMinor: product.priceMinor,
            currency: product.currency,
            imageUrl: product.imageUrl,
            available: product.available,
            quantity: qty,
          },
        ];
      });
      return null;
    },
    [items],
  );

  const setQuantity = useCallback((productId, qty) => {
    setItems((prev) =>
      prev.map((i) =>
        i.productId === productId
          ? { ...i, quantity: Math.max(1, Math.min(qty, MAX_QTY, i.available ?? MAX_QTY)) }
          : i,
      ),
    );
  }, []);

  const remove = useCallback((productId) => {
    setItems((prev) => prev.filter((i) => i.productId !== productId));
  }, []);

  const clear = useCallback(() => setItems([]), []);

  /** Cập nhật số lượng còn bán được theo danh mục mới nhất. */
  const syncAvailability = useCallback((products) => {
    const byId = new Map(products.map((p) => [p.id, p]));
    setItems((prev) =>
      prev.map((i) => {
        const p = byId.get(i.productId);
        return p ? { ...i, available: p.available, priceMinor: p.priceMinor } : i;
      }),
    );
  }, []);

  const value = useMemo(() => {
    const count = items.reduce((s, i) => s + i.quantity, 0);
    const totalMinor = items.reduce((s, i) => s + i.quantity * i.priceMinor, 0);
    return { items, count, totalMinor, add, setQuantity, remove, clear, syncAvailability };
  }, [items, add, setQuantity, remove, clear, syncAvailability]);

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart() {
  return useContext(CartContext);
}

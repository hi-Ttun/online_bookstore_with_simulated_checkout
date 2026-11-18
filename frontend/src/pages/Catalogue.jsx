// Danh mục 50 cuốn sách: GET /api/products (API công khai, không gửi token).
import { useEffect, useMemo, useState } from "react";
import { BookCover, ErrorBox, Spinner } from "../components/ui";
import { Catalog } from "../lib/api";
import { useCart } from "../lib/cart";
import { formatMoney } from "../lib/format";
import { usePolling } from "../lib/usePolling";

export default function Catalogue() {
  const { data, error, loading, reload } = usePolling(() => Catalog.list(), 0);
  const { add, items, syncAvailability } = useCart();
  const [q, setQ] = useState("");
  const [onlyAvailable, setOnlyAvailable] = useState(false);
  const [sort, setSort] = useState("id");
  const [toast, setToast] = useState(null);

  useEffect(() => {
    if (data) syncAvailability(data);
  }, [data, syncAvailability]);

  useEffect(() => {
    if (!toast) return undefined;
    const t = setTimeout(() => setToast(null), 2500);
    return () => clearTimeout(t);
  }, [toast]);

  const books = useMemo(() => {
    if (!data) return [];
    const term = q.trim().toLowerCase();
    let list = data.filter(
      (b) =>
        !term ||
        b.title.toLowerCase().includes(term) ||
        b.author.toLowerCase().includes(term) ||
        b.sku.toLowerCase().includes(term),
    );
    if (onlyAvailable) list = list.filter((b) => b.available > 0);
    const sorters = {
      id: (a, b) => a.id - b.id,
      priceAsc: (a, b) => a.priceMinor - b.priceMinor,
      priceDesc: (a, b) => b.priceMinor - a.priceMinor,
      title: (a, b) => a.title.localeCompare(b.title),
    };
    return [...list].sort(sorters[sort]);
  }, [data, q, onlyAvailable, sort]);

  const inCart = (id) => items.find((i) => i.productId === id)?.quantity || 0;

  const onAdd = (book) => {
    const err = add(book, 1);
    setToast(err ? { tone: "bad", text: err } : { tone: "ok", text: `Đã thêm "${book.title}" vào giỏ.` });
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Danh mục sách</h1>
          <p className="muted">{data ? `${data.length} đầu sách` : "Đang tải danh mục..."}</p>
        </div>
        <div className="toolbar">
          <input
            className="input"
            type="search"
            placeholder="Tìm theo tên sách, tác giả, SKU..."
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          <select className="input" value={sort} onChange={(e) => setSort(e.target.value)}>
            <option value="id">Mặc định</option>
            <option value="title">Tên A–Z</option>
            <option value="priceAsc">Giá tăng dần</option>
            <option value="priceDesc">Giá giảm dần</option>
          </select>
          <label className="check">
            <input type="checkbox" checked={onlyAvailable} onChange={(e) => setOnlyAvailable(e.target.checked)} />
            Còn hàng
          </label>
          <button className="btn btn-ghost" onClick={reload} title="Tải lại số lượng tồn">
            ↻ Làm mới
          </button>
        </div>
      </div>

      <ErrorBox error={error} onRetry={reload} />
      {loading && !data && <Spinner />}

      <div className="grid">
        {books.map((b) => {
          const out = b.available === 0;
          const qty = inCart(b.id);
          return (
            <article key={b.id} className={`card book ${out ? "is-out" : ""}`}>
              <BookCover product={b} />
              <div className="book-body">
                <div className="small muted">{b.sku}</div>
                <h3 className="book-title" title={b.title}>
                  {b.title}
                </h3>
                <div className="muted">{b.author}</div>
                <div className="book-foot">
                  <span className="price">{formatMoney(b.priceMinor, b.currency)}</span>
                  <span className={`stock ${out ? "stock-out" : b.available <= 5 ? "stock-low" : ""}`}>
                    {out ? "Hết hàng" : `Còn ${b.available}`}
                  </span>
                </div>
                <button
                  className="btn btn-primary btn-block"
                  disabled={out || qty >= b.available}
                  onClick={() => onAdd(b)}
                >
                  {out ? "Hết hàng" : qty > 0 ? `Thêm nữa (đã có ${qty})` : "Thêm vào giỏ"}
                </button>
              </div>
            </article>
          );
        })}
      </div>
      {data && books.length === 0 && <div className="empty">Không có sách phù hợp.</div>}
      {toast && <div className={`toast toast-${toast.tone}`}>{toast.text}</div>}
    </div>
  );
}

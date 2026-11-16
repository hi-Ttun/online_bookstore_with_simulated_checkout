-- Schema ban đầu của Online Bookstore (PostgreSQL 13+), do Flyway áp dụng khi ứng dụng khởi động.
-- Không sửa migration đã áp dụng; thay đổi schema bằng file V2__*.sql mới.
-- Tiền lưu dạng BIGINT theo đơn vị nhỏ nhất (VND: đồng).

-- 1. Sách
CREATE TABLE product (
    id          BIGSERIAL PRIMARY KEY,
    sku         TEXT        NOT NULL UNIQUE,              -- mã sách cố định
    title       TEXT        NOT NULL,
    author      TEXT        NOT NULL,
    price_minor BIGINT      NOT NULL CHECK (price_minor >= 0),
    currency    CHAR(3)     NOT NULL DEFAULT 'VND',
    image_url   TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2. Tồn kho. Còn bán được = on_hand - reserved
CREATE TABLE inventory (
    product_id BIGINT      PRIMARY KEY REFERENCES product (id),
    on_hand    INT         NOT NULL CHECK (on_hand >= 0),          -- số sách thực có trong kho
    reserved   INT         NOT NULL DEFAULT 0 CHECK (reserved >= 0), -- đang giữ cho đơn chờ thanh toán
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (reserved <= on_hand)                                     -- không bao giờ giữ chỗ vượt tồn kho
);

-- 3. Đơn hàng ("order" là từ khóa SQL nên đặt tên orders)
CREATE TABLE orders (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id      TEXT        NOT NULL,                 -- claim sub của JWT
    idempotency_key  TEXT        NOT NULL,
    request_hash     TEXT        NOT NULL,                 -- SHA-256 nội dung request
    status           TEXT        NOT NULL CHECK (status IN
                         ('PENDING_PAYMENT', 'PAYMENT_UNKNOWN', 'PAID', 'PAYMENT_FAILED', 'FULFILLED')),
    payment_scenario TEXT        NOT NULL CHECK (payment_scenario IN ('SUCCESS', 'FAIL', 'TIMEOUT')),
    total_minor      BIGINT      NOT NULL CHECK (total_minor >= 0),
    currency         CHAR(3)     NOT NULL DEFAULT 'VND',
    correlation_id   TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    fulfilled_at     TIMESTAMPTZ,
    UNIQUE (customer_id, idempotency_key)                  -- idempotency theo từng khách
);
CREATE INDEX orders_status_idx   ON orders (status, created_at);
CREATE INDEX orders_customer_idx ON orders (customer_id, created_at DESC);

-- 4. Dòng trong đơn (chụp lại giá tại thời điểm mua)
CREATE TABLE order_line (
    id               BIGSERIAL PRIMARY KEY,
    order_id         UUID   NOT NULL REFERENCES orders (id),
    product_id       BIGINT NOT NULL REFERENCES product (id),
    quantity         INT    NOT NULL CHECK (quantity > 0),
    unit_price_minor BIGINT NOT NULL CHECK (unit_price_minor >= 0),
    line_total_minor BIGINT NOT NULL CHECK (line_total_minor >= 0),
    UNIQUE (order_id, product_id)
);

-- 5. Lần gọi cổng thanh toán (mô phỏng)
CREATE TABLE payment_attempt (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id       UUID        NOT NULL REFERENCES orders (id),
    operation_key  TEXT        NOT NULL UNIQUE,            -- khóa ổn định của giao dịch, dùng khi đối soát
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor >= 0),
    currency       CHAR(3)     NOT NULL DEFAULT 'VND',
    status         TEXT        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    provider_ref   TEXT,
    failure_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ
);
CREATE INDEX payment_attempt_order_idx   ON payment_attempt (order_id);
CREATE INDEX payment_attempt_unknown_idx ON payment_attempt (created_at) WHERE status = 'UNKNOWN';

-- 6. Outbox: sự kiện ghi cùng transaction với thay đổi nghiệp vụ, relay gửi lên Pub/Sub sau
CREATE TABLE outbox_event (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(), -- khóa chống trùng của consumer
    aggregate_type   TEXT        NOT NULL,                 -- 'order'
    aggregate_id     TEXT        NOT NULL,                 -- order id
    event_type       TEXT        NOT NULL,                 -- 'order.created' | 'payment.succeeded' | 'payment.failed'
    payload          JSONB       NOT NULL,
    correlation_id   TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,                          -- NULL = chưa gửi
    publish_attempts INT         NOT NULL DEFAULT 0,
    last_error       TEXT
);
CREATE INDEX outbox_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;

-- 7. Lịch sử biến động kho
CREATE TABLE inventory_movement (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT      NOT NULL REFERENCES product (id),
    order_id   UUID        REFERENCES orders (id),         -- NULL với SEED
    kind       TEXT        NOT NULL CHECK (kind IN ('SEED', 'RESERVE', 'COMMIT', 'RELEASE')),
    quantity   INT         NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_id, product_id, kind)                    -- mỗi đơn chỉ COMMIT/RELEASE một lần
);
CREATE INDEX inventory_movement_product_idx ON inventory_movement (product_id, created_at);

-- 8. Sự kiện consumer đã xử lý (ghi cùng transaction với thay đổi dữ liệu)
CREATE TABLE processed_event (
    event_id     UUID        NOT NULL,
    consumer     TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

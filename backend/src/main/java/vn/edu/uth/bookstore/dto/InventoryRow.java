package vn.edu.uth.bookstore.dto;

import java.time.OffsetDateTime;

/** Tồn kho của một sách: onHand (thực có), reserved (đang giữ cho đơn chờ thanh toán), available. */
public record InventoryRow(
        long productId,
        String sku,
        String title,
        int onHand,
        int reserved,
        int available,
        OffsetDateTime updatedAt) {
}

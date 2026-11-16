package vn.edu.uth.bookstore.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import vn.edu.uth.bookstore.model.MovementType;

/** Một biến động kho. orderId = null với dòng SEED. */
public record InventoryMovementRow(
        long id,
        long productId,
        String sku,
        UUID orderId,
        MovementType kind,
        int quantity,
        OffsetDateTime createdAt) {
}

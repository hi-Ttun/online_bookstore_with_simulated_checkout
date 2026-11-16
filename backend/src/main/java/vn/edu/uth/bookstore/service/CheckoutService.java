package vn.edu.uth.bookstore.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.uth.bookstore.dto.CheckoutRequest;
import vn.edu.uth.bookstore.dto.CheckoutRequest.Item;
import vn.edu.uth.bookstore.dto.OrderReceipt;
import vn.edu.uth.bookstore.exception.ApiException;
import vn.edu.uth.bookstore.logging.CorrelationIdFilter;
import vn.edu.uth.bookstore.model.EventType;
import vn.edu.uth.bookstore.model.MovementType;
import vn.edu.uth.bookstore.model.PaymentScenario;
import vn.edu.uth.bookstore.repository.InventoryRepository;
import vn.edu.uth.bookstore.repository.OrderRepository;
import vn.edu.uth.bookstore.repository.OutboxRepository;

/**
 * Checkout nguyên tử: tạo đơn, giữ tồn kho và ghi sự kiện {@code order.created} vào outbox trong một transaction.
 * Mọi exception đều rollback toàn bộ nên không tồn tại đơn được tạo một phần.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    /** {@code replayed = true}: request trùng key và trùng nội dung với một đơn đã tạo trước đó. */
    public record Result(OrderReceipt receipt, boolean replayed) {
    }

    private final OrderRepository orders;
    private final InventoryRepository inventory;
    private final OutboxRepository outbox;

    public CheckoutService(OrderRepository orders, InventoryRepository inventory, OutboxRepository outbox) {
        this.orders = orders;
        this.inventory = inventory;
        this.outbox = outbox;
    }

    @Transactional
    public Result checkout(String customerId, String idempotencyKey, CheckoutRequest request) {
        // Khóa tồn kho theo thứ tự productId tăng dần ở mọi giao dịch để tránh deadlock.
        List<Item> items = request.items().stream().sorted(Comparator.comparing(Item::productId)).toList();
        rejectDuplicateProducts(items);
        String hash = requestHash(items, request.paymentScenario());

        Map<Long, OrderRepository.PriceRow> prices = orders.findPrices(items.stream().map(Item::productId).toList());
        long total = 0;
        for (Item item : items) {
            OrderRepository.PriceRow price = prices.get(item.productId());
            if (price == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                        "Không tìm thấy sách id=" + item.productId(), Map.of("productId", item.productId()));
            }
            total = Math.addExact(total, Math.multiplyExact(price.priceMinor(), item.quantity()));
        }
        // shortcut: cửa hàng chỉ bán bằng VND nên lấy currency của dòng đầu; hỗ trợ đa tiền tệ thì phải kiểm từng dòng.
        String currency = prices.get(items.get(0).productId()).currency();

        String correlationId = CorrelationIdFilter.current();
        Optional<UUID> newOrderId = orders.insertIfAbsent(customerId, idempotencyKey, hash,
                request.paymentScenario(), total, currency, correlationId);
        if (newOrderId.isEmpty()) {
            return replay(customerId, idempotencyKey, hash);
        }
        UUID orderId = newOrderId.get();

        for (Item item : items) {
            if (!inventory.reserve(item.productId(), item.quantity())) {
                log.atWarn().addKeyValue("event", "inventory_conflict").addKeyValue("productId", item.productId())
                        .log("event=inventory_conflict productId={} requested={}", item.productId(), item.quantity());
                throw new ApiException(HttpStatus.CONFLICT, "OUT_OF_STOCK",
                        "Sách id=" + item.productId() + " không đủ số lượng",
                        Map.of("productId", item.productId(), "requested", item.quantity()));
            }
            orders.insertLine(orderId, item.productId(), item.quantity(), prices.get(item.productId()).priceMinor());
            inventory.insertMovement(item.productId(), orderId, MovementType.RESERVE, item.quantity());
        }

        outbox.insert("order", orderId.toString(), EventType.ORDER_CREATED, Map.of(
                "orderId", orderId.toString(),
                "customerId", customerId,
                "totalMinor", total,
                "currency", currency,
                "paymentScenario", request.paymentScenario().name(),
                "lines", items.stream()
                        .map(i -> Map.of("productId", i.productId(), "quantity", i.quantity()))
                        .toList()),
                correlationId);

        log.info("event=checkout_created orderId={} customerId={} totalMinor={}", orderId, customerId, total);
        return new Result(orders.findReceipt(orderId, customerId).orElseThrow(), false);
    }

    /** Key đã tồn tại: trả đơn cũ nếu nội dung trùng khớp, ngược lại báo xung đột. */
    private Result replay(String customerId, String idempotencyKey, String hash) {
        OrderRepository.ExistingOrder existing = orders.findByIdempotencyKey(customerId, idempotencyKey);
        if (!existing.requestHash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key đã được dùng cho một giỏ hàng khác", Map.of("orderId", existing.id()));
        }
        return new Result(orders.findReceipt(existing.id(), customerId).orElseThrow(), true);
    }

    private static void rejectDuplicateProducts(List<Item> items) {
        if (new HashSet<>(items.stream().map(Item::productId).toList()).size() != items.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Mỗi sách chỉ được xuất hiện một lần trong items");
        }
    }

    /**
     * SHA-256 của nội dung request ở dạng chuẩn hóa, ví dụ {@code "1x2,5x1|SUCCESS"}.
     * {@code sortedItems} phải được sắp theo productId để cùng giỏ hàng luôn cho cùng giá trị.
     */
    static String requestHash(List<Item> sortedItems, PaymentScenario scenario) {
        String canonical = sortedItems.stream()
                .map(i -> i.productId() + "x" + i.quantity())
                .collect(Collectors.joining(",")) + "|" + scenario.name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 luôn có sẵn trong JDK
        }
    }
}

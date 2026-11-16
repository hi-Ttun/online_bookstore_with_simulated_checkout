package vn.edu.uth.bookstore.dto;

/**
 * Sách trong danh mục.
 *
 * @param priceMinor giá theo đơn vị tiền nhỏ nhất (VND: đồng)
 * @param available  số lượng còn bán được = on_hand - reserved
 */
public record ProductResponse(
        long id,
        String sku,
        String title,
        String author,
        long priceMinor,
        String currency,
        String imageUrl,
        int available) {
}

package vn.edu.uth.bookstore.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.uth.bookstore.dto.ProductResponse;

/** Truy vấn danh mục sách kèm số lượng còn bán được. */
@Repository
public class ProductRepository {

    private static final String SELECT_PRODUCT = """
            SELECT p.id, p.sku, p.title, p.author, p.price_minor, p.currency, p.image_url,
                   i.on_hand - i.reserved AS available
            FROM product p
            JOIN inventory i ON i.product_id = p.id
            """;

    private final JdbcClient jdbc;

    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProductResponse> findPage(int page, int size) {
        return jdbc.sql(SELECT_PRODUCT + " ORDER BY p.id LIMIT :limit OFFSET :offset")
                .param("limit", size)
                .param("offset", (long) page * size)
                .query(ProductResponse.class)
                .list();
    }

    public Optional<ProductResponse> findById(long id) {
        return jdbc.sql(SELECT_PRODUCT + " WHERE p.id = :id")
                .param("id", id)
                .query(ProductResponse.class)
                .optional();
    }
}

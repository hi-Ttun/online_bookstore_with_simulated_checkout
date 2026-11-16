package vn.edu.uth.bookstore.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import vn.edu.uth.bookstore.dto.ProductResponse;
import vn.edu.uth.bookstore.exception.ApiException;
import vn.edu.uth.bookstore.repository.ProductRepository;

/** Danh mục sách, truy cập công khai. */
@Tag(name = "Catalog")
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @Operation(summary = "Danh sách sách (page từ 0, size 1-100)")
    @GetMapping
    public List<ProductResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return products.findPage(page, size);
    }

    @Operation(summary = "Chi tiết một sách")
    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable long id) {
        return products.findById(id)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Không tìm thấy sách id=" + id));
    }
}

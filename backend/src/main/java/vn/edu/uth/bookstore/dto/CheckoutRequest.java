package vn.edu.uth.bookstore.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.edu.uth.bookstore.model.PaymentScenario;

/** Giỏ hàng gửi lên khi checkout. Mỗi productId chỉ xuất hiện một lần. */
public record CheckoutRequest(
        @NotEmpty @Size(max = 20) List<@Valid Item> items,
        @NotNull PaymentScenario paymentScenario) {

    public record Item(
            @NotNull Long productId,
            @NotNull @Min(1) @Max(100) Integer quantity) {
    }
}

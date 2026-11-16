package vn.edu.uth.bookstore.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import vn.edu.uth.bookstore.dto.CheckoutRequest.Item;
import vn.edu.uth.bookstore.model.PaymentScenario;

/** Unit test cho hàm băm nội dung request dùng trong kiểm tra idempotency. */
class CheckoutServiceTest {

    private static final List<Item> CART = List.of(new Item(1L, 2), new Item(5L, 1));

    @Test
    void sameCartAndScenario_produceSameHash() {
        assertThat(CheckoutService.requestHash(List.of(new Item(1L, 2), new Item(5L, 1)), PaymentScenario.SUCCESS))
                .isEqualTo(CheckoutService.requestHash(CART, PaymentScenario.SUCCESS));
    }

    @Test
    void differentQuantity_producesDifferentHash() {
        assertThat(CheckoutService.requestHash(List.of(new Item(1L, 3), new Item(5L, 1)), PaymentScenario.SUCCESS))
                .isNotEqualTo(CheckoutService.requestHash(CART, PaymentScenario.SUCCESS));
    }

    @Test
    void differentScenario_producesDifferentHash() {
        assertThat(CheckoutService.requestHash(CART, PaymentScenario.FAIL))
                .isNotEqualTo(CheckoutService.requestHash(CART, PaymentScenario.SUCCESS));
    }

    @Test
    void productAndQuantityAreSeparated() {
        // "1x12" (sách 1, 12 cuốn) không được trùng "11x2" (sách 11, 2 cuốn).
        assertThat(CheckoutService.requestHash(List.of(new Item(1L, 12)), PaymentScenario.SUCCESS))
                .isNotEqualTo(CheckoutService.requestHash(List.of(new Item(11L, 2)), PaymentScenario.SUCCESS));
    }
}

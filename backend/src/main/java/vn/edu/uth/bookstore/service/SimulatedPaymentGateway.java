package vn.edu.uth.bookstore.service;

import org.springframework.stereotype.Component;

import vn.edu.uth.bookstore.model.PaymentScenario;
import vn.edu.uth.bookstore.model.PaymentStatus;

/**
 * Cổng thanh toán mô phỏng, kết quả xác định hoàn toàn theo kịch bản của đơn.
 *
 * <pre>
 * Kịch bản   charge()                      lookup() khi đối soát
 * SUCCESS    SUCCEEDED                     SUCCEEDED
 * FAIL       FAILED                        FAILED
 * TIMEOUT    PaymentTimeoutException       SUCCEEDED (giao dịch thực tế đã trừ tiền)
 * </pre>
 *
 * Kịch bản TIMEOUT minh họa lý do timeout phải chờ đối soát thay vì coi là thất bại.
 */
@Component
public class SimulatedPaymentGateway {

    /** Cổng không phản hồi kịp: chưa xác định được giao dịch đã trừ tiền hay chưa. */
    public static class PaymentTimeoutException extends Exception {
        public PaymentTimeoutException(String operationKey) {
            super("Payment gateway timeout, operationKey=" + operationKey);
        }
    }

    /**
     * Yêu cầu thanh toán. {@code operationKey} là khóa ổn định của giao dịch: gọi lại với cùng khóa
     * không trừ tiền lần hai, và dùng để tra cứu khi đối soát.
     */
    public PaymentStatus charge(String operationKey, PaymentScenario scenario, long amountMinor)
            throws PaymentTimeoutException {
        return switch (scenario) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case FAIL -> PaymentStatus.FAILED;
            case TIMEOUT -> throw new PaymentTimeoutException(operationKey);
        };
    }

    /**
     * Tra cứu kết quả cuối cùng của giao dịch theo {@code operationKey}.
     * shortcut: cổng mô phỏng không lưu trạng thái nên nhận thêm kịch bản; cổng thật chỉ cần operationKey.
     */
    public PaymentStatus lookup(String operationKey, PaymentScenario scenario) {
        return scenario == PaymentScenario.FAIL ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED;
    }
}

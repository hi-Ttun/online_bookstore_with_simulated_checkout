package vn.edu.uth.bookstore.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import vn.edu.uth.bookstore.service.ReconciliationService;

/** Chạy đối soát thanh toán định kỳ. Operator vẫn có thể kích hoạt thủ công qua POST /api/admin/reconcile. */
@Component
@ConditionalOnProperty(name = "app.reconciler.enabled", havingValue = "true")
public class ReconciliationJob {

    private final ReconciliationService reconciliation;

    public ReconciliationJob(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${app.reconciler.interval-ms}")
    public void run() {
        reconciliation.reconcileNow();
    }
}

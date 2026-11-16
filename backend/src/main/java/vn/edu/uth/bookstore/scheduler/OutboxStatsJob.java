package vn.edu.uth.bookstore.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import vn.edu.uth.bookstore.repository.OutboxRepository;

/**
 * Ghi định kỳ chỉ số outbox (pending, oldestAgeSeconds) làm nguồn cho metric "outbox age" và cảnh báo.
 * Chạy độc lập với relay để vẫn phát hiện được tình trạng ứ đọng khi relay ngừng hoạt động.
 */
@Component
@ConditionalOnProperty(name = "app.outbox-stats.enabled", havingValue = "true")
public class OutboxStatsJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxStatsJob.class);

    private final OutboxRepository outbox;

    public OutboxStatsJob(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedRateString = "${app.outbox-stats.interval-ms}")
    public void report() {
        OutboxRepository.Stats stats = outbox.stats();
        log.atInfo().addKeyValue("event", "outbox_stats").addKeyValue("pending", stats.pending())
                .addKeyValue("oldestAgeSeconds", stats.oldestAgeSeconds())
                .log("event=outbox_stats pending={} oldestAgeSeconds={}", stats.pending(), stats.oldestAgeSeconds());
    }
}

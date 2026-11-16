package vn.edu.uth.bookstore.logging;

import java.util.Map;

import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;

/**
 * Định dạng log JSON một dòng cho Google Cloud Logging (bật ở profile cloud).
 * Các trường: severity, time, message, logger, thread, toàn bộ MDC (correlationId, orderId, eventId),
 * các key-value gắn qua {@code log.atInfo().addKeyValue(...)} và stack_trace khi có exception.
 */
public class CloudLoggingFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private final JsonWriter<ILoggingEvent> writer = JsonWriter.<ILoggingEvent>of(members -> {
        members.add("severity", event -> severity(event.getLevel()));
        members.add("time", event -> event.getInstant().toString());
        members.add("message", ILoggingEvent::getFormattedMessage);
        members.add("logger", ILoggingEvent::getLoggerName);
        members.add("thread", ILoggingEvent::getThreadName);
        members.from(ILoggingEvent::getMDCPropertyMap).whenNotEmpty().usingPairs(Map::forEach);
        members.from(ILoggingEvent::getKeyValuePairs).whenNotEmpty()
                .usingExtractedPairs(Iterable::forEach, (KeyValuePair pair) -> pair.key, pair -> pair.value);
        members.add("stack_trace", ILoggingEvent::getThrowableProxy).whenNotNull().as(ThrowableProxyUtil::asString);
    }).withNewLineAtEnd();

    @Override
    public String format(ILoggingEvent event) {
        return writer.writeToString(event);
    }

    // Cloud Logging dùng WARNING thay cho WARN.
    private static String severity(Level level) {
        return switch (level.toInt()) {
            case Level.ERROR_INT -> "ERROR";
            case Level.WARN_INT -> "WARNING";
            case Level.INFO_INT -> "INFO";
            default -> "DEBUG";
        };
    }
}

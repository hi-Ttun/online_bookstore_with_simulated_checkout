package vn.edu.uth.bookstore.config;

import java.io.IOException;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.pubsub.v1.TopicName;

import io.grpc.ManagedChannelBuilder;

/**
 * Publisher cho topic sự kiện đơn hàng, chỉ tạo khi bật outbox relay.
 * Khi {@code app.pubsub.emulator-host} có giá trị thì kết nối Pub/Sub emulator; ngược lại dùng Pub/Sub thật với
 * Application Default Credentials (service account của Cloud Run).
 */
@Configuration
@ConditionalOnProperty(name = "app.relay.enabled", havingValue = "true")
public class PubSubConfig {

    @Bean(destroyMethod = "shutdown")
    Publisher orderEventsPublisher(
            @Value("${app.pubsub.project-id}") String projectId,
            @Value("${app.pubsub.topic}") String topic,
            @Value("${app.pubsub.emulator-host}") String emulatorHost) throws IOException {
        Publisher.Builder builder = Publisher.newBuilder(TopicName.of(projectId, topic))
                // Giới hạn tổng thời gian retry 10 giây (mặc định 10 phút); relay tự thử lại ở chu kỳ sau.
                .setRetrySettings(RetrySettings.newBuilder()
                        .setInitialRetryDelayDuration(Duration.ofMillis(100))
                        .setRetryDelayMultiplier(2.0)
                        .setMaxRetryDelayDuration(Duration.ofSeconds(2))
                        .setInitialRpcTimeoutDuration(Duration.ofSeconds(5))
                        .setRpcTimeoutMultiplier(1.0)
                        .setMaxRpcTimeoutDuration(Duration.ofSeconds(5))
                        .setTotalTimeoutDuration(Duration.ofSeconds(10))
                        .build());
        if (!emulatorHost.isBlank()) {
            var channel = ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build();
            builder.setChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                    .setCredentialsProvider(NoCredentialsProvider.create());
        }
        return builder.build();
    }
}

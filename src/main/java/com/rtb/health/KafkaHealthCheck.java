package com.rtb.health;

import com.rtb.config.AppConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Checks Kafka cluster connectivity via AdminClient. */
public final class KafkaHealthCheck implements HealthCheck {

    private final String bootstrapServers;

    public KafkaHealthCheck(AppConfig config) {
        this.bootstrapServers = config.get("kafka.bootstrap.servers", "localhost:9092");
    }

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public HealthStatus check() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);

        try (AdminClient admin = AdminClient.create(props)) {
            int nodeCount = admin.describeCluster().nodes().get(3, TimeUnit.SECONDS).size();
            return HealthStatus.up(nodeCount + " node(s)");
        } catch (Exception e) {
            return HealthStatus.down(e.getMessage());
        }
    }
}

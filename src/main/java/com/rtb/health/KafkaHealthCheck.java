package com.rtb.health;

import com.rtb.config.AppConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Checks Kafka cluster connectivity via a reusable AdminClient. */
public final class KafkaHealthCheck implements HealthCheck, AutoCloseable {

    private final AdminClient admin;

    public KafkaHealthCheck(AppConfig config) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                config.get("kafka.bootstrap.servers", "localhost:9092"));
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
        this.admin = AdminClient.create(props);
    }

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public HealthStatus check() {
        try {
            int nodeCount = admin.describeCluster().nodes().get(3, TimeUnit.SECONDS).size();
            return HealthStatus.up(nodeCount + " node(s)");
        } catch (Exception e) {
            return HealthStatus.down(e.getMessage());
        }
    }

    @Override
    public void close() {
        admin.close();
    }
}

package com.rtb.config;

/** Aerospike connection configuration. */
public record AerospikeConfig(String host,
                               int port,
                               String namespace,
                               String set,
                               int connectTimeoutMs,
                               int commandTimeoutMs,
                               int eventLoopSize) {

    public AerospikeConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("aerospike.host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("aerospike.port out of range: " + port);
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("aerospike.namespace must not be blank");
        }
        if (set == null || set.isBlank()) {
            throw new IllegalArgumentException("aerospike.set must not be blank");
        }
        if (connectTimeoutMs < 100) {
            throw new IllegalArgumentException("aerospike.connect.timeout.ms must be >= 100");
        }
        if (commandTimeoutMs < 1) {
            throw new IllegalArgumentException("aerospike.command.timeout.ms must be >= 1");
        }
        if (eventLoopSize < 1) {
            throw new IllegalArgumentException("aerospike.eventloop.size must be >= 1");
        }
    }

    public static AerospikeConfig from(AppConfig config) {
        return new AerospikeConfig(
                config.get("aerospike.host", "localhost"),
                config.getInt("aerospike.port", 3000),
                config.get("aerospike.namespace", "rtb"),
                config.get("aerospike.set", "freq"),
                config.getInt("aerospike.connect.timeout.ms", 2000),
                config.getInt("aerospike.command.timeout.ms", 50),
                config.getInt("aerospike.eventloop.size", 4)
        );
    }
}

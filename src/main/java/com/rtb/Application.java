package com.rtb;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.rtb.config.AppConfig;
import com.rtb.config.PipelineConfig;
import com.rtb.config.PostgresConfig;
import com.rtb.config.RedisConfig;
import com.rtb.resilience.CircuitBreaker;
import com.rtb.resilience.ResilientRedis;
import com.rtb.resilience.ResilientEventPublisher;
import com.rtb.health.CompositeHealthCheck;
import com.rtb.health.KafkaHealthCheck;
import com.rtb.health.RedisHealthCheck;
import com.rtb.metrics.BidMetrics;
import com.rtb.metrics.EventLoopLagProbe;
import com.rtb.metrics.MetricsRegistry;
import com.rtb.event.EventPublisher;
import com.rtb.event.KafkaEventPublisher;
import com.rtb.event.NoOpEventPublisher;
import com.rtb.pipeline.BidPipeline;
import com.rtb.pipeline.PipelineStage;
import com.rtb.frequency.RedisFrequencyCapper;
import com.rtb.pacing.BudgetMetrics;
import com.rtb.pacing.BudgetPacer;
import com.rtb.pacing.DistributedBudgetPacer;
import com.rtb.pacing.HourlyPacedBudgetPacer;
import com.rtb.pacing.LocalBudgetPacer;
import com.rtb.pacing.QualityThrottledBudgetPacer;
import com.rtb.pipeline.stages.BudgetPacingStage;
import com.rtb.pipeline.stages.CandidateRetrievalStage;
import com.rtb.pipeline.stages.FrequencyCapStage;
import com.rtb.pipeline.stages.RankingStage;
import com.rtb.pipeline.stages.RequestValidationStage;
import com.rtb.pipeline.stages.ResponseBuildStage;
import com.rtb.pipeline.stages.ScoringStage;
import com.rtb.scoring.ABTestScorer;
import com.rtb.scoring.CascadeScorer;
import com.rtb.scoring.FeatureSchema;
import com.rtb.scoring.FeatureWeightedScorer;
import com.rtb.scoring.MLScorer;
import com.rtb.scoring.Scorer;
import com.rtb.pipeline.stages.UserEnrichmentStage;
import com.rtb.repository.CachedCampaignRepository;
import com.rtb.repository.CampaignRepository;
import com.rtb.repository.JsonCampaignRepository;
import com.rtb.repository.PostgresCampaignRepository;
import com.rtb.repository.RedisUserSegmentRepository;
import com.rtb.server.BidRequestHandler;
import com.rtb.server.BidRouter;
import com.rtb.server.HttpServer;
import com.rtb.server.TrackingHandler;
import com.rtb.server.WinHandler;
import com.rtb.targeting.EmbeddingTargetingEngine;
import com.rtb.targeting.HybridTargetingEngine;
import com.rtb.targeting.SegmentTargetingEngine;
import com.rtb.targeting.TargetingEngine;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Composition root — wires dependencies and starts the server. */
public final class Application {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        try {
            start(args);
        } catch (Exception e) {
            // Full stack trace goes to log file — terminal only sees the clean message
            logger.error("Startup failed", e);
            System.err.println();
            System.err.println("ERROR: " + friendlyStartupError(e));
            System.err.println("       Full stack trace written to logs/rtb-bidder.log");
            System.err.println();
            System.exit(1);
        }
    }

    private static String friendlyStartupError(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        Throwable cause = e.getCause();
        String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage() : "";

        if (msg.contains("Unable to connect") || causeMsg.contains("Connection refused")) {
            return "Cannot connect to Redis. Is Docker running? → docker-compose up -d";
        }
        if (msg.contains("kafka") || causeMsg.contains("kafka")) {
            return "Cannot connect to Kafka. Start it with → docker-compose up -d kafka";
        }
        if (msg.contains("campaigns") || msg.contains("Campaign file not found")) {
            return "Campaign file not found. Check campaigns.json exists in src/main/resources/";
        }
        if (msg.contains("address already in use") || causeMsg.contains("address already in use")) {
            return "Port 8080 is already in use. Kill the existing process or change server.port";
        }
        if (msg.contains("model") || msg.contains(".onnx")) {
            return "ML model file not found. Check SCORING_MODEL_PATH points to a valid .onnx file";
        }
        return e.getClass().getSimpleName() + ": " + (msg.isEmpty() ? causeMsg : msg);
    }

    private static void start(String[] args) {
        AppConfig config = AppConfig.load();
        int port = config.getInt("server.port", 8080);
        int maxBodySize = config.getInt("server.body.maxSize", 65536);
        String baseUrl = config.get("server.baseUrl", "http://localhost:" + port);

        ObjectMapper objectMapper = createObjectMapper();
        PipelineConfig pipelineConfig = PipelineConfig.from(config);
        RedisConfig redisConfig = RedisConfig.from(config);

        // Metrics — created first so Redis client Timers and circuit breaker
        // gauges can register against it during their construction.
        MetricsRegistry metricsRegistry = new MetricsRegistry();

        // Repositories
        RedisUserSegmentRepository userSegmentRepo = new RedisUserSegmentRepository(
                redisConfig, metricsRegistry.registry());
        Runtime.getRuntime().addShutdownHook(new Thread(userSegmentRepo::close, "shutdown-redis"));

        CampaignRepository campaignRepo = createCampaignRepository(config, objectMapper);

        TargetingEngine targetingEngine = createTargetingEngine(config);
        Scorer scorer = createScorer(config);
        if (scorer instanceof AutoCloseable closeableScorer) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(closeableScorer), "shutdown-scorer"));
        }
        RedisFrequencyCapper frequencyCapper = new RedisFrequencyCapper(
                redisConfig, metricsRegistry.registry());
        Runtime.getRuntime().addShutdownHook(new Thread(frequencyCapper::close, "shutdown-freq-capper"));

        // Resilience — separate circuit breakers for Redis and Kafka
        int redisFailures = config.getInt("resilience.redis.failure.threshold", 5);
        long redisCooldown = config.getLong("resilience.redis.cooldown.ms", 10000);
        int kafkaFailures = config.getInt("resilience.kafka.failure.threshold", 5);
        long kafkaCooldown = config.getLong("resilience.kafka.cooldown.ms", 30000);

        ResilientRedis resilientRedis = new ResilientRedis(
                userSegmentRepo, frequencyCapper, redisFailures, redisCooldown);
        resilientRedis.getCircuitBreaker().registerMetrics(metricsRegistry.registry());
        logger.info("Circuit breakers: redis(failures={}, cooldown={}ms), kafka(failures={}, cooldown={}ms)",
                redisFailures, redisCooldown, kafkaFailures, kafkaCooldown);
        BudgetMetrics budgetMetrics = new BudgetMetrics();
        BudgetPacer budgetPacer = createBudgetPacer(config, redisConfig, campaignRepo, budgetMetrics);
        if (budgetPacer instanceof AutoCloseable closeablePacer) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(closeablePacer), "shutdown-pacer"));
        }

        // Pipeline stages — 8 stages, executed in order
        // resilientRedis wraps both userSegmentRepo AND frequencyCapper with one circuit breaker
        // — if Redis is down, both segment lookup and freq capping degrade together
        List<PipelineStage> stages = List.of(
                new RequestValidationStage(),
                new UserEnrichmentStage(resilientRedis),       // segments via circuit breaker
                new CandidateRetrievalStage(campaignRepo, targetingEngine),
                new FrequencyCapStage(resilientRedis),          // freq cap via same circuit breaker
                new ScoringStage(scorer),
                new RankingStage(),
                new BudgetPacingStage(budgetPacer),
                new ResponseBuildStage(baseUrl)
        );
        BidMetrics bidMetrics = new BidMetrics(metricsRegistry.registry());

        BidPipeline pipeline = new BidPipeline(stages, pipelineConfig, bidMetrics);

        // Pool saturation gauges — validate the Phase 11 zero-alloc claim at runtime.
        // If bid_context_pool_total_created keeps climbing after warmup, the pool is
        // undersized and we're allocating on the hot path (GC pressure returns).
        metricsRegistry.registry().gauge("bid_context_pool_available",
                pipeline, BidPipeline::getContextPoolAvailable);
        metricsRegistry.registry().gauge("bid_context_pool_total_created",
                pipeline, BidPipeline::getContextPoolTotalCreated);

        KafkaHealthCheck kafkaHealthCheck = new KafkaHealthCheck(config);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(kafkaHealthCheck), "shutdown-kafka-health"));

        CompositeHealthCheck healthCheck = new CompositeHealthCheck(List.of(
                new RedisHealthCheck(userSegmentRepo.getConnection()),
                kafkaHealthCheck
        ));

        // Event publishing — Kafka async failures feed into circuit breaker
        CircuitBreaker kafkaCircuitBreaker = new CircuitBreaker("kafka-events", kafkaFailures, kafkaCooldown);
        kafkaCircuitBreaker.registerMetrics(metricsRegistry.registry());
        EventPublisher rawEventPublisher = createEventPublisher(
                config, kafkaCircuitBreaker::recordExternalFailure, metricsRegistry.registry());
        if (rawEventPublisher instanceof AutoCloseable closeablePublisher) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(closeablePublisher), "shutdown-events"));
        }
        EventPublisher eventPublisher = new ResilientEventPublisher(rawEventPublisher, kafkaCircuitBreaker);

        // Handlers — use resilient wrappers
        BidRequestHandler bidRequestHandler = new BidRequestHandler(pipeline, resilientRedis, eventPublisher, bidMetrics);
        WinHandler winHandler = new WinHandler(objectMapper, eventPublisher, bidMetrics, campaignRepo);
        TrackingHandler trackingHandler = new TrackingHandler(eventPublisher, bidMetrics);
        BidRouter bidRouter = new BidRouter(bidRequestHandler, winHandler, trackingHandler, maxBodySize,
                metricsRegistry, healthCheck, objectMapper);

        // Start
        Vertx vertx = Vertx.vertx(new VertxOptions());

        // Event-loop lag probe — the canonical Vert.x health signal
        EventLoopLagProbe lagProbe = new EventLoopLagProbe(vertx, metricsRegistry.registry());
        lagProbe.start();
        Runtime.getRuntime().addShutdownHook(new Thread(lagProbe::stop, "shutdown-lag-probe"));

        HttpServer httpServer = new HttpServer(vertx, bidRouter, port);

        httpServer.start()
                .onSuccess(server -> logger.info("RTB Bidder started on port {} | SLA: {}ms | stages: {}",
                        server.actualPort(), pipelineConfig.maxLatencyMs(), stages.size()))
                .onFailure(err -> {
                    logger.error("Failed to start HTTP server", err);
                    System.err.println();
                    System.err.println("ERROR: " + friendlyStartupError(err));
                    System.err.println("       Full stack trace written to logs/rtb-bidder.log");
                    System.err.println();
                    closeQuietly(frequencyCapper);
                    closeQuietly(userSegmentRepo);
                    vertx.close();
                    System.exit(1);
                });
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            logger.warn("Failed to close resource: {}", e.getMessage());
        }
    }

    private static CampaignRepository createCampaignRepository(AppConfig config, ObjectMapper objectMapper) {
        String type = config.get("campaigns.source", "json");
        logger.info("Campaign source: {}", type);

        CampaignRepository source = switch (type) {
            case "postgres" -> {
                PostgresConfig pgConfig = PostgresConfig.from(config);
                logger.info("Using PostgreSQL campaign repository");
                yield new PostgresCampaignRepository(pgConfig);
            }
            default -> {
                logger.info("Using JSON file campaign repository (default)");
                yield new JsonCampaignRepository(objectMapper, "campaigns.json");
            }
        };

        return new CachedCampaignRepository(source);
    }

    private static Scorer createScorer(AppConfig config) {
        String type = config.get("scoring.type", "feature-weighted");
        logger.info("Scoring type: {}", type);

        Scorer scorer = switch (type) {
            case "ml" -> {
                yield createMLScorer(config);
            }
            case "abtest" -> {
                int treatmentPct = config.getInt("scoring.abtest.treatment.percentage", 50);
                MLScorer mlScorer = createMLScorer(config);
                logger.info("A/B test: {}% ML, {}% feature-weighted", treatmentPct, 100 - treatmentPct);
                yield new ABTestScorer(new FeatureWeightedScorer(), mlScorer, treatmentPct);
            }
            case "cascade" -> {
                MLScorer mlScorer = createMLScorer(config);
                double threshold = Double.parseDouble(config.get("scoring.cascade.threshold", "0.1"));
                logger.info("Cascade: FeatureWeighted → ML (threshold: {})", threshold);
                yield new CascadeScorer(new FeatureWeightedScorer(), mlScorer, threshold);
            }
            default -> {
                logger.info("Using feature-weighted scorer (default)");
                yield new FeatureWeightedScorer();
            }
        };

        logger.info("Scorer initialized: {}", scorer.name());
        return scorer;
    }

    private static EventPublisher createEventPublisher(AppConfig config,
                                                        java.util.function.Consumer<Exception> failureCallback,
                                                        io.micrometer.core.instrument.MeterRegistry registry) {
        String type = config.get("events.type", "noop");
        logger.info("Event publisher: {}", type);
        return switch (type) {
            case "kafka" -> new KafkaEventPublisher(config, failureCallback, registry);
            default -> {
                logger.info("Using NoOp event publisher (events logged at DEBUG level)");
                yield new NoOpEventPublisher();
            }
        };
    }

    private static BudgetPacer createBudgetPacer(AppConfig config, RedisConfig redisConfig,
                                                    CampaignRepository campaignRepo, BudgetMetrics metrics) {
        String type = config.get("pacing.type", "local");
        logger.info("Pacing type: {}", type);
        BudgetPacer basePacer = switch (type) {
            case "distributed" -> {
                logger.info("Using distributed budget pacer (Redis)");
                yield new DistributedBudgetPacer(redisConfig, campaignRepo);
            }
            default -> {
                logger.info("Using local budget pacer (AtomicLong)");
                yield new LocalBudgetPacer(campaignRepo, metrics);
            }
        };

        boolean hourlyPacing = config.getBoolean("pacing.hourly.enabled", false);
        if (hourlyPacing) {
            int hours = config.getInt("pacing.hourly.hours", 24);
            basePacer = new HourlyPacedBudgetPacer(basePacer, hours, metrics);
        }

        // Quality throttling decorator — applied last so it sees the effective budget after hourly pacing
        boolean qualityThrottling = config.getBoolean("pacing.quality.throttling.enabled", false);
        if (qualityThrottling) {
            double lowThreshold = Double.parseDouble(config.get("pacing.quality.threshold.low", "0.05"));
            double highThreshold = Double.parseDouble(config.get("pacing.quality.threshold.high", "0.20"));
            logger.info("Quality-throttled pacing enabled: low={}, high={}", lowThreshold, highThreshold);
            return new QualityThrottledBudgetPacer(basePacer, lowThreshold, highThreshold);
        }

        return basePacer;
    }

    private static TargetingEngine createTargetingEngine(AppConfig config) {
        String type = config.get("targeting.type", "segment");
        logger.info("Targeting type: {}", type);
        return switch (type) {
            case "embedding" -> {
                String campaignEmb = config.get("targeting.embedding.campaign.path", "ml/campaign_embeddings.json");
                String wordEmb = config.get("targeting.embedding.word.path", "ml/word_embeddings.json");
                double threshold = Double.parseDouble(config.get("targeting.embedding.threshold", "0.3"));
                yield new EmbeddingTargetingEngine(campaignEmb, wordEmb, threshold);
            }
            case "hybrid" -> {
                String campaignEmb = config.get("targeting.embedding.campaign.path", "ml/campaign_embeddings.json");
                String wordEmb = config.get("targeting.embedding.word.path", "ml/word_embeddings.json");
                double threshold = Double.parseDouble(config.get("targeting.embedding.threshold", "0.3"));
                yield new HybridTargetingEngine(
                        new SegmentTargetingEngine(),
                        new EmbeddingTargetingEngine(campaignEmb, wordEmb, threshold)
                );
            }
            default -> {
                logger.info("Using segment targeting (default)");
                yield new SegmentTargetingEngine();
            }
        };
    }

    private static MLScorer createMLScorer(AppConfig config) {
        String modelPath = config.get("scoring.ml.model.path", "ml/pctr_model.onnx");
        String schemaPath = config.get("scoring.ml.schema.path", "ml/feature_schema.json");
        FeatureSchema schema = FeatureSchema.load(schemaPath);
        return new MLScorer(modelPath, schema);
    }

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}

package com.rtb;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.rtb.config.AppConfig;
import com.rtb.config.PipelineConfig;
import com.rtb.config.RedisConfig;
import com.rtb.pipeline.BidPipeline;
import com.rtb.pipeline.PipelineStage;
import com.rtb.frequency.RedisFrequencyCapper;
import com.rtb.pacing.BudgetPacer;
import com.rtb.pacing.DistributedBudgetPacer;
import com.rtb.pacing.HourlyPacedBudgetPacer;
import com.rtb.pacing.LocalBudgetPacer;
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
import com.rtb.repository.RedisUserSegmentRepository;
import com.rtb.server.BidRequestHandler;
import com.rtb.server.BidRouter;
import com.rtb.server.HttpServer;
import com.rtb.server.TrackingHandler;
import com.rtb.server.WinHandler;
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
        AppConfig config = AppConfig.load();
        int port = config.getInt("server.port", 8080);
        int maxBodySize = config.getInt("server.body.maxSize", 65536);
        String baseUrl = config.get("server.baseUrl", "http://localhost:" + port);

        ObjectMapper objectMapper = createObjectMapper();
        PipelineConfig pipelineConfig = PipelineConfig.from(config);
        RedisConfig redisConfig = RedisConfig.from(config);

        // Repositories
        RedisUserSegmentRepository userSegmentRepo = new RedisUserSegmentRepository(redisConfig);
        Runtime.getRuntime().addShutdownHook(new Thread(userSegmentRepo::close, "shutdown-redis"));

        CampaignRepository campaignRepo = new CachedCampaignRepository(objectMapper, "campaigns.json");

        TargetingEngine targetingEngine = new SegmentTargetingEngine();
        Scorer scorer = createScorer(config);
        if (scorer instanceof AutoCloseable closeableScorer) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(closeableScorer), "shutdown-scorer"));
        }
        RedisFrequencyCapper frequencyCapper = new RedisFrequencyCapper(redisConfig);
        Runtime.getRuntime().addShutdownHook(new Thread(frequencyCapper::close, "shutdown-freq-capper"));
        BudgetPacer budgetPacer = createBudgetPacer(config, redisConfig, campaignRepo);
        if (budgetPacer instanceof AutoCloseable closeablePacer) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(closeablePacer), "shutdown-pacer"));
        }

        // Pipeline stages — 8 stages, executed in order
        List<PipelineStage> stages = List.of(
                new RequestValidationStage(),
                new UserEnrichmentStage(userSegmentRepo),
                new CandidateRetrievalStage(campaignRepo, targetingEngine),
                new FrequencyCapStage(frequencyCapper),
                new ScoringStage(scorer),
                new RankingStage(),
                new BudgetPacingStage(budgetPacer),
                new ResponseBuildStage(baseUrl)
        );
        BidPipeline pipeline = new BidPipeline(stages, pipelineConfig);

        // Handlers
        BidRequestHandler bidRequestHandler = new BidRequestHandler(objectMapper, pipeline, frequencyCapper);
        WinHandler winHandler = new WinHandler(objectMapper);
        TrackingHandler trackingHandler = new TrackingHandler();
        BidRouter bidRouter = new BidRouter(bidRequestHandler, winHandler, trackingHandler, maxBodySize);

        // Start
        Vertx vertx = Vertx.vertx(new VertxOptions());
        HttpServer httpServer = new HttpServer(vertx, bidRouter, port);

        httpServer.start()
                .onSuccess(server -> logger.info("RTB Bidder started on port {} | SLA: {}ms | stages: {}",
                        server.actualPort(), pipelineConfig.maxLatencyMs(), stages.size()))
                .onFailure(err -> {
                    logger.error("Failed to start RTB Bidder", err);
                    closeQuietly(frequencyCapper);
                    closeQuietly(userSegmentRepo);
                    vertx.close();
                });
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            logger.warn("Failed to close resource: {}", e.getMessage());
        }
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

    private static BudgetPacer createBudgetPacer(AppConfig config, RedisConfig redisConfig,
                                                    CampaignRepository campaignRepo) {
        String type = config.get("pacing.type", "local");
        logger.info("Pacing type: {}", type);
        BudgetPacer basePacer = switch (type) {
            case "distributed" -> {
                logger.info("Using distributed budget pacer (Redis)");
                yield new DistributedBudgetPacer(redisConfig, campaignRepo);
            }
            default -> {
                logger.info("Using local budget pacer (AtomicLong)");
                yield new LocalBudgetPacer(campaignRepo);
            }
        };

        boolean hourlyPacing = config.getBoolean("pacing.hourly.enabled", false);
        if (hourlyPacing) {
            int hours = config.getInt("pacing.hourly.hours", 24);
            logger.info("Hourly pacing enabled: spreading across {} hours with spend smoothing", hours);
            return new HourlyPacedBudgetPacer(basePacer, hours);
        }
        return basePacer;
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

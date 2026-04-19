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
import com.rtb.pipeline.stages.CandidateRetrievalStage;
import com.rtb.pipeline.stages.FrequencyCapStage;
import com.rtb.pipeline.stages.RequestValidationStage;
import com.rtb.pipeline.stages.ResponseBuildStage;
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

        // Targeting + frequency capping
        TargetingEngine targetingEngine = new SegmentTargetingEngine();
        RedisFrequencyCapper frequencyCapper = new RedisFrequencyCapper(redisConfig);
        Runtime.getRuntime().addShutdownHook(new Thread(frequencyCapper::close, "shutdown-freq-capper"));

        // Pipeline stages — executed in order
        List<PipelineStage> stages = List.of(
                new RequestValidationStage(),
                new UserEnrichmentStage(userSegmentRepo),
                new CandidateRetrievalStage(campaignRepo, targetingEngine),
                new FrequencyCapStage(frequencyCapper),
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

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}

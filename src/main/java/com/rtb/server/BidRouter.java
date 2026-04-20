package com.rtb.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtb.health.CompositeHealthCheck;
import com.rtb.metrics.MetricsRegistry;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BidRouter {

    private static final Logger logger = LoggerFactory.getLogger(BidRouter.class);

    private final BidRequestHandler bidRequestHandler;
    private final WinHandler winHandler;
    private final TrackingHandler trackingHandler;
    private final int maxBodySize;
    private final MetricsRegistry metricsRegistry;
    private final CompositeHealthCheck healthCheck;
    private final ObjectMapper objectMapper;

    public BidRouter(BidRequestHandler bidRequestHandler, WinHandler winHandler,
                     TrackingHandler trackingHandler, int maxBodySize,
                     MetricsRegistry metricsRegistry, CompositeHealthCheck healthCheck,
                     ObjectMapper objectMapper) {
        this.bidRequestHandler = bidRequestHandler;
        this.winHandler = winHandler;
        this.trackingHandler = trackingHandler;
        this.maxBodySize = maxBodySize;
        this.metricsRegistry = metricsRegistry;
        this.healthCheck = healthCheck;
        this.objectMapper = objectMapper;
    }

    public Router createRouter(Vertx vertx) {
        Router router = Router.router(vertx);

        router.post().handler(BodyHandler.create().setBodyLimit(maxBodySize));

        router.post("/bid").handler(bidRequestHandler);
        router.post("/win").handler(winHandler);
        router.get("/impression").handler(trackingHandler::handleImpression);
        router.get("/click").handler(trackingHandler::handleClick);
        router.get("/health").handler(ctx -> handleHealth(ctx, vertx));
        router.get("/metrics").handler(this::handleMetrics);
        router.get("/api-docs").handler(this::handleApiDocs);
        router.get("/docs").handler(this::handleSwaggerUi);

        logger.info("Routes configured: /bid, /win, /impression, /click, /health, /metrics, /docs");
        return router;
    }

    /** Health checks run on a worker thread — never blocks the event loop. */
    private void handleHealth(RoutingContext ctx, Vertx vertx) {
        vertx.executeBlocking(() -> {
            return healthCheck.check();
        }).onSuccess(result -> {
            try {
                boolean healthy = "UP".equals(result.get("status"));
                String json = objectMapper.writeValueAsString(result);
                ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(healthy ? 200 : 503)
                        .end(json);
            } catch (Exception e) {
                ctx.response().setStatusCode(503)
                        .putHeader("Content-Type", "application/json")
                        .end("{\"status\":\"DOWN\"}");
            }
        }).onFailure(err -> {
            ctx.response().setStatusCode(503)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"status\":\"DOWN\"}");
        });
    }

    private void handleMetrics(RoutingContext ctx) {
        ctx.response()
                .putHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                .setStatusCode(200)
                .end(metricsRegistry.scrape());
    }

    private void handleApiDocs(RoutingContext ctx) {
        ctx.response()
                .putHeader("Content-Type", "application/x-yaml")
                .sendFile("openapi.yaml");
    }

    private void handleSwaggerUi(RoutingContext ctx) {
        ctx.response()
                .putHeader("Content-Type", "text/html")
                .sendFile("swagger-ui.html");
    }
}

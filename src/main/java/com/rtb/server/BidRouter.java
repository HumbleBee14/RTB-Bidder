package com.rtb.server;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures all HTTP routes for the RTB bidder.
 */
public final class BidRouter {

    private static final Logger logger = LoggerFactory.getLogger(BidRouter.class);

    private final BidRequestHandler bidRequestHandler;
    private final WinHandler winHandler;
    private final TrackingHandler trackingHandler;
    private final int maxBodySize;

    public BidRouter(BidRequestHandler bidRequestHandler,
                     WinHandler winHandler,
                     TrackingHandler trackingHandler,
                     int maxBodySize) {
        this.bidRequestHandler = bidRequestHandler;
        this.winHandler = winHandler;
        this.trackingHandler = trackingHandler;
        this.maxBodySize = maxBodySize;
    }

    public Router createRouter(Vertx vertx) {
        Router router = Router.router(vertx);

        router.post().handler(BodyHandler.create().setBodyLimit(maxBodySize));

        router.post("/bid").handler(bidRequestHandler);
        router.post("/win").handler(winHandler);
        router.get("/impression").handler(trackingHandler::handleImpression);
        router.get("/click").handler(trackingHandler::handleClick);
        router.get("/health").handler(this::handleHealth);
        router.get("/metrics").handler(this::handleMetrics);
        router.get("/api-docs").handler(this::handleApiDocs);
        router.get("/docs").handler(this::handleSwaggerUi);

        logger.info("Routes configured: /bid, /win, /impression, /click, /health, /metrics, /docs");
        return router;
    }

    private void handleHealth(RoutingContext ctx) {
        ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end("{\"status\":\"UP\"}");
    }

    private void handleMetrics(RoutingContext ctx) {
        ctx.response()
                .putHeader("Content-Type", "text/plain; charset=utf-8")
                .setStatusCode(200)
                .end("# Metrics not yet configured\n");
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

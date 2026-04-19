package com.rtb;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.rtb.config.AppConfig;
import com.rtb.server.BidRequestHandler;
import com.rtb.server.BidRouter;
import com.rtb.server.HttpServer;
import com.rtb.server.TrackingHandler;
import com.rtb.server.WinHandler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Composition root — wires dependencies and starts the server. */
public final class Application {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        int port = config.getInt("server.port", 8080);
        int maxBodySize = config.getInt("server.body.maxSize", 65536);

        ObjectMapper objectMapper = createObjectMapper();

        BidRequestHandler bidRequestHandler = new BidRequestHandler(objectMapper);
        WinHandler winHandler = new WinHandler(objectMapper);
        TrackingHandler trackingHandler = new TrackingHandler();
        BidRouter bidRouter = new BidRouter(bidRequestHandler, winHandler, trackingHandler, maxBodySize);

        Vertx vertx = Vertx.vertx(new VertxOptions());
        HttpServer httpServer = new HttpServer(vertx, bidRouter, port);

        httpServer.start()
                .onSuccess(server -> logger.info("RTB Bidder started on port {}", server.actualPort()))
                .onFailure(err -> {
                    logger.error("Failed to start RTB Bidder", err);
                    vertx.close();
                });
    }

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}

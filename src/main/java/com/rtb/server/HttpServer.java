package com.rtb.server;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Vert.x HTTP server lifecycle. */
public final class HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private final Vertx vertx;
    private final BidRouter bidRouter;
    private final int port;

    public HttpServer(Vertx vertx, BidRouter bidRouter, int port) {
        this.vertx = vertx;
        this.bidRouter = bidRouter;
        this.port = port;
    }

    public Future<io.vertx.core.http.HttpServer> start() {
        return vertx.createHttpServer()
                .requestHandler(bidRouter.createRouter(vertx))
                .listen(port);
    }
}

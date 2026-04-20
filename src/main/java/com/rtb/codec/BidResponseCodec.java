package com.rtb.codec;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.rtb.model.BidResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Low-allocation JSON writer for BidResponse using Jackson Streaming API.
 *
 * Uses a ThreadLocal ByteArrayOutputStream to avoid allocating a new buffer per response.
 * At 50K QPS, pooling the buffer saves 50K byte array allocations/sec.
 */
public final class BidResponseCodec {

    private final JsonFactory jsonFactory;

    // ThreadLocal BAOS — reused per thread, reset between calls, zero allocation after warmup
    private static final ThreadLocal<ByteArrayOutputStream> BUFFER = ThreadLocal.withInitial(
            () -> new ByteArrayOutputStream(512));

    public BidResponseCodec(JsonFactory jsonFactory) {
        this.jsonFactory = jsonFactory;
    }

    public byte[] encode(BidResponse response) throws IOException {
        ByteArrayOutputStream baos = BUFFER.get();
        baos.reset();
        try (JsonGenerator gen = jsonFactory.createGenerator(baos)) {
            gen.writeStartObject();
            gen.writeArrayFieldStart("bids");
            for (BidResponse.SlotBid bid : response.bids()) {
                writeSlotBid(gen, bid);
            }
            gen.writeEndArray();
            gen.writeEndObject();
        }
        return baos.toByteArray();
    }

    private void writeSlotBid(JsonGenerator gen, BidResponse.SlotBid bid) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("bid_id", bid.bidId());
        gen.writeStringField("slot_id", bid.slotId());
        gen.writeStringField("ad_id", bid.adId());
        gen.writeNumberField("price", bid.price());
        gen.writeNumberField("width", bid.width());
        gen.writeNumberField("height", bid.height());
        gen.writeStringField("creative_url", bid.creativeUrl());

        gen.writeObjectFieldStart("tracking_urls");
        gen.writeStringField("impression_url", bid.trackingUrls().impressionUrl());
        gen.writeStringField("click_url", bid.trackingUrls().clickUrl());
        gen.writeEndObject();

        gen.writeStringField("advertiser_domain", bid.advertiserDomain());
        gen.writeEndObject();
    }
}

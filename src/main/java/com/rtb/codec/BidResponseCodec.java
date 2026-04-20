package com.rtb.codec;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.rtb.model.BidResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Zero-allocation JSON writer for BidResponse using Jackson Streaming API.
 *
 * ObjectMapper.writeValueAsString() creates an intermediate String (~2 allocations per field).
 * This codec writes directly to a byte buffer via JsonGenerator — no intermediate String,
 * no reflection, no annotation processing.
 */
public final class BidResponseCodec {

    private final JsonFactory jsonFactory;

    public BidResponseCodec(JsonFactory jsonFactory) {
        this.jsonFactory = jsonFactory;
    }

    public byte[] encode(BidResponse response) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
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

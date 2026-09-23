package com.match3d.common;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;


/**
 * Events to JSON bytes and back, for the message queue. Fails with
 * UncheckedIOException, since a bad message is not something a caller can fix.
 */
public final class EventJson {

    private static final ObjectMapper MAPPER = createMapper();

    private EventJson() {}

    private static ObjectMapper createMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static byte[] toBytes(Object event) {
        try {
            return MAPPER.writeValueAsBytes(event);
        } catch (IOException e) {
            throw new UncheckedIOException("Error serializing event to JSON", e);
        }
    }

    public static <T> T fromBytes(byte[] bytes, Class<T> type) {
        try {
            return MAPPER.readValue(bytes, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Error deserializing JSON to event", e);
        }
    }
}
package com.github.delegacy.youngbot.server.util;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class JacksonUtils {
    private static final ObjectMapper OM = new ObjectMapper();

    public static String serialize(Object obj) {
        return serialize(OM, obj);
    }

    public static String serialize(ObjectMapper om, Object obj) {
        try {
            return om.writeValueAsString(obj);
        } catch (IOException e) {
            log.warn("Failed to serialize the obj<{}> with om<{}>", obj, om, e);
            throw new IllegalArgumentException("Failed to serialize the obj");
        }
    }

    public static <T> T deserialize(String str, Class<T> clazz) {
        return deserialize(OM, str, clazz);
    }

    public static <T> T deserialize(ObjectMapper om, String str, Class<T> clazz) {
        try {
            return om.readValue(str, clazz);
        } catch (IOException e) {
            log.warn("Failed to deserialize the str<{}> with om<{}>", str, om, e);
            throw new IllegalArgumentException("Failed to deserialize the str");
        }
    }

    public static <T> T deserialize(byte[] bytes, Class<T> clazz) {
        return deserialize(OM, bytes, clazz);
    }

    public static <T> T deserialize(ObjectMapper om, byte[] bytes, Class<T> clazz) {
        try {
            return om.readValue(bytes, clazz);
        } catch (IOException e) {
            log.warn("Failed to deserialize the bytes<{}> with om<{}>", bytes, om, e);
            throw new IllegalArgumentException("Failed to deserialize the bytes");
        }
    }

    private JacksonUtils() {
        // do nothing
    }
}
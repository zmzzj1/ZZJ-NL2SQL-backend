package com.queryforge.domain.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** JSON 数组 <-> List<Long> 的轻量转换（候选/回退链）。 */
public final class JsonIds {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonIds() {
    }

    public static List<Long> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Long> ids = MAPPER.readValue(json, new TypeReference<List<Long>>() {
            });
            return ids == null ? List.of() : ids;
        } catch (Exception ex) {
            return List.of();
        }
    }

    public static String write(List<Long> ids) {
        if (ids == null) {
            return "[]";
        }
        return ids.toString();
    }

    public static List<Double> parseWeights(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Double> weights = MAPPER.readValue(json, new TypeReference<List<Double>>() {
            });
            return weights == null ? new ArrayList<>() : weights;
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }
}

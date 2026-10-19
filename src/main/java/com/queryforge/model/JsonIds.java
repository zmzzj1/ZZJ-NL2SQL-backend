package com.queryforge.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** JSON 数组 <-> List<Long> 的轻量转换（候选/回退链）。 */
final class JsonIds {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonIds() {
    }

    static List<Long> parse(String json) {
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

    static String write(List<Long> ids) {
        if (ids == null) {
            return "[]";
        }
        return ids.toString();
    }

    static List<Double> parseWeights(String json) {
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

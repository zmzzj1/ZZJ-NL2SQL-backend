package com.queryforge.application.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON Schema 参数校验：仅覆盖 {@code type/object/required/properties/enum/additionalProperties}
 * 中本项目需要的部分。未知扩展字段不默认开启特权。校验在工具请求发出前进行，失败属于
 * 确定的、尚无副作用的参数错误，可进入受控修复。
 */
public final class McpToolArgumentValidator {

    private McpToolArgumentValidator() {
    }

    /** 返回参数错误列表；空列表表示通过。 */
    @SuppressWarnings("unchecked")
    public static List<String> validate(Map<String, Object> schema, Map<String, Object> args) {
        List<String> errors = new ArrayList<>();
        if (schema == null || schema.isEmpty()) {
            return errors;
        }
        Map<String, Object> arguments = args == null ? Map.of() : args;
        Object type = schema.get("type");
        if (type instanceof String typeStr && !"object".equals(typeStr)) {
            errors.add("期望参数为 object 类型，实际为 " + typeStr);
            return errors;
        }
        Object requiredObj = schema.get("required");
        if (requiredObj instanceof List<?> requiredList) {
            for (Object name : requiredList) {
                if (name == null) {
                    continue;
                }
                if (!arguments.containsKey(String.valueOf(name)) || arguments.get(String.valueOf(name)) == null) {
                    errors.add("缺少必填参数：" + name);
                }
            }
        }
        Object propsObj = schema.get("properties");
        Map<String, Object> properties = propsObj instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = entry.getKey();
            Object propSchema = properties.get(key);
            if (propSchema == null) {
                Object additional = schema.get("additionalProperties");
                if (Boolean.FALSE.equals(additional)) {
                    errors.add("不允许的额外参数：" + key);
                }
                continue;
            }
            if (!(propSchema instanceof Map<?, ?> propMap)) {
                continue;
            }
            Object expectedType = ((Map<String, Object>) propMap).get("type");
            if (expectedType instanceof String typeStr) {
                if (!typeMatches(typeStr, entry.getValue())) {
                    errors.add("参数 " + key + " 类型应为 " + typeStr);
                }
            }
            Object enumObj = ((Map<String, Object>) propMap).get("enum");
            if (enumObj instanceof List<?> enumList && entry.getValue() != null) {
                boolean inEnum = enumList.stream().anyMatch(e -> String.valueOf(e).equals(String.valueOf(entry.getValue())));
                if (!inEnum) {
                    errors.add("参数 " + key + " 取值不在允许范围");
                }
            }
        }
        return errors;
    }

    private static boolean typeMatches(String expected, Object value) {
        if (value == null) {
            return true; // 必填由 required 负责
        }
        return switch (expected) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n && n.longValue() == n.doubleValue();
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
    }
}

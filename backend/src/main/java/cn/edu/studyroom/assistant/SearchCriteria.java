package cn.edu.studyroom.assistant;

import cn.edu.studyroom.api.Api;
import cn.edu.studyroom.domain.Entities.Seat;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.*;

/** Immutable conditions; a validated batch produces a new value, never a partial update. */
public record SearchCriteria(Map<String, Condition> fields) {
    public record Condition(Object value, String strength) {}
    private static final Set<String> FIELDS = Set.of("startTime", "endTime", "roomId", "seatType", "hasPower", "isQuiet");
    public SearchCriteria { fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields)); }
    public static SearchCriteria empty() { return new SearchCriteria(Map.of()); }
    public SearchCriteria merge(JsonNode changes) {
        Api.require(changes != null && changes.isArray() && changes.size() <= 6, 400, "INVALID_PARAMETER", "changes须为最多6项的数组");
        var next = new LinkedHashMap<>(fields);
        var seen = new HashSet<String>();
        for (var change : changes) {
            Api.require(change.isObject(), 400, "INVALID_PARAMETER", "条件变更须为对象");
            change.fieldNames().forEachRemaining(k -> Api.require(Set.of("field", "operation", "value", "strength").contains(k), 400, "INVALID_PARAMETER", "条件包含未知字段"));
            String field = change.path("field").asText(), operation = change.path("operation").asText();
            Api.require(FIELDS.contains(field) && seen.add(field), 400, "INVALID_PARAMETER", "条件字段无效或重复");
            if (operation.equals("CLEAR")) {
                Api.require(!change.has("value") && !change.has("strength"), 400, "INVALID_PARAMETER", "CLEAR不可包含value或strength");
                next.remove(field);
                continue;
            }
            Api.require(operation.equals("SET") && change.hasNonNull("value"), 400, "INVALID_PARAMETER", "SET须提供value");
            var value = change.get("value"); Object parsed;
            boolean time = field.equals("startTime") || field.equals("endTime");
            String strength = time ? "HARD" : change.path("strength").asText();
            Api.require(time ? !change.has("strength") : Set.of("HARD", "SOFT").contains(strength), 400, "INVALID_PARAMETER", "条件强度无效");
            try {
                parsed = switch (field) {
                    case "startTime", "endTime" -> {
                        Api.require(value.isTextual(),400,"INVALID_PARAMETER","时间须为字符串");
                        var t = OffsetDateTime.parse(value.textValue());
                        Api.require(t.getOffset().getTotalSeconds() == 8 * 3600,400,"INVALID_TIME","须使用北京时间+08:00");
                        yield t.toString();
                    }
                    case "roomId" -> {
                        Api.require(value.isTextual() && value.textValue().matches("[1-9][0-9]{0,18}"),400,"INVALID_PARAMETER","roomId格式不正确");
                        yield Long.toString(Long.parseLong(value.textValue()));
                    }
                    case "seatType" -> {
                        Api.require(value.isTextual() && Set.of("WINDOW","AISLE","MIDDLE").contains(value.textValue()),400,"INVALID_PARAMETER","座位类型不正确");
                        yield value.textValue();
                    }
                    default -> { Api.require(value.isBoolean(),400,"INVALID_PARAMETER","插座与安静区须为布尔值"); yield value.booleanValue(); }
                };
            } catch (java.time.format.DateTimeParseException | NumberFormatException ex) {
                throw new Api.Failure(400,"INVALID_PARAMETER","条件值格式不正确");
            }
            next.put(field, new Condition(parsed, strength));
        }
        return new SearchCriteria(next);
    }
    public OffsetDateTime start() { return time("startTime"); }
    public OffsetDateTime end() { return time("endTime"); }
    private OffsetDateTime time(String key) { return fields.containsKey(key) ? OffsetDateTime.parse(fields.get(key).value.toString()) : null; }
    public boolean matches(Seat seat, String field) {
        var condition = fields.get(field);
        return condition == null || Objects.equals(condition.value, switch (field) {
            case "roomId" -> seat.getRoomId().toString(); case "seatType" -> seat.getSeatType();
            case "hasPower" -> seat.getHasPower(); case "isQuiet" -> seat.getIsQuiet(); default -> null;
        });
    }
    public boolean allows(Seat seat) {
        return fields.entrySet().stream().filter(e -> !e.getKey().endsWith("Time") && e.getValue().strength.equals("HARD"))
            .allMatch(e -> matches(seat, e.getKey()));
    }
    public boolean hasPreferences() { return fields.keySet().stream().anyMatch(k -> !k.endsWith("Time")); }
    public Map<String,Condition> view() { return fields; }
}

package io.github.forest611.mcptest.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.forest611.mcptest.mcp.Args;
import io.github.forest611.mcptest.mcp.Schema;
import io.github.forest611.mcptest.mcp.ToolException;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Selects events by type, by a partial match on their fields, and by a regex on their text. */
public final class EventFilter {
    private final Set<String> types;
    private final JsonObject match;
    private final Pattern pattern;
    private final Predicate<EventLog.Event> extra;

    private EventFilter(Set<String> types, JsonObject match, Pattern pattern, Predicate<EventLog.Event> extra) {
        this.types = types;
        this.match = match;
        this.pattern = pattern;
        this.extra = extra;
    }

    public static Schema describe(Schema schema) {
        return schema
                .add("types", Schema.typed("array", "Event types to include, e.g. [\"block_change\"]. Omit for all types."), false)
                .obj("match", "Partial match on event fields: every key given must be present with an equal value "
                        + "(nested objects match partially, arrays exactly). "
                        + "E.g. {\"pos\":[1,64,2],\"new\":{\"id\":\"minecraft:air\"}} or {\"entity\":{\"type\":\"minecraft:item_display\"}}.")
                .str("pattern", "Java regex searched (find, not full match) in the event's 'text' field, "
                        + "or in the whole event JSON when it has no text.");
    }

    public static EventFilter fromArgs(Args args) throws ToolException {
        Set<String> types = new HashSet<>(args.stringList("types"));
        return new EventFilter(types, args.object("match"), compile(args.string("pattern", null)), null);
    }

    public static EventFilter ofTypes(Set<String> types, Pattern pattern) {
        return new EventFilter(types, null, pattern, null);
    }

    public EventFilter and(Predicate<EventLog.Event> condition) {
        return new EventFilter(types, match, pattern, extra == null ? condition : extra.and(condition));
    }

    public static Pattern compile(String regex) throws ToolException {
        if (regex == null) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw ToolException.invalidArgs("invalid regex: " + e.getMessage());
        }
    }

    public boolean test(EventLog.Event event) {
        if (!types.isEmpty() && !types.contains(event.type())) {
            return false;
        }
        if (match != null && !contains(event.data(), match)) {
            return false;
        }
        if (extra != null && !extra.test(event)) {
            return false;
        }
        if (pattern != null) {
            JsonElement text = event.data().get("text");
            String haystack = text != null && text.isJsonPrimitive() ? text.getAsString() : event.data().toString();
            return pattern.matcher(haystack).find();
        }
        return true;
    }

    private static boolean contains(JsonElement actual, JsonElement expected) {
        if (actual == null) {
            return expected.isJsonNull();
        }
        if (expected.isJsonObject()) {
            if (!actual.isJsonObject()) {
                return false;
            }
            for (Map.Entry<String, JsonElement> entry : expected.getAsJsonObject().entrySet()) {
                if (!contains(actual.getAsJsonObject().get(entry.getKey()), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isJsonArray()) {
            if (!actual.isJsonArray()) {
                return false;
            }
            JsonArray a = actual.getAsJsonArray();
            JsonArray e = expected.getAsJsonArray();
            if (a.size() != e.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!contains(a.get(i), e.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return actual.equals(expected);
    }
}

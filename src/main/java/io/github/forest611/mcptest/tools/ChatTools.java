package io.github.forest611.mcptest.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.forest611.mcptest.McpTestMod;
import io.github.forest611.mcptest.event.EventFilter;
import io.github.forest611.mcptest.event.EventLog;
import io.github.forest611.mcptest.mcp.Args;
import io.github.forest611.mcptest.mcp.Schema;
import io.github.forest611.mcptest.mcp.ToolException;
import io.github.forest611.mcptest.mcp.ToolRegistry;
import io.github.forest611.mcptest.util.Client;
import net.minecraft.util.StringUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class ChatTools {
    private static final String KINDS = "chat (player chat), system (plugin and server messages), action_bar, title, subtitle";

    private ChatTools() {
    }

    static void register(ToolRegistry registry) {
        registry.registerInstant("send_chat",
                "Sends a chat message as the player. A message starting with '/' is sent as a command, as in the chat box.",
                Schema.of().reqStr("message", "Text to send (max 256 characters)"),
                args -> send(args.requireString("message"), false));

        registry.registerInstant("send_command",
                "Runs a command as the player (not the console). The leading '/' is optional. "
                        + "Replies arrive as messages: follow with wait_message using the returned event_seq as after_seq.",
                Schema.of().reqStr("command", "Command line, e.g. \"time query daytime\""),
                args -> send(args.requireString("command"), true));

        registry.registerInstant("messages",
                "Messages the client received, oldest first, with formatting stripped. Kinds: " + KINDS + ".",
                Schema.of()
                        .integer("after_seq", "Only messages with a larger seq (default 0 = everything still in memory)")
                        .add("kinds", Schema.typed("array", "Message kinds to include (default all)"), false)
                        .str("pattern", "Java regex searched in the text")
                        .integer("limit", "Maximum number of messages, keeping the newest (default 100)"),
                ChatTools::messages);

        registry.register("wait_message",
                "Waits for a message whose text matches a regex and returns it. On timeout the error lists the messages that "
                        + "did arrive. Messages after 'after_seq' but before this call also count.",
                Schema.of()
                        .reqStr("pattern", "Java regex searched (find) in the message text; quote literal text with \\\\Q...\\\\E if needed")
                        .add("kinds", Schema.typed("array", "Message kinds to consider (default all): " + KINDS), false)
                        .integer("after_seq", "Consider messages with a larger seq (default: only messages from now on)")
                        .integer("timeout_ticks", "Give up after this many ticks (default 100 = 5 s)"),
                args -> new EventTools.WaitEventOp(messageFilter(args, true), args, true));
    }

    private static JsonObject send(String text, boolean command) throws ToolException {
        String normalized = StringUtil.trimChatMessage(StringUtils.normalizeSpace(text.trim()));
        boolean asCommand = command || normalized.startsWith("/");
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()) {
            throw ToolException.invalidArgs("nothing to send");
        }
        if (asCommand) {
            Client.player().connection.sendCommand(normalized);
        } else {
            Client.player().connection.sendChat(normalized);
        }
        JsonObject result = new JsonObject();
        result.addProperty("sent", (asCommand ? "/" : "") + normalized);
        return result;
    }

    private static EventFilter messageFilter(Args args, boolean patternRequired) throws ToolException {
        Pattern pattern = EventFilter.compile(patternRequired ? args.requireString("pattern") : args.string("pattern", null));
        Set<String> kinds = new HashSet<>(args.stringList("kinds"));
        EventFilter base = EventFilter.ofTypes(Set.of("message"), pattern);
        return kinds.isEmpty() ? base : base.and(event -> kinds.contains(event.data().get("kind").getAsString()));
    }

    private static JsonObject messages(Args args) throws ToolException {
        EventFilter filter = messageFilter(args, false);
        int limit = Math.max(1, args.integer("limit", 100));
        EventLog log = McpTestMod.events();
        JsonArray array = new JsonArray();
        for (EventLog.Event event : log.after(args.longValue("after_seq", 0))) {
            if (filter.test(event)) {
                array.add(event.toJson());
            }
        }
        boolean truncated = array.size() > limit;
        while (array.size() > limit) {
            array.remove(0);
        }
        JsonObject result = new JsonObject();
        result.add("messages", array);
        result.addProperty("count", array.size());
        result.addProperty("truncated", truncated);
        result.addProperty("last_seq", log.lastSeq());
        return result;
    }
}

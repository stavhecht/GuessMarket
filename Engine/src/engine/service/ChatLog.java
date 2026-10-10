package engine.service;

import engine.dto.ChatMessage;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every event's chat: who said what, in the order the engine accepted it.
 *
 * <p>Bookkeeping only, like {@code OrderBook}: it numbers and keeps messages and answers
 * "what came after this one". Whether a message may be posted at all (the event exists,
 * the author is a user, the text is a line worth keeping) is {@link MarketEngine}'s to
 * decide before anything reaches here.
 *
 * <p>Deliberately not part of {@code EventManager}: a chat is talk about the market, not
 * state of it, so it is not saved in a {@code .gm} session and does not change what
 * {@code serialVersionUID = 1L} means for one. Each event keeps its latest
 * {@link #KEPT_PER_EVENT} messages, so a server that runs for days does not grow without
 * bound; the ids keep rising regardless, so a client that asks for "after N" never
 * mistakes a dropped message for a new one.
 */
class ChatLog {

    /** How many of an event's messages are kept; older ones fall off the front. */
    static final int KEPT_PER_EVENT = 500;

    private final Map<Integer, Deque<ChatMessage>> byEvent = new HashMap<>();
    private long lastId;

    /** Adds one message, already validated, and stamps its id and time. */
    ChatMessage post(int eventId, String author, String text) {
        ChatMessage message = new ChatMessage(++lastId, eventId, author, text, Instant.now());
        Deque<ChatMessage> kept = byEvent.computeIfAbsent(eventId, id -> new ArrayDeque<>());
        kept.addLast(message);
        while (kept.size() > KEPT_PER_EVENT) {
            kept.removeFirst();
        }
        return message;
    }

    /** The event's messages with an id above {@code afterId}, oldest first; all of them for 0. */
    List<ChatMessage> after(int eventId, long afterId) {
        List<ChatMessage> newer = new ArrayList<>();
        for (ChatMessage message : byEvent.getOrDefault(eventId, new ArrayDeque<>())) {
            if (message.id() > afterId) {
                newer.add(message);
            }
        }
        return newer;
    }

    /** Forgets every chat, for a market replaced by another whose event ids mean other events. */
    void clear() {
        byEvent.clear();
    }
}
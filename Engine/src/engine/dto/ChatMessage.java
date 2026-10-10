package engine.dto;

import java.time.Instant;

/**
 * One line of an event's chat, as everyone in the market sees it.
 *
 * @param id     the message's place in the market's chat as a whole, rising across every
 *               event, so "everything after {@code id}" is how a client asks only for
 *               what it has not seen
 * @param author the user who posted it, as the engine knows them, never as the poster
 *               typed it
 * @param sentAt when the engine accepted it, which is the order everyone reads it in
 */
public record ChatMessage(long id, int eventId, String author, String text, Instant sentAt) {
}
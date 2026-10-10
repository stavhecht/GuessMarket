package server;

import api.ChatRequest;
import api.Endpoints;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * {@code /api/events/chat}: an event's chat, kept on the server so everyone connected reads
 * the same conversation. GET {@code ?id=&after=} answers the messages after the last one the
 * client has seen ({@code after} left out means all of them); POST adds one, as the
 * logged-in user.
 *
 * <p>Whether a message is acceptable (the event exists, the text is a single line of a
 * sensible length) is the engine's, as every other rule is; this servlet only sees that the
 * fields were sent.
 */
@WebServlet(name = "chat", urlPatterns = Endpoints.CHAT)
public class ChatServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        read(req, resp, (engine, user) -> engine.getChatMessages(intParam(req, Endpoints.PARAM_ID),
                req.getParameter(Endpoints.PARAM_AFTER) == null ? 0 : longParam(req, Endpoints.PARAM_AFTER)));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        write(req, resp, (engine, user) -> {
            ChatRequest message = body(req, ChatRequest.class);
            return engine.postChatMessage(user, required(message.eventId(), "eventId"),
                    required(message.text(), "text"));
        });
    }
}

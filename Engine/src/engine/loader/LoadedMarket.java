package engine.loader;

import engine.model.Event;
import engine.model.User;

import java.util.List;

/**
 * Everything one events file describes: its events and its users, both already checked
 * and cross-referenced by {@link XmlEventLoader}.
 *
 * <p>It exists because a file now says two things rather than one, and the two are only
 * valid together: a user is Market Maker for an event id, which has to be an event in
 * the same list. Returning them as a pair keeps that check inside the loader, where the
 * file is still the subject.
 *
 * <p>Not a DTO: it is the loader's output, not the UI's input, and holds model objects
 * rather than views.
 *
 * @param events the events in file order
 * @param users  the users in file order
 */
public record LoadedMarket(List<Event> events, List<User> users) {

    public LoadedMarket {
        events = List.copyOf(events);
        users = List.copyOf(users);
    }
}
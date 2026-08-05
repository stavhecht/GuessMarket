package engine.service;

import engine.exception.EventNotFoundException;
import engine.model.Event;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns the loaded event collection. A new file replaces everything that came before. */
public class EventManager {

    // LinkedHashMap so the UI lists events in file order rather than hash order.
    private final Map<Integer, Event> events = new LinkedHashMap<>();

    /** Replaces the current events wholesale — all market state from the previous file is dropped. */
    public void loadEvents(List<Event> newEvents) {
        events.clear();
        for (Event event : newEvents) {
            events.put(event.getId(), event);
        }
    }

    public Event getEvent(int id) {
        Event event = events.get(id);
        if (event == null) {
            throw new EventNotFoundException("There is no event with id " + id + ".");
        }
        return event;
    }

    public Collection<Event> getAllEvents() {
        return Collections.unmodifiableCollection(events.values());
    }

    /** Seeds every event account with b·ln2 so payouts are always covered. Call once, right after loading. */
    public void applyInitialSubsidies(LmsrCalculator calculator) {
        for (Event event : events.values()) {
            event.getMMAccount().deposit(calculator.initialSubsidy(event.getB()));
        }
    }
}

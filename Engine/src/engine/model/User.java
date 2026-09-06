package engine.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A participant: their cash, the shares they hold in each event, and the events they run
 * as Market Maker.
 *
 * <p>Money is held in two figures rather than one. {@link #getBalance()} is what the user
 * owns; {@link #getReservedCash()} is the part of it already promised to orders resting in
 * an order book. Only the difference, {@link #getAvailableCash()}, may be committed to
 * something new, which is what makes a resting order always executable: the money for it
 * was set aside when it was placed and cannot be spent twice.
 *
 * <p>Shares work the same way: a sell order resting in the book locks the shares it offers,
 * so the same share cannot be sold to two buyers.
 *
 * <p>Nothing here decides anything. Every method is a movement that a caller has already
 * checked it can afford; {@code engine.service} owns the rules, as it does for {@link Event}.
 */
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String name;
    private final int initialCash;

    /**
     * Not final, because an event can be created after this user was: see
     * {@link #addMarketMakerEvent}. The declared type is unchanged, so this class still
     * deserialises every {@code .gm} file written before it could grow.
     */
    private List<Integer> marketMakerEventIds;

    private double balance;
    private double reservedCash;

    // Event id to the shares held per option. LinkedHashMap so "the events this user takes
    // part in" lists in the order they first traded in one.
    private final Map<Integer, long[]> shares = new LinkedHashMap<>();
    private final Map<Integer, long[]> lockedShares = new LinkedHashMap<>();

    public User(String name, int initialCash, List<Integer> marketMakerEventIds) {
        this.name = name;
        this.initialCash = initialCash;
        this.marketMakerEventIds = List.copyOf(marketMakerEventIds);
        this.balance = initialCash;
    }

    public String getName() {
        return name;
    }

    /** What the file gave them, kept for reference; {@link #getBalance()} is the live figure. */
    public int getInitialCash() {
        return initialCash;
    }

    /** The ids of the events this user is Market Maker for; empty if they run none. */
    public List<Integer> getMarketMakerEventIds() {
        return Collections.unmodifiableList(marketMakerEventIds);
    }

    public boolean isMarketMakerOf(int eventId) {
        return marketMakerEventIds.contains(eventId);
    }

    /**
     * Makes this user the Market Maker of one more event: the one they have just created.
     *
     * <p>Copy-on-write rather than an {@code add}, and that is not a style choice: a session
     * saved before this method existed deserialises the immutable list the constructor used
     * to make, and adding to it would throw. Replacing it works whatever the list arrived as.
     *
     * <p>The engine has already checked that the event exists and that nobody else runs it;
     * this class holds state, not rules, exactly as it does for money.
     */
    public void addMarketMakerEvent(int eventId) {
        List<Integer> updated = new ArrayList<>(marketMakerEventIds);
        updated.add(eventId);
        marketMakerEventIds = List.copyOf(updated);
    }

    // --- money ---

    public double getBalance() {
        return balance;
    }

    /** The part of the balance already promised to resting buy orders. */
    public double getReservedCash() {
        return reservedCash;
    }

    /** What may still be committed to something new. */
    public double getAvailableCash() {
        return balance - reservedCash;
    }

    public void deposit(double amount) {
        balance += amount;
    }

    /** Money out, from the free part of the balance: a purchase made on the spot. */
    public void withdraw(double amount) {
        balance -= amount;
    }

    /** Sets money aside for an order that is about to rest in the book. */
    public void reserve(double amount) {
        reservedCash += amount;
    }

    /** Gives money back that an order no longer needs: it filled cheaply, or was cancelled. */
    public void release(double amount) {
        reservedCash -= amount;
    }

    /**
     * Pays out of money that was already reserved: the reservation and the balance both
     * fall, so the available cash is unchanged: it was never available to begin with.
     */
    public void spendReserved(double amount) {
        reservedCash -= amount;
        balance -= amount;
    }

    // --- shares ---

    /** The events this user holds shares in, in the order they first did. */
    public Set<Integer> getEventIds() {
        return Collections.unmodifiableSet(shares.keySet());
    }

    public long getShares(int eventId, int optionIndex) {
        long[] held = shares.get(eventId);
        return held == null ? 0L : held[optionIndex];
    }

    /** Shares committed to a resting sell order, and so not sellable again. */
    public long getLockedShares(int eventId, int optionIndex) {
        long[] locked = lockedShares.get(eventId);
        return locked == null ? 0L : locked[optionIndex];
    }

    public long getAvailableShares(int eventId, int optionIndex) {
        return getShares(eventId, optionIndex) - getLockedShares(eventId, optionIndex);
    }

    public void addShares(int eventId, int optionIndex, long amount) {
        countsFor(shares, eventId)[optionIndex] += amount;
    }

    public void removeShares(int eventId, int optionIndex, long amount) {
        countsFor(shares, eventId)[optionIndex] -= amount;
    }

    public void lockShares(int eventId, int optionIndex, long amount) {
        countsFor(lockedShares, eventId)[optionIndex] += amount;
    }

    public void unlockShares(int eventId, int optionIndex, long amount) {
        countsFor(lockedShares, eventId)[optionIndex] -= amount;
    }

    /** Hands over shares a resting sell order had locked: both counts fall together. */
    public void deliverLockedShares(int eventId, int optionIndex, long amount) {
        removeShares(eventId, optionIndex, amount);
        unlockShares(eventId, optionIndex, amount);
    }

    private static long[] countsFor(Map<Integer, long[]> counts, int eventId) {
        return counts.computeIfAbsent(eventId, id -> new long[Event.OPTION_COUNT]);
    }
}

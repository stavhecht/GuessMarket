package engine.service;

import engine.model.BookTrade;
import engine.model.Order;

import java.util.List;

/**
 * What became of one submitted order: the order itself — whose {@code remaining} says how
 * much of it is now waiting in the book — and the trades it caused.
 *
 * @param fills the lines of history this order was party to, in the order they happened.
 *              For a mint only the submitter's own line appears here; the counterparty's
 *              line is in the book's history, since it belongs to their order, not this one.
 */
public record OrderOutcome(Order order, List<BookTrade> fills) {
}
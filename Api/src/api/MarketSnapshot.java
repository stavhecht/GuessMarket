package api;

import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.OrderBookStatusView;
import engine.dto.UserView;

import java.util.List;
import java.util.Map;

/**
 * Everything the screens read, taken in one go: the answer to {@code GET /api/market}.
 *
 * <p>The screens poll rather than subscribe, and a refresh reads the event list, every
 * event's status, the price history of the one charted, and every user. Asked for one
 * call at a time that is a request per event and, worse, a read lock per request, so the
 * users could reflect a trade the statuses did not. One response under one read lock is
 * one consistent view of the market, which is what a refresh is meant to be. The
 * per-call endpoints still exist, one for each {@link engine.service.Market} method; this
 * is the same data folded together.
 *
 * <p>Keyed by event id. An LMSR event appears in {@code lmsrStatuses} and
 * {@code priceHistories}, an order book in {@code bookStatuses}, never both.
 */
public record MarketSnapshot(List<EventView> events,
                             Map<Integer, EventStatusView> lmsrStatuses,
                             Map<Integer, OrderBookStatusView> bookStatuses,
                             Map<Integer, List<double[]>> priceHistories,
                             List<UserView> users) {
}

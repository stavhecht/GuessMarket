package engine.service;

import engine.model.Event;
import engine.model.Trade;
import java.util.PriorityQueue;

public class OrderBook {

    /**
     * Represents a single Buy or Sell limit order.
     */
    public static class Order {
        long orderId;
        String userId;
        boolean isBuy;
        double price;
        long quantity; // Using long to match Trade.shares()
        long timestamp;

        public Order(long orderId, String userId, boolean isBuy, double price, long quantity) {
            this.orderId = orderId;
            this.userId = userId;
            this.isBuy = isBuy;
            this.price = price;
            this.quantity = quantity;
            this.timestamp = System.nanoTime();
        }
    }

    private final Event event;
    private final String optionName;
    private final double d = 1.0;        //denominator

    // Max-heap for Bids (Buy orders: highest price first, then earliest timestamp)
    private final PriorityQueue<Order> bids = new PriorityQueue<>((o1, o2) -> {
        if (Double.compare(o2.price, o1.price) != 0) {
            return Double.compare(o2.price, o1.price);
        }
        return Long.compare(o1.timestamp, o2.timestamp);
    });

    // Min-heap for Asks (Sell orders: lowest price first, then earliest timestamp)
    private final PriorityQueue<Order> asks = new PriorityQueue<>((o1, o2) -> {
        if (Double.compare(o1.price, o2.price) != 0) {
            return Double.compare(o1.price, o2.price);
        }
        return Long.compare(o1.timestamp, o2.timestamp);
    });

    /**
     * Creates an OrderBook for a specific option within an event.
     *
     * @param event The aggregate root event managing state and trades.
     * @param optionIndex The index of the option this book trades (0 or 1).
     */
    public OrderBook(Event event, int optionIndex) {
        this.event = event;
        // Assuming the Option interface/class has a getName() method or similar identifier
        this.optionName = "Option_" + optionIndex;
    }

    public void placeOrder(Order order) {
        if (!event.isActive()) {
            throw new IllegalStateException("Cannot place orders: Event is no longer active.");
        }
        if (order.price <= 0 || order.price > d) {
            throw new IllegalArgumentException("Price must be between 0 and " + d);
        }

        if (order.isBuy) {
            matchBuyOrder(order);
        } else {
            matchSellOrder(order);
        }
    }

    private void matchBuyOrder(Order buyOrder) {
        while (buyOrder.quantity > 0 && !asks.isEmpty()) {
            Order bestAsk = asks.peek();
            if (bestAsk.price <= buyOrder.price) {
                long tradeQuantity = Math.min(buyOrder.quantity, bestAsk.quantity);
                double sharesCost = tradeQuantity * bestAsk.price;

                // Calculate commission based on Event's rate (simplified)
                double commission = sharesCost * event.getCommissionRate();

                // Record via aggregate root
                Trade trade = event.recordTrade(optionName, tradeQuantity, sharesCost, commission);

                buyOrder.quantity -= tradeQuantity;
                bestAsk.quantity -= tradeQuantity;

                if (bestAsk.quantity == 0) {
                    asks.poll();
                }
            } else {
                break;
            }
        }
        if (buyOrder.quantity > 0) {
            bids.add(buyOrder);
        }
    }

    private void matchSellOrder(Order sellOrder) {
        while (sellOrder.quantity > 0 && !bids.isEmpty()) {
            Order bestBid = bids.peek();
            if (bestBid.price >= sellOrder.price) {
                long tradeQuantity = Math.min(sellOrder.quantity, bestBid.quantity);
                double sharesCost = tradeQuantity * bestBid.price;

                // Calculate commission based on Event's rate (simplified)
                double commission = sharesCost * event.getCommissionRate();

                // Record via aggregate root
                Trade trade = event.recordTrade(optionName, tradeQuantity, sharesCost, commission);

                sellOrder.quantity -= tradeQuantity;
                bestBid.quantity -= tradeQuantity;

                if (bestBid.quantity == 0) {
                    bids.poll();
                }
            } else {
                break;
            }
        }
        if (sellOrder.quantity > 0) {
            asks.add(sellOrder);
        }
    }
}
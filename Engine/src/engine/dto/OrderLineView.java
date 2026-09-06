package engine.dto;

/** One order waiting in the book, as it appears on screen. */
public record OrderLineView(int sequence, String userName, double price, long remaining) {
}

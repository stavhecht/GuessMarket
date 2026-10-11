import type {
  BookTrade, CancelResult, EventStatusView, EventView, OptionBookView, OrderBookStatusView, OrderLineView,
  OrderResult, Trade, UserView,
} from './api/types';
import type { RemoteMarket } from './api/remoteMarket';
import { NONE, money, percent, price, shares } from './format';

/*
 * The arithmetic the screens do on top of the engine's DTOs: a port of ui.desktop.MarketData,
 * method for method. None of it is market logic: no price is invented here and no money
 * moves. Every figure is read straight off a DTO or is a sum of figures that were.
 */

/** What one winning share pays in an LMSR market; an order book has its own base value. */
const LMSR_SHARE_VALUE = 1.0;

/** One line of an event's participation log, whichever way the event trades. */
export interface Line {
  sequence: number;
  user: string;
  optionName: string;
  shares: number;
  price: number;
  commission: number;
  total: number;
}

/** One point of a price chart: both option prices, and when the trade that moved them happened. */
export interface PricePoint {
  prices: number[];
  at: string | null;
}

/** Where one user stands in one event. `invested` and `profit` are null for LMSR. */
export interface Position {
  eventId: number;
  eventName: string;
  owner: boolean;
  optionName: string;
  shares: number;
  invested: number | null;
  value: number;
  ifWins: number | null;
  status: string;
}

export function profit(position: Position): number | null {
  return position.invested === null ? null : position.value - position.invested;
}

// --- events ---

export function isLmsr(event: EventView): boolean {
  return event.tradingMethod === 'LMSR';
}

export function isActive(event: EventView): boolean {
  return event.status.toUpperCase() === 'ACTIVE';
}

/** Whether the acting user may seal this event: they are its Market Maker, and it is still open. */
export function canClose(market: RemoteMarket, event: EventView | null): boolean {
  return event !== null
    && isActive(event)
    && event.marketMaker !== null
    && event.marketMaker === market.getCurrentUserName();
}

/**
 * The acting user's most recent order still waiting in this book, in words, or null if they
 * have none: the same pick MarketEngine.cancelLastOrder makes.
 */
export function lastOwnOrder(status: OrderBookStatusView | null, user: string | null): string | null {
  if (status === null || status.closed || user === null) {
    return null;
  }
  let last: OrderLineView | null = null;
  let side = '';
  let option = '';
  for (const book of status.options) {
    for (let sideIndex = 0; sideIndex < 2; sideIndex++) {
      for (const line of sideIndex === 0 ? book.bids : book.asks) {
        if (line.userName === user && (last === null || line.sequence > last.sequence)) {
          last = line;
          side = sideIndex === 0 ? 'buy' : 'sell';
          option = book.name;
        }
      }
    }
  }
  return last === null ? null
    : '#' + last.sequence + ': ' + side + ' ' + shares(last.remaining) + ' of ' + option + ' at ' + price(last.price);
}

/** What a cancel did, for the status line. */
export function describeCancel(result: CancelResult): string {
  const order = 'Cancelled order #' + result.sequence + ': ' + (result.side === 'BUY' ? 'buy' : 'sell') + ' '
    + shares(result.cancelled) + ' of ' + result.optionName + ' at ' + price(result.price) + '.';
  return result.side === 'BUY'
    ? order + ' ' + money(result.cashReleased) + ' is available to spend again.'
    : order + ' Those shares can be sold again.';
}

/** What an order did, which is rarely just "order placed": OrderBookPane.describe. */
export function describeOrder(result: OrderResult): string {
  let message = '';
  if (result.filled > 0) {
    let minted = 0;
    for (const fill of result.fills) {
      if (fill.kind === 'MINT') {
        minted += fill.quantity;
      }
    }
    message += (result.side === 'BUY' ? 'Bought' : 'Sold') + ' ' + shares(result.filled) + ' of ' + result.optionName;
    if (minted > 0) {
      message += ' (' + shares(minted) + ' newly minted)';
    }
    message += '.';
  }
  if (result.resting > 0) {
    message += (message.length === 0 ? '' : ' ')
      + shares(result.resting) + ' left waiting in the book at ' + price(result.price) + '.';
  }
  return message;
}

export function methodLabel(event: EventView): string {
  return isLmsr(event) ? 'LMSR' : 'Order book';
}

export function commissionLabel(event: EventView): string {
  const when = event.commissionMethod === 'PER_PURCHASE' ? 'on purchase' : 'on close';
  return percent(event.commissionRate) + '  ' + when;
}

/** Everyone holding shares in this event, plus its market maker. */
export function participants(users: UserView[], event: EventView): number {
  const names = new Set<string>();
  for (const user of users) {
    if (user.marketMakerEventIds.includes(event.id)) {
      names.add(user.name);
    }
    for (const holding of user.holdings) {
      if (holding.eventId === event.id && (holding.shares[0] !== 0 || holding.shares[1] !== 0)) {
        names.add(user.name);
      }
    }
  }
  return names.size;
}

function tradeTotal(trade: Trade): number {
  return trade.sharesCost + trade.commission;
}

function bookAmount(trade: BookTrade): number {
  return trade.price * trade.quantity;
}

function bookTotal(trade: BookTrade): number {
  return bookAmount(trade) + trade.commission;
}

/** The participation log of an LMSR event, newest first, as the history already is. */
export function lmsrLines(status: EventStatusView): Line[] {
  return status.history.map((trade) => ({
    sequence: trade.sequence,
    user: NONE,
    optionName: trade.optionName,
    shares: trade.shares,
    price: trade.shares === 0 ? 0 : trade.sharesCost / trade.shares,
    commission: trade.commission,
    total: tradeTotal(trade),
  }));
}

/** The same log for an order book, where every line names the buyer. */
export function bookLines(status: OrderBookStatusView): Line[] {
  return status.history.map((trade) => ({
    sequence: trade.sequence,
    user: trade.buyer,
    optionName: trade.optionName,
    shares: trade.quantity,
    price: trade.price,
    commission: trade.commission,
    total: bookTotal(trade),
  }));
}

/**
 * What each option last traded at as an order book's log runs forward, starting from where
 * the market opened. An event that opened with no allocation has no opening price, and its
 * untraded side reads NaN, which the chart draws as a gap.
 */
export function bookPriceSeries(status: OrderBookStatusView): PricePoint[] {
  const oldestFirst = [...status.history].reverse();
  const series: PricePoint[] = [];
  const open = status.openingPrice === null ? Number.NaN : status.openingPrice;
  let latest = [open, open];
  if (status.openingPrice !== null) {
    series.push({ prices: latest, at: null });   // the open is nobody's trade
  }
  for (const trade of oldestFirst) {
    latest = [latest[0], latest[1]];
    latest[trade.optionIndex] = trade.price;
    series.push({ prices: latest, at: trade.createdAt });
  }
  return series;
}

/**
 * The same series for an LMSR event: the engine's own prices (the open, then one point per
 * trade, oldest first), with point i belonging to trade i - 1 of the reversed log.
 */
export function lmsrPriceSeries(status: EventStatusView, history: number[][]): PricePoint[] {
  const oldestFirst = [...status.history].reverse();
  return history.map((prices, i) => {
    const fromTrade = i > 0 && i - 1 < oldestFirst.length;
    return { prices, at: fromTrade ? oldestFirst[i - 1].createdAt : null };
  });
}

// --- users ---

export function totalHeld(users: UserView[]): number {
  return users.reduce((total, user) => total + user.balance, 0);
}

/**
 * Every event `user` is in: each option they hold shares in, with what it is worth now and
 * what it pays if that side wins, and then every event they run and hold nothing in, since
 * ownership is a position here even when no shares are.
 */
export function positions(market: RemoteMarket, user: UserView, events: EventView[]): Position[] {
  const byId = new Map<number, EventView>();
  for (const event of events) {
    byId.set(event.id, event);
  }

  const result: Position[] = [];
  const listed = new Set<number>();
  for (const holding of user.holdings) {
    const event = byId.get(holding.eventId);
    if (event === undefined) {
      continue;
    }
    const owner = user.marketMakerEventIds.includes(event.id);
    const active = isActive(event);

    const prices = [0, 0];
    let payout = LMSR_SHARE_VALUE;
    let invested: (number | null)[] = [null, null];
    let winner: string | null;

    if (isLmsr(event)) {
      const status = market.getEventStatus(event.id);
      winner = status.winningOptionName;
      status.options.forEach((option, i) => { prices[i] = option.currentPrice; });
    } else {
      const status = market.getOrderBookStatus(event.id);
      winner = status.winningOptionName;
      payout = status.baseValue;
      status.options.forEach((option, i) => { prices[i] = quote(option); });
      invested = investedByOption(status, user.name);
    }

    for (let i = 0; i < 2; i++) {
      const held = holding.shares[i];
      if (held === 0) {
        continue;
      }
      const optionName = holding.optionNames[i];
      const won = optionName === winner;
      const value = active ? held * prices[i] : (won ? held * payout : 0.0);
      const ifWins = active ? held * payout : null;
      result.push({
        eventId: event.id, eventName: event.name, owner, optionName,
        shares: held, invested: invested[i], value, ifWins, status: event.status,
      });
      listed.add(event.id);
    }
  }

  for (const event of events) {
    if (user.marketMakerEventIds.includes(event.id) && !listed.has(event.id)) {
      result.push({
        eventId: event.id, eventName: event.name, owner: true, optionName: NONE,
        shares: 0, invested: null, value: 0.0, ifWins: null, status: event.status,
      });
    }
  }
  return result;
}

/** Balance plus every open position at what it pays if that side wins. A settled event adds nothing. */
export function potentialOutcome(user: UserView, list: Position[]): number {
  let total = user.balance;
  for (const position of list) {
    if (position.ifWins !== null) {
      total += position.ifWins;
    }
  }
  return total;
}

/** What `name` has put into each option of one book: paid as a buyer, less taken as a seller. */
function investedByOption(status: OrderBookStatusView, name: string): number[] {
  const spent = [0, 0];
  for (const trade of status.history) {
    if (name === trade.buyer) {
      spent[trade.optionIndex] += bookTotal(trade);
    }
    if (name === trade.seller) {
      spent[trade.optionIndex] -= bookAmount(trade);
    }
  }
  return spent;
}

/** The price to value a holding at: what it last traded at, else the middle of the quote. */
export function quote(option: OptionBookView): number {
  if (option.lastPrice !== null) {
    return option.lastPrice;
  }
  if (option.midPrice !== null) {
    return option.midPrice;
  }
  return 0.0;
}

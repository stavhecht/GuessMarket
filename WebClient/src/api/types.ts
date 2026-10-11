/*
 * The engine's DTOs as they arrive on the wire: api.Json.GSON writes each record's
 * components by name, nulls included, and an Instant as its ISO text. These mirror the Java
 * records one for one (engine.dto, engine.model.Trade / BookTrade, api.MarketSnapshot), so a
 * field renamed there has to be renamed here.
 *
 * A record's methods are not on the wire, only its components: Trade.totalPaid() and
 * BookTrade.totalPaid() / amount() are worked out again in marketData.ts.
 */

export interface OptionView {
  name: string;
  currentPrice: number;
  totalShares: number;
}

export interface Trade {
  sequence: number;
  optionName: string;
  shares: number;
  sharesCost: number;
  commission: number;
  createdAt: string | null;
}

export interface BookTrade {
  sequence: number;
  kind: 'MATCH' | 'MINT';
  optionIndex: number;
  optionName: string;
  price: number;
  quantity: number;
  buyer: string;
  seller: string | null;
  commission: number;
  createdAt: string | null;
}

export interface EventView {
  id: number;
  name: string;
  description: string | null;
  commissionRate: number;
  commissionMethod: 'PER_PURCHASE' | 'ON_CLOSE';
  tradingMethod: 'LMSR' | 'ORDER_BOOK';
  marketMaker: string | null;
  optionNames: string[];
  status: string;
}

export interface EventStatusView {
  eventId: number;
  name: string;
  options: OptionView[];
  accountBalance: number;
  commissionCollected: number;
  b: number;
  history: Trade[];
  closed: boolean;
  winningOptionName: string | null;
  finalSharesPerOption: number[] | null;
}

export interface OrderLineView {
  sequence: number;
  userName: string;
  price: number;
  remaining: number;
}

export interface OptionBookView {
  name: string;
  sharesOutstanding: number;
  lastPrice: number | null;
  bestBid: number | null;
  bestAsk: number | null;
  midPrice: number | null;
  spread: number | null;
  bids: OrderLineView[];
  asks: OrderLineView[];
}

export interface OrderBookStatusView {
  eventId: number;
  name: string;
  baseValue: number;
  openingPrice: number | null;
  allowMint: boolean;
  marketMaker: string | null;
  accountBalance: number;
  commissionCollected: number;
  options: OptionBookView[];
  history: BookTrade[];
  closed: boolean;
  winningOptionName: string | null;
}

export interface HoldingView {
  eventId: number;
  eventName: string;
  optionNames: string[];
  shares: number[];
  lockedShares: number[];
}

export interface UserView {
  name: string;
  initialCash: number;
  balance: number;
  reservedCash: number;
  availableCash: number;
  marketMakerEventIds: number[];
  holdings: HoldingView[];
}

export interface PurchaseQuote {
  optionName: string;
  shares: number;
  sharesCost: number;
  commission: number;
  totalCost: number;
  priceAfter: number;
}

export interface PurchaseResult {
  optionName: string;
  sharesBought: number;
  sharesCost: number;
  commission: number;
  totalPaid: number;
  updatedStatus: EventStatusView;
}

export interface FillView {
  kind: 'MATCH' | 'MINT';
  optionName: string;
  price: number;
  quantity: number;
  counterparty: string | null;
  amount: number;
  commission: number;
}

export interface OrderResult {
  sequence: number;
  side: 'BUY' | 'SELL';
  optionName: string;
  price: number;
  quantity: number;
  filled: number;
  resting: number;
  fills: FillView[];
}

export interface CancelResult {
  eventId: number;
  sequence: number;
  side: 'BUY' | 'SELL';
  optionName: string;
  price: number;
  cancelled: number;
  cashReleased: number;
}

export interface SettlementResult {
  eventId: number;
  winningOptionName: string;
  sharesPerOption: number[];
  commissionMoved: number;
  totalPaidToWinners: number;
  subsidyReturned: number;
}

export interface ChatMessage {
  id: number;
  eventId: number;
  author: string;
  text: string;
  sentAt: string | null;
}

/** GET /api/market. Gson writes an Integer map key as a string, so the keys are "1", "2"... */
export interface MarketSnapshot {
  events: EventView[];
  lmsrStatuses: Record<string, EventStatusView>;
  bookStatuses: Record<string, OrderBookStatusView>;
  priceHistories: Record<string, number[][]>;
  users: UserView[];
}

/** What the server answers with on any status but success. */
export interface ErrorBody {
  error: string | null;
  type: string | null;
}

export type CommissionMethod = 'PER_PURCHASE' | 'ON_CLOSE';
export type OrderSide = 'BUY' | 'SELL';

/** The longest chat message the engine accepts: Market.CHAT_MAX_LENGTH. */
export const CHAT_MAX_LENGTH = 500;

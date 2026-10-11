import type {
  CancelResult, ChatMessage, CommissionMethod, ErrorBody, EventStatusView, EventView, MarketSnapshot,
  OrderBookStatusView, OrderResult, OrderSide, PurchaseQuote, PurchaseResult, SettlementResult, UserView,
} from './types';

/*
 * The market on the server, as the screens read and command it: a port of the JavaFX
 * client's RemoteMarket, call for call, against the same endpoints (api.Endpoints).
 *
 * The same three ideas carry over unchanged:
 *
 * Reads come out of a snapshot, not off the wire. The whole market is fetched at once
 * (GET /api/market), kept as one object, and every read the screens make answers out of it.
 * It is refetched every POLL_MILLIS, which is how another client's trade reaches this page,
 * and by every command of this page's own before the command returns, so the redraw that
 * follows a command shows what the command did.
 *
 * Refusals arrive as the engine's own sentence. The server answers a refusal with an
 * ErrorBody and a 4xx, and call() turns that into an EngineError carrying the sentence,
 * which the one place commands are run (perform, in App) paints in the status line.
 *
 * The chat is read one event at a time. The snapshot carries no chat; this keeps a copy of
 * each chat the page has opened, fetched whole the first time, and the poll asks only for
 * what came after the last message held, and only for the event on screen.
 *
 * Where it differs is only what a browser forces: every call is asynchronous, and the
 * session is not a cookie the browser keeps but a header this object keeps (see
 * vite.config.ts for why), so that one tab is one client.
 */

/** How often the poll asks the server whether anything has changed: RemoteMarket.POLL_MILLIS. */
export const POLL_MILLIS = 500;

/** How many messages of one event's chat this page holds; the server keeps as many. */
const CHAT_KEPT = 500;

const SESSION_HEADER = 'X-GM-Session';
const SERVER_HEADER = 'X-GM-Server';

/** Everything that goes wrong comes out as one of these, with a sentence for the status line. */
export class EngineError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'EngineError';
  }
}

/** One point of a balance timeline: LiveMarket.BalancePoint. */
export interface BalancePoint {
  balance: number;
  at: Date | null;
}

export class RemoteMarket {

  /**
   * The server as the user should hear it named, in the status line. Whoever forwards /api
   * (the dev server or WebClient.jar) says it on every answer, as X-GM-Server, so the page
   * is not built for one address.
   */
  base = '';

  private snapshot: MarketSnapshot | null = null;
  private snapshotJson: string | null = null;
  private userName: string | null = null;
  private session: string | null = null;

  /** Raised on every applied fetch, so a slow poll cannot overwrite a newer command's answer. */
  private fetchSeq = 0;
  private appliedSeq = 0;

  private polling = false;
  private pollTimer: number | null = null;

  private readonly chats = new Map<number, ChatMessage[]>();
  private chatWatched: number | null = null;
  private readonly chatFetching = new Set<number>();

  /** LiveMarket's balance timelines: the initial cash, then one point per move seen. */
  private readonly balanceHistories = new Map<string, BalancePoint[]>();

  /** Told when the poll cannot reach the server; the poll carries on and tries again. */
  onTrouble: (message: string) => void = () => {};

  private readonly listeners = new Set<() => void>();
  private version = 0;

  // --- being watched by React ---

  /** For useSyncExternalStore: told whenever anything a screen reads may have changed. */
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getVersion = (): number => this.version;

  private changed(): void {
    this.version++;
    for (const listener of this.listeners) {
      listener();
    }
  }

  // --- the session ---

  /** Becomes `name` on the server, and fetches the first snapshot so the page has a market at once. */
  async login(name: string): Promise<UserView> {
    const me = await this.call<UserView>('POST', '/api/login', { name });
    this.userName = me.name;
    this.balanceHistories.clear();
    try {
      await this.reload();
    } catch {
      // Logged in all the same; the poll fetches the market on its next tick.
    }
    this.changed();
    return me;
  }

  /** Frees the name on the server and forgets the market. Never throws: it is a goodbye. */
  async logout(): Promise<void> {
    this.stopPolling();
    try {
      await this.call<void>('POST', '/api/logout', null);
    } catch {
      // The name is freed when the session times out; there is nothing to tell the user.
    }
    this.forget();
  }

  /**
   * The page is going away (closed, reloaded, navigated off): log out the way the JavaFX
   * client does when its window is closed, so the name is free at once rather than at the
   * session timeout. A keepalive request is the one kind a closing page is allowed to finish.
   */
  logoutOnClose(): void {
    if (this.session === null) {
      return;
    }
    this.stopPolling();
    try {
      void fetch('/api/logout', {
        method: 'POST',
        keepalive: true,
        headers: { [SESSION_HEADER]: this.session },
      });
    } catch {
      // Nothing can be done about it from a page that is closing.
    }
    this.session = null;
  }

  private forget(): void {
    this.userName = null;
    this.session = null;
    this.snapshot = null;
    this.snapshotJson = null;
    this.chats.clear();
    this.chatWatched = null;
    this.balanceHistories.clear();
    this.changed();
  }

  // --- the snapshot ---

  /**
   * Fetches the market again.
   *
   * @return whether anything in it changed, judged on the JSON text itself, as the JavaFX
   *         client judges it, so an unchanged market redraws nothing
   */
  async reload(): Promise<boolean> {
    const seq = ++this.fetchSeq;
    const json = await this.call<string>('GET', '/api/market', null, true);
    if (seq < this.appliedSeq || this.userName === null) {
      return false;      // a newer answer already arrived, or the user logged out meanwhile
    }
    this.appliedSeq = seq;
    if (json === this.snapshotJson) {
      return false;
    }
    this.snapshot = JSON.parse(json) as MarketSnapshot;
    this.snapshotJson = json;
    this.syncBalances(this.snapshot);
    this.changed();
    return true;
  }

  /**
   * Asks the server for the market every POLL_MILLIS, and for the chat on screen. A change
   * reaches the screens through subscribe(); a server out of reach is handed to onTrouble
   * and the poll carries on.
   */
  startPolling(): void {
    this.stopPolling();
    this.polling = true;
    const tick = async () => {
      if (!this.polling) {
        return;
      }
      try {
        await this.reload();
        const watching = this.chatWatched;
        if (watching !== null && this.chats.has(watching)) {
          if (await this.fetchChat(watching)) {
            this.changed();
          }
        }
      } catch (error) {
        if (this.polling) {
          this.onTrouble(messageOf(error));
        }
      }
      if (this.polling) {
        this.pollTimer = window.setTimeout(tick, POLL_MILLIS);
      }
    };
    this.pollTimer = window.setTimeout(tick, POLL_MILLIS);
  }

  stopPolling(): void {
    this.polling = false;
    if (this.pollTimer !== null) {
      window.clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }

  private current(): MarketSnapshot {
    if (this.snapshot === null) {
      throw new EngineError('Not logged in.');
    }
    return this.snapshot;
  }

  // --- the reads, out of the snapshot ---

  /** Whether there is a market to show at all, which for a client is whether it is logged in. */
  isFileLoaded(): boolean {
    return this.snapshot !== null;
  }

  getCurrentUserName(): string | null {
    return this.userName;
  }

  getUsers(): UserView[] {
    return this.current().users;
  }

  getEvents(): EventView[] {
    return this.current().events;
  }

  getEventStatus(eventId: number): EventStatusView {
    const status = this.current().lmsrStatuses[String(eventId)];
    if (!status) {
      throw new EngineError('There is no LMSR event with id ' + eventId + '.');
    }
    return status;
  }

  getOrderBookStatus(eventId: number): OrderBookStatusView {
    const status = this.current().bookStatuses[String(eventId)];
    if (!status) {
      throw new EngineError('There is no order-book event with id ' + eventId + '.');
    }
    return status;
  }

  getPriceHistory(eventId: number): number[][] {
    const history = this.current().priceHistories[String(eventId)];
    if (!history) {
      throw new EngineError('There is no LMSR event with id ' + eventId + '.');
    }
    return history;
  }

  /** The logged-in user's own account, out of the snapshot; null before the first fetch. */
  account(): UserView | null {
    if (this.snapshot === null || this.userName === null) {
      return null;
    }
    return this.snapshot.users.find((user) => user.name === this.userName) ?? null;
  }

  /** The one read that goes to the wire: a quote depends on a share count typed a moment ago. */
  quoteParticipation(eventId: number, optionIndex: number, shares: number): Promise<PurchaseQuote> {
    const query = new URLSearchParams({ id: String(eventId), option: String(optionIndex), shares: String(shares) });
    return this.call<PurchaseQuote>('GET', '/api/events/quote?' + query.toString(), null);
  }

  /**
   * An event's chat, out of this page's copy; asking is also what makes it the chat the poll
   * follows. The first time an event is asked about, its chat is fetched in the background
   * and the screens are told when it arrives; until then it reads as empty.
   */
  getChatMessages(eventId: number, afterId = 0): ChatMessage[] {
    this.chatWatched = eventId;
    const held = this.chats.get(eventId);
    if (held === undefined) {
      if (!this.chatFetching.has(eventId)) {
        this.chatFetching.add(eventId);
        this.fetchChat(eventId)
          .then(() => this.changed())
          .catch(() => { /* an empty chat until the next poll, not an error: the caller is a redraw */ })
          .finally(() => this.chatFetching.delete(eventId));
      }
      return [];
    }
    return held.filter((message) => message.id > afterId);
  }

  /** Whether the chat of `eventId` has been fetched yet, so a pane can tell empty from not yet known. */
  hasChat(eventId: number): boolean {
    return this.chats.has(eventId);
  }

  /** Asks for whatever is newer than the last message held for `eventId`; whether anything arrived. */
  private async fetchChat(eventId: number): Promise<boolean> {
    const held = this.chats.get(eventId) ?? [];
    const last = held.length === 0 ? 0 : held[held.length - 1].id;
    const query = new URLSearchParams({ id: String(eventId), after: String(last) });
    const arrived = await this.call<ChatMessage[]>('GET', '/api/events/chat?' + query.toString(), null);
    const now = this.chats.get(eventId) ?? [];
    // Merged by id, so a poll and a post that both fetched the same line add it once.
    const known = new Set(now.map((message) => message.id));
    const fresh = arrived.filter((message) => !known.has(message.id));
    if (fresh.length === 0) {
      if (!this.chats.has(eventId)) {
        this.chats.set(eventId, []);
      }
      return false;
    }
    const grown = [...now, ...fresh].sort((a, b) => a.id - b.id);
    this.chats.set(eventId, grown.slice(Math.max(0, grown.length - CHAT_KEPT)));
    return true;
  }

  // --- the balance timelines (LiveMarket) ---

  /**
   * The balance timeline of one user, oldest first: what the account started with, then one
   * point per move this page has seen. The engine keeps no ledger, so this is the UI's own,
   * exactly as LiveMarket keeps it for the JavaFX windows.
   */
  balanceHistory(userName: string): BalancePoint[] {
    return [...(this.balanceHistories.get(userName) ?? [])];
  }

  private syncBalances(snapshot: MarketSnapshot): void {
    for (const user of snapshot.users) {
      let history = this.balanceHistories.get(user.name);
      if (history === undefined) {
        history = [{ balance: user.initialCash, at: null }];
        this.balanceHistories.set(user.name, history);
      }
      if (history[history.length - 1].balance !== user.balance) {
        history.push({ balance: user.balance, at: new Date() });
      }
    }
  }

  // --- the commands, each followed by a reload ---

  async loadFunds(amount: number): Promise<UserView> {
    const account = await this.call<UserView>('POST', '/api/me/funds', { amount });
    await this.reload();
    return account;
  }

  async participate(eventId: number, optionIndex: number, shares: number): Promise<PurchaseResult> {
    const result = await this.call<PurchaseResult>('POST', '/api/events/participate', { eventId, optionIndex, shares });
    await this.reload();
    return result;
  }

  async placeOrder(eventId: number, optionIndex: number, side: OrderSide, price: number, quantity: number): Promise<OrderResult> {
    const result = await this.call<OrderResult>('POST', '/api/events/order', { eventId, optionIndex, side, price, quantity });
    await this.reload();
    return result;
  }

  async cancelLastOrder(eventId: number): Promise<CancelResult> {
    const result = await this.call<CancelResult>('POST', '/api/events/order/cancel', { eventId });
    await this.reload();
    return result;
  }

  /** Posts a line, then fetches the chat rather than the market: a message moves no money. */
  async postChatMessage(eventId: number, text: string): Promise<ChatMessage> {
    const posted = await this.call<ChatMessage>('POST', '/api/events/chat', { eventId, text });
    await this.fetchChat(eventId);
    this.changed();
    return posted;
  }

  async closeEvent(eventId: number, winningOptionIndex: number): Promise<SettlementResult> {
    const result = await this.call<SettlementResult>('POST', '/api/events/close', { eventId, winningOptionIndex });
    await this.reload();
    return result;
  }

  async createLmsrEvent(name: string, description: string, commissionPercent: number,
                        commissionMethod: CommissionMethod, optionNames: string[], b: number): Promise<EventView> {
    const made = await this.call<EventView>('POST', '/api/events', {
      name, description, commissionPercent, commissionMethod, optionNames,
      tradingMethod: 'LMSR', b, initialInvestment: null, d: null, allowMint: null,
    });
    await this.reload();
    return made;
  }

  async createOrderBookEvent(name: string, description: string, commissionPercent: number,
                             commissionMethod: CommissionMethod, optionNames: string[],
                             initialInvestment: number, d: number, allowMint: boolean): Promise<EventView> {
    const made = await this.call<EventView>('POST', '/api/events', {
      name, description, commissionPercent, commissionMethod, optionNames,
      tradingMethod: 'ORDER_BOOK', b: null, initialInvestment, d, allowMint,
    });
    await this.reload();
    return made;
  }

  // --- the wire ---

  /**
   * Makes one request and reads the answer: the raw text when `raw`, nothing for an empty
   * answer, parsed JSON otherwise. Everything that can go wrong comes out as an EngineError
   * carrying a sentence for the status line: the server's own for a refusal it explained, and
   * this class's for a server that is out of reach or answered with somebody else's page.
   */
  private async call<T>(method: 'GET' | 'POST', path: string, body: unknown, raw = false): Promise<T> {
    const headers: Record<string, string> = {};
    if (this.session !== null) {
      headers[SESSION_HEADER] = this.session;
    }
    if (method === 'POST') {
      headers['Content-Type'] = 'application/json; charset=utf-8';
    }
    let response: Response;
    try {
      response = await fetch(path, {
        method,
        headers,
        body: method === 'POST' && body !== null ? JSON.stringify(body) : method === 'POST' ? '' : undefined,
        cache: 'no-store',
      });
    } catch (error) {
      throw new EngineError('Cannot reach the web client\'s own server (is its window still open?): ' + messageOf(error));
    }
    const session = response.headers.get(SESSION_HEADER);
    if (session) {
      this.session = session;
    }
    this.base = response.headers.get(SERVER_HEADER) ?? this.base;
    const text = await response.text();
    if (!response.ok) {
      throw new EngineError(explain(response.status, text));
    }
    if (raw) {
      return text as T;
    }
    if (text.length === 0) {
      return undefined as T;
    }
    return JSON.parse(text) as T;
  }
}

function explain(code: number, text: string): string {
  try {
    const error = JSON.parse(text) as ErrorBody;
    if (error && error.error) {
      return code === 401 && error.type !== 'Unreachable' ? 'Your session has ended; log in again.' : error.error;
    }
  } catch {
    // fall through: the body is somebody else's page
  }
  return 'The server replied ' + code + '.';
}

export function messageOf(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

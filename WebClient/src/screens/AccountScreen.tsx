import { useEffect, useState } from 'react';
import type { EventView, UserView } from '../api/types';
import { useHost } from '../host';
import { Ticker, useTicker } from '../components/ticker';
import { Legend, SparkChart } from '../components/sparkChart';
import { Filter, KeyValue, PanelHead, Pill, StatusPill, Table, Tiny, type Column } from '../components/widgets';
import { NONE, money, moveClass, price, quietDouble, quietLong, readDouble, readLong, shares, signed, stamp, trimmed } from '../format';
import {
  canClose, commissionLabel, describeCancel, isActive, isLmsr, lastOwnOrder, methodLabel, positions as positionsOf,
  potentialOutcome, profit, quote, type Position,
} from '../marketData';

/*
 * Screen two, as a client has it: the logged-in user's account (ui.desktop.UsersScreen in
 * its single-user form, where the list of everyone is never built and the account is
 * whoever logged in). The panels descend in the order the account is read: the balance and
 * what it could become, its history, every position behind it, what each is worth, and the
 * one event a position points at with a form for adding to it.
 */

const ALL = 'All';
const ROLES = [ALL, 'Owner', 'Participant'] as const;

const POSITION_COLUMNS: Column<Position>[] = [
  { title: 'Event', width: 130, render: (position) => position.eventName },
  { title: 'Role', width: 92, render: (position) => position.owner ? <Pill text="Owner" kind="mark" /> : <Pill text="Participant" kind="plain" /> },
  { title: 'Option', width: 120, render: (position) => position.optionName },
  { title: 'Shares', width: 68, numeric: true, render: (position) => shares(position.shares) },
  { title: 'Invested', width: 88, numeric: true, render: (position) => position.invested === null ? NONE : money(position.invested) },
  { title: 'Value', width: 84, numeric: true, render: (position) => money(position.value) },
  { title: 'If wins', width: 84, numeric: true, render: (position) => position.ifWins === null ? NONE : money(position.ifWins) },
  {
    title: 'P/L', width: 84, numeric: true,
    cellClass: (position) => { const p = profit(position); return p === null ? '' : moveClass(p); },
    render: (position) => { const p = profit(position); return p === null ? NONE : signed(p); },
  },
  { title: 'Status', width: 72, render: (position) => <StatusPill status={position.status} /> },
];

export function AccountScreen(props: { visible: boolean }) {
  const { market } = useHost();
  const [role, setRole] = useState<typeof ROLES[number]>(ALL);
  const [tradeEventId, setTradeEventId] = useState<number | null>(null);

  const loaded = market.isFileLoaded();
  const user = loaded ? market.account() : null;
  const events: EventView[] = loaded ? market.getEvents() : [];
  const all: Position[] = user === null ? [] : positionsOf(market, user, events);
  const visible = all.filter((position) => role === ALL
    || (role === 'Owner' && position.owner) || (role === 'Participant' && !position.owner));

  // The event on show: the one chosen, while it is still one of this user's; otherwise the
  // first row of the table, as the JavaFX screen reselects after a redraw.
  const mine = uniqueEvents(all, events);
  const chosen = tradeEventId !== null && mine.some((event) => event.id === tradeEventId) ? tradeEventId : null;
  const effective = chosen ?? visible[0]?.eventId ?? null;
  useEffect(() => {
    if (effective !== tradeEventId) {
      setTradeEventId(effective);
    }
  }, [effective, tradeEventId]);

  const selectedRow = visible.find((position) => position.eventId === effective) ?? null;

  return (
    <div className={'screen' + (props.visible ? '' : ' hidden')} style={{ height: 'auto', minHeight: 470 }}>
      <div className="col stack" style={{ gap: 12, flex: '1 1 auto', minWidth: 592 }}>
        <Header user={user} positions={all} visible={props.visible} />
        <BalancePanel user={user} />
        <div className="panel">
          <PanelHead title="Events: participation & ownership">
            <Filter label="role" value={role} options={ROLES} onChange={setRole} width={118} />
          </PanelHead>
          <Table columns={POSITION_COLUMNS} rows={visible} rowKey={(position) => position.eventId + ':' + position.optionName}
                 empty="Holds nothing yet."
                 selected={selectedRow === null ? null : selectedRow.eventId + ':' + selectedRow.optionName}
                 onSelect={(position) => setTradeEventId(position.eventId)}
                 style={{ height: 232, minHeight: 140 }} />
        </div>
        <BarsPanel positions={visible} />
        <TradePanel user={user} events={events} mine={mine} eventId={effective} onPick={setTradeEventId} />
      </div>
    </div>
  );
}

/** The events a user is in, once each, in the order their positions list them. */
function uniqueEvents(all: Position[], events: EventView[]): EventView[] {
  const seen = new Set<number>();
  const result: EventView[] = [];
  for (const position of all) {
    if (!seen.has(position.eventId)) {
      seen.add(position.eventId);
      const event = events.find((one) => one.id === position.eventId);
      if (event !== undefined) {
        result.push(event);
      }
    }
  }
  return result;
}

function Header(props: { user: UserView | null; positions: Position[]; visible: boolean }) {
  const host = useHost();
  const { user, positions } = props;

  const outcome = user === null ? null : potentialOutcome(user, positions);
  const delta = user === null || outcome === null ? null : outcome - user.balance;
  const subject = user?.name ?? '';

  let summary = 'Not logged in.';
  if (user !== null) {
    // Counted off the rows: an event this user runs and holds nothing in is one of their
    // events and not one of their positions.
    const eventCount = new Set(positions.map((position) => position.eventId)).size;
    const held = positions.filter((position) => position.shares > 0).length;
    summary = eventCount + ' event' + (eventCount === 1 ? '' : 's') + ' · ' + held + ' position' + (held === 1 ? '' : 's')
      + ' · ' + money(user.reservedCash) + ' reserved';
  }
  const owned = user?.marketMakerEventIds.length ?? 0;

  return (
    <div className="panel" style={{ flexDirection: 'row', alignItems: 'center', gap: 12, padding: 12, overflow: 'visible' }}>
      <div className="col grow" style={{ gap: 4, minWidth: 150 }}>
        <div className="row baseline">
          <span className="h-panel ellipsis">{user?.name ?? NONE}</span>
          {owned > 0 && <Pill text={'Owner of ' + owned} kind="mark" />}
        </div>
        <span className="faint ellipsis">{summary}</span>
      </div>
      <div className="card" style={{ minWidth: 170 }}>
        <Tiny>account balance</Tiny>
        {/* Held still while the tab is hidden: the roll plays when the tab is opened. */}
        <Ticker className="num-big" value={user?.balance ?? null} subject={subject} format={money} inView={props.visible} />
        <div className="row" style={{ gap: 6 }}>
          <span className="kv-key">available cash</span><span className="grow" />
          <span className="kv-val">{user === null ? NONE : money(user.availableCash)}</span>
        </div>
        <button className="btn small" disabled={user === null} onClick={host.loadFunds}>Load funds…</button>
      </div>
      <div className="card" style={{ minWidth: 210 }}>
        <Tiny>potential outcome</Tiny>
        <div className="row baseline">
          <Ticker className="num-big" value={outcome} subject={subject} format={money} inView={props.visible} />
          <Ticker className={'mono ' + (delta === null ? '' : moveClass(delta))} value={delta} subject={subject}
                  format={signed} inView={props.visible} />
        </div>
        <span className="note">If every open trade wins</span>
      </div>
    </div>
  );
}

/**
 * The account's own timeline: the cash it started with, then one point per move this page
 * has seen. The engine keeps a balance and no ledger, so the history is the UI's.
 */
function BalancePanel(props: { user: UserView | null }) {
  const { market } = useHost();
  const history = props.user === null ? [] : market.balanceHistory(props.user.name);
  const moves = Math.max(0, history.length - 1);
  return (
    <div className="framed">
      <div className="framed-head">
        <Tiny>account balance after each change</Tiny>
        <Legend name="balance" color="accent" />
        <span className="grow" />
        <span className="note">{props.user === null ? '' : moves === 1 ? '1 change' : moves + ' changes'}</span>
      </div>
      <SparkChart
        series={history.length === 0 ? [] : [{ name: 'balance', color: 'accent', points: history.map((point) => point.balance) }]}
        yLabel={money}
        xLabels={history.map((_, i) => i === 0 ? 'start' : 'c' + i)}
        stamps={history.map((point) => stamp(point.at))}
        emptyMessage="No balance changes yet this session." />
    </div>
  );
}

/** Where this user's money is: what each holding is worth now, against what it pays if that side wins. */
function BarsPanel(props: { positions: Position[] }) {
  const held = props.positions.filter((position) => position.shares > 0);
  let widest = 0;
  for (const position of held) {
    widest = Math.max(widest, position.ifWins === null ? position.value : position.ifWins);
  }
  return (
    <div className="panel">
      <PanelHead title="Position value by event">
        <Legend name="value now" color="accent" />
        <Legend name="if that side wins" color="accent-line" />
      </PanelHead>
      <div className="col" style={{ gap: 8, padding: 12, minHeight: 90 }}>
        {held.length === 0 || widest <= 0 ? <span className="faint">Nothing held yet.</span> : held.map((position) => {
          const ceiling = (position.ifWins === null ? position.value : position.ifWins) / widest;
          const now = position.value / widest;
          return (
            <div className="row" style={{ gap: 10 }} key={position.eventId + ':' + position.optionName}>
              <span className="muted ellipsis" style={{ width: 190, flex: '0 0 190px' }}>
                {position.eventName + ' · ' + position.optionName}
              </span>
              <span className="bar-track">
                <span className="bar-fill ghost" style={{ width: (ceiling * 100) + '%' }} />
                <span className="bar-fill" style={{ width: (now * 100) + '%' }} />
              </span>
              <span className="figure" style={{ width: 78, flex: '0 0 78px', textAlign: 'right' }}>{money(position.value)}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function TradePanel(props: {
  user: UserView | null;
  events: EventView[];
  mine: EventView[];
  eventId: number | null;
  onPick: (eventId: number) => void;
}) {
  const host = useHost();
  const { market, version } = host;
  const { user } = props;
  const event = props.events.find((one) => one.id === props.eventId) ?? null;
  const open = event !== null && isActive(event) && user !== null;

  const [option, setOption] = useState(0);
  const [quantity, setQuantity] = useState('100');
  const [limit, setLimit] = useState('');
  const [lmsrQuote, setLmsrQuote] = useState<{ cost: number; fee: number } | null>(null);

  const lmsr = event !== null && isLmsr(event);
  const lmsrStatus = event !== null && lmsr ? market.getEventStatus(event.id) : null;
  const bookStatus = event !== null && !lmsr ? market.getOrderBookStatus(event.id) : null;
  const myOrder = lastOwnOrder(bookStatus, market.getCurrentUserName());

  // The limit box is offered the chosen option's touch price while it is empty.
  useEffect(() => {
    if (bookStatus !== null) {
      const book = bookStatus.options[option];
      const touch = book.bestAsk !== null ? book.bestAsk : book.lastPrice;
      setLimit((typed) => typed.trim() === '' ? (touch === null ? '' : touch.toFixed(2)) : typed);
    }
    // Only on a redraw, never on typing.
  }, [props.eventId, version]);

  const count = quietLong(quantity);

  useEffect(() => {
    if (event === null || !lmsr || count <= 0 || user === null) {
      setLmsrQuote(null);
      return;
    }
    let current = true;
    market.quoteParticipation(event.id, option, count)
      .then((answer) => { if (current) setLmsrQuote({ cost: answer.sharesCost, fee: answer.commission }); })
      .catch(() => { if (current) setLmsrQuote(null); });
    return () => { current = false; };
  }, [market, event?.id, lmsr, option, count, user === null, version]);

  // Prices what is in the form, quietly: a half-typed number is not an error yet.
  let breakdown: { cost: number; fee: number } | null = null;
  if (event !== null && count > 0 && user !== null) {
    if (lmsr) {
      breakdown = lmsrQuote;
    } else {
      const atPrice = quietDouble(limit);
      if (atPrice !== null) {
        const cost = count * atPrice;
        breakdown = { cost, fee: event.commissionMethod === 'PER_PURCHASE' ? cost * event.commissionRate : 0 };
      }
    }
  }
  const after = breakdown === null || user === null ? null : user.availableCash - breakdown.cost - breakdown.fee;

  const submit = () => {
    if (event === null) {
      return;
    }
    const optionIndex = option;
    void host.perform(async () => {
      const howMany = readLong(quantity, 'shares');
      if (isLmsr(event)) {
        const result = await market.participate(event.id, optionIndex, howMany);
        return 'Bought ' + shares(result.sharesBought) + ' of ' + result.optionName + ' for '
          + money(result.sharesCost) + ' (commission ' + money(result.commission) + ').';
      }
      const atPrice = readDouble(limit, 'price');
      const result = await market.placeOrder(event.id, optionIndex, 'BUY', atPrice, howMany);
      return 'Order #' + result.sequence + ': ' + shares(result.filled) + ' filled, ' + shares(result.resting)
        + ' resting at ' + price(result.price) + '.';
    });
  };

  return (
    <div className="panel">
      <div className="panel-head">
        <span className="h-section ellipsis">{event === null ? 'No event selected' : event.name + ' · details & trade'}</span>
        {event !== null && <Pill text={isActive(event) ? 'Active' : 'Closed'} kind={isActive(event) ? 'ok' : 'off'} />}
        <span className="grow" />
        <span className="filter">
          <Tiny>event</Tiny>
          <select className="select" style={{ width: 210, minWidth: 84 }} disabled={props.mine.length === 0}
                  value={event === null ? '' : String(event.id)}
                  onChange={(e) => props.onPick(Number(e.target.value))}>
            {event === null && <option value="" />}
            {props.mine.map((one) => <option key={one.id} value={String(one.id)}>{one.id + ' · ' + one.name}</option>)}
          </select>
        </span>
        {myOrder !== null && event !== null && (
          <button className="btn small" title={'Withdraws order ' + myOrder + ' and gives back what it set aside.'}
                  onClick={() => host.perform(async () => describeCancel(await market.cancelLastOrder(event.id)))}>
            Cancel my last order
          </button>
        )}
        {canClose(market, event) && event !== null && (
          <button className="btn small danger" onClick={() => host.closeEvent(event)}>Close event…</button>
        )}
      </div>
      <div className="panel-body row" style={{ gap: 12, alignItems: 'flex-start' }}>
        <div className="col grow" style={{ gap: 10 }}>
          <span className="muted">{event === null ? 'Pick one of your positions to trade its event.' : event.description ?? ''}</span>
          <div className="row" style={{ gap: 10, alignItems: 'stretch' }}>
            {[0, 1].map((i) => {
              if (event === null) {
                return <OptionRow key={i} name={NONE} value={null} subject="" detail="" />;
              }
              if (lmsrStatus !== null) {
                const one = lmsrStatus.options[i];
                return <OptionRow key={i} name={one.name} value={one.currentPrice} subject={event.id + ':' + i}
                                  detail={shares(one.totalShares) + ' sh · b = ' + trimmed(lmsrStatus.b)} />;
              }
              const book = bookStatus!.options[i];
              return <OptionRow key={i} name={book.name} value={quote(book)} subject={event.id + ':' + i}
                                detail={shares(book.sharesOutstanding) + ' sh · bid ' + price(book.bestBid) + ' / ask ' + price(book.bestAsk)} />;
            })}
          </div>
          <div className="row" style={{ gap: 18 }}>
            <KeyValue label="commission"><span className="kv-val">{event === null ? NONE : commissionLabel(event)}</span></KeyValue>
            <KeyValue label="market method"><span className="kv-val">{event === null ? NONE : methodLabel(event)}</span></KeyValue>
            <KeyValue label="event account">
              <span className="kv-val">{lmsrStatus !== null ? money(lmsrStatus.accountBalance)
                : bookStatus !== null ? money(bookStatus.accountBalance) : NONE}</span>
            </KeyValue>
          </div>
        </div>
        <div className="card" style={{ width: 260, flex: '0 0 260px' }}>
          <Tiny>{event === null || user === null ? 'Buy shares' : 'Buy shares as ' + user.name}</Tiny>
          <div className="row" style={{ gap: 6 }}>
            {[0, 1].map((i) => (
              <button key={i} className={'toggle grow' + (option === i ? ' selected' : '')} disabled={!open}
                      onClick={() => setOption(i)}>
                {event === null ? NONE : event.optionNames[i]}
              </button>
            ))}
          </div>
          <div className="row" style={{ gap: 6 }}>
            <input className="field qty" style={{ width: 90 }} value={quantity} disabled={!open}
                   onChange={(e) => setQuantity(e.target.value)} />
            <span className="faint">shares</span>
          </div>
          {event !== null && !lmsr && (
            <div className="row" style={{ gap: 6 }}>
              <Tiny>limit price</Tiny>
              <input className="field qty" style={{ width: 80 }} value={limit} disabled={!open}
                     onChange={(e) => setLimit(e.target.value)} />
            </div>
          )}
          <div className="divider" />
          <div className="col" style={{ gap: 6 }}>
            <BreakdownRow label="Shares cost" value={breakdown === null ? NONE : money(breakdown.cost)} />
            <BreakdownRow label="Commission" value={breakdown === null ? NONE : money(breakdown.fee)} />
            <BreakdownRow label="Total" value={breakdown === null ? NONE : money(breakdown.cost + breakdown.fee)} />
            <BreakdownRow label="Available after" value={after === null ? NONE : money(after)}
                          className={after !== null && after < 0 ? 'down' : ''} />
          </div>
          <button className="btn primary wide" disabled={!open} onClick={submit}>
            {breakdown === null || event === null ? 'Buy' : 'Buy ' + shares(count) + ' of ' + event.optionNames[option]}
          </button>
        </div>
      </div>
    </div>
  );
}

function BreakdownRow(props: { label: string; value: string; className?: string }) {
  return (
    <div className="row">
      <span className="kv-key">{props.label}</span>
      <span className="grow" />
      <span className={'kv-val ' + (props.className ?? '')}>{props.value}</span>
    </div>
  );
}

/** One outcome as the trade panel shows it: name, a rolling price, a meter that follows it, a line of detail. */
function OptionRow(props: { name: string; value: number | null; subject: string; detail: string }) {
  const rolling = useTicker(props.value, props.subject);
  const fill = Math.max(0, Math.min(1, rolling ?? 0));
  return (
    <div className="card grow" style={{ flexBasis: 0 }}>
      <div className="row baseline">
        <span className="opt-name">{props.name}</span>
        <span className="grow" />
        <span className="opt-price">{rolling === null ? NONE : price(rolling)}</span>
      </div>
      <div className="meter"><div className="meter-fill" style={{ width: (fill * 100) + '%' }} /></div>
      <span className="note">{props.detail}</span>
    </div>
  );
}

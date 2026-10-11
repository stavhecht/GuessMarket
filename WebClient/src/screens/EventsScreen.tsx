import { useEffect, useState } from 'react';
import type { EventView, UserView } from '../api/types';
import { useHost } from '../host';
import { Ticker } from '../components/ticker';
import { Legend, SparkChart } from '../components/sparkChart';
import { Filter, KeyValue, PanelHead, Pill, StatusPill, Table, Tiny, type Column } from '../components/widgets';
import { NONE, eventNumber, money, price, shares, stamp, trimmed } from '../format';
import {
  bookLines, bookPriceSeries, canClose, commissionLabel, describeCancel, isActive, isLmsr, lastOwnOrder,
  lmsrLines, lmsrPriceSeries, methodLabel, participants, type Line, type PricePoint,
} from '../marketData';
import { LmsrPane } from './LmsrPane';
import { OrderBookPane } from './OrderBookPane';
import { ChatPane } from './ChatPane';

/*
 * Screen one: every event on the left, the selected one traded on the right
 * (ui.desktop.EventsScreen). The left panel is a filterable list with a preview of whatever
 * is highlighted; the right is that event's market (an LMSR pair of price cards or an order
 * book's two ladders), the price history under it, the participation log under that, and
 * the event's chat at the foot. The filters are the screen's own and never reach the server.
 */

const ALL = 'All';
const METHODS = [ALL, 'LMSR', 'Order book'] as const;
const STATUSES = [ALL, 'Active', 'Closed'] as const;
const COMMISSIONS = [ALL, 'On purchase', 'On close'] as const;

const EVENT_COLUMNS: Column<EventView>[] = [
  { title: 'id', width: 40, ident: true, render: (event) => eventNumber(event.id) },
  { title: 'Event', width: 150, render: (event) => event.name },
  { title: 'Method', width: 90, render: methodLabel },
  { title: 'Commission', width: 130, render: commissionLabel },
  { title: 'Status', width: 72, render: (event) => <StatusPill status={event.status} /> },
];

const LINE_COLUMNS: Column<Line>[] = [
  { title: '#', width: 44, ident: true, render: (line) => String(line.sequence) },
  { title: 'User', width: 110, render: (line) => line.user },
  { title: 'Option', width: 130, render: (line) => line.optionName },
  { title: 'Shares', width: 74, numeric: true, render: (line) => shares(line.shares) },
  { title: 'Price', width: 66, numeric: true, render: (line) => line.price.toFixed(2) },
  { title: 'Comm.', width: 74, numeric: true, render: (line) => money(line.commission) },
  { title: 'Total', width: 86, numeric: true, render: (line) => money(line.total) },
];

export function EventsScreen(props: { visible: boolean }) {
  const host = useHost();
  const { market } = host;

  const [method, setMethod] = useState<typeof METHODS[number]>(ALL);
  const [status, setStatus] = useState<typeof STATUSES[number]>(ALL);
  const [commission, setCommission] = useState<typeof COMMISSIONS[number]>(ALL);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const loaded = market.isFileLoaded();
  const all: EventView[] = loaded ? market.getEvents() : [];
  const users: UserView[] = loaded ? market.getUsers() : [];

  const matches = (event: EventView) => {
    if (method !== ALL && method !== methodLabel(event)) {
      return false;
    }
    if (status !== ALL && status !== (isActive(event) ? 'Active' : 'Closed')) {
      return false;
    }
    const when = event.commissionMethod === 'PER_PURCHASE' ? 'On purchase' : 'On close';
    return commission === ALL || commission === when;
  };
  const visible = all.filter(matches);

  // Keep the selected event if it is still showing; otherwise open on the first row rather
  // than on an empty panel.
  const selected = visible.find((event) => event.id === selectedId) ?? visible[0] ?? null;
  useEffect(() => {
    const id = selected?.id ?? null;
    if (id !== selectedId) {
      setSelectedId(id);
    }
  }, [selected?.id, selectedId]);

  return (
    <div className={'screen' + (props.visible ? '' : ' hidden')}>
      <div className="panel" style={{ flex: '1 1 0', minWidth: 460 }}>
        <PanelHead title="Events">
          <Pill text={String(all.length)} kind="count" />
          <span style={{ width: 4 }} />
          <button className="btn small" disabled={!loaded || market.getCurrentUserName() === null}
                  title={'Adds an event to this market. You become its market maker, so an order book\'s '
                    + 'initial investment, or an LMSR event\'s subsidy, comes out of your balance.'}
                  onClick={host.createEvent}>
            Create event
          </button>
        </PanelHead>
        <div className="subbar row" style={{ gap: 8 }}>
          <Tiny>filter</Tiny>
          <Filter label="method" value={method} options={METHODS} onChange={setMethod} width={108} />
          <Filter label="status" value={status} options={STATUSES} onChange={setStatus} width={90} />
          <Filter label="commission" value={commission} options={COMMISSIONS} onChange={setCommission} width={118} />
          <span className="grow" />
          <span className="note ellipsis">{loaded ? visible.length + ' of ' + all.length : ''}</span>
        </div>
        <Table columns={EVENT_COLUMNS} rows={visible} rowKey={(event) => event.id}
               empty={loaded ? 'No events yet. Create one.' : 'Not logged in.'}
               selected={selected?.id ?? null} onSelect={(event) => setSelectedId(event.id)}
               style={{ flex: '1 1 auto' }} />
        <Preview event={selected} users={users} />
      </div>
      <EventPanel event={selected} />
    </div>
  );
}

function Preview(props: { event: EventView | null; users: UserView[] }) {
  const event = props.event;
  return (
    <div className="subbar col" style={{ gap: 8, padding: 12, borderTop: '1px solid var(--gm-line)', borderBottom: 'none' }}>
      <Tiny>selected event</Tiny>
      <span className="h-section">{event === null ? NONE : event.name}</span>
      <span className="muted" style={{ minHeight: 32 }}>{event?.description ?? ''}</span>
      <div className="row" style={{ gap: 18, alignItems: 'flex-start' }}>
        <KeyValue label="owner"><span className="figure">{event?.marketMaker ?? NONE}</span></KeyValue>
        <KeyValue label="options"><span className="muted">{event === null ? NONE : event.optionNames.join(' / ')}</span></KeyValue>
        <KeyValue label="commission"><span className="muted">{event === null ? NONE : commissionLabel(event)}</span></KeyValue>
        <KeyValue label="participants">
          <span className="figure">{event === null ? NONE : String(participants(props.users, event))}</span>
        </KeyValue>
      </div>
    </div>
  );
}

function EventPanel(props: { event: EventView | null }) {
  const host = useHost();
  const { market } = host;
  const event = props.event;

  let body = <div className="faint">Select an event on the left.</div>;
  let lines: Line[] = [];
  let note = '';
  let account: number | null = null;
  let commissionTaken: number | null = null;
  let mine: string | null = null;
  let series: PricePoint[] = [];
  let fromOpen = false;

  if (event !== null) {
    if (isLmsr(event)) {
      const status = market.getEventStatus(event.id);
      body = <LmsrPane status={status} />;
      lines = lmsrLines(status);
      note = 'b = ' + trimmed(status.b);
      account = status.accountBalance;
      commissionTaken = status.commissionCollected;
      series = lmsrPriceSeries(status, market.getPriceHistory(event.id));
      fromOpen = true;     // an LMSR event always has an opening price: 0.5 a side
    } else {
      const status = market.getOrderBookStatus(event.id);
      body = <OrderBookPane event={event} status={status} />;
      lines = bookLines(status);
      note = 'd = ' + trimmed(status.baseValue) + (status.allowMint ? ' · minting allowed' : ' · no minting');
      account = status.accountBalance;
      commissionTaken = status.commissionCollected;
      mine = lastOwnOrder(status, market.getCurrentUserName());
      series = bookPriceSeries(status);
      fromOpen = status.openingPrice !== null;
    }
  }

  const closable = canClose(market, event);
  const tradeCount = lines.length === 1 ? '1 trade' : lines.length + ' trades';

  return (
    <div className="panel" style={{ flex: '1 1 0', minWidth: 610 }}>
      <div className="panel-head">
        <span className="h-panel ellipsis">{event === null ? 'No event selected' : event.name}</span>
        <span className="mono faint">{event === null ? '' : '#' + eventNumber(event.id)}</span>
        {event !== null && <Pill text={isActive(event) ? 'Active' : 'Closed'} kind={isActive(event) ? 'ok' : 'off'} />}
        <span className="grow" />
        {mine !== null && event !== null && (
          <button className="btn small" title={'Withdraws order ' + mine + ' and gives back what it set aside.'}
                  onClick={() => host.perform(async () => describeCancel(await market.cancelLastOrder(event.id)))}>
            Cancel my last order
          </button>
        )}
        {closable && event !== null && (
          <button className="btn small danger" onClick={() => host.closeEvent(event)}>Close event…</button>
        )}
      </div>
      {/* An order-book ladder is a good deal taller than a pair of LMSR cards, so the panel
          scrolls rather than squeezing whichever block happens to be last. */}
      <div className="panel-body col stack" style={{ gap: 12, overflowY: 'auto', flex: '1 1 auto' }}>
        {body}
        <div className="framed">
          <div className="framed-head">
            <Tiny>option price from the opening</Tiny>
            {event !== null && (
              <>
                <Legend name={event.optionNames[0]} color="accent" />
                <Legend name={event.optionNames[1]} color="tx-3" />
              </>
            )}
            <span className="grow" />
            <span className="note">{event === null ? '' : tradeCount}</span>
          </div>
          <PriceChart event={event} series={series} fromOpen={fromOpen} />
        </div>
        <div className="framed">
          <div className="framed-head">
            <Tiny>participations</Tiny>
            <Pill text={String(lines.length)} kind="count" />
          </div>
          <Table columns={LINE_COLUMNS} rows={lines} rowKey={(line) => lines.indexOf(line)}
                 empty="Nothing has traded yet." style={{ height: 212, minHeight: 120 }} />
          <div className="footer-strip">
            <Tiny>mm balance</Tiny>
            <Ticker className="figure" value={account} subject={'account:' + (event?.id ?? '')} format={money} />
            <span style={{ width: 10 }} />
            <Tiny>commission</Tiny>
            <Ticker className="figure" value={commissionTaken} subject={'commission:' + (event?.id ?? '')} format={money} />
            <span className="grow" />
            <span className="note">{note}</span>
          </div>
        </div>
        <ChatPane event={event} />
      </div>
    </div>
  );
}

function PriceChart(props: { event: EventView | null; series: PricePoint[]; fromOpen: boolean }) {
  const { event, series, fromOpen } = props;
  if (event === null) {
    return <SparkChart series={[]} yLabel={() => ''} xLabels={[]} emptyMessage="No transactions yet." />;
  }
  const ticks = series.map((_, i) => fromOpen && i === 0 ? 'open' : 't' + (fromOpen ? i : i + 1));
  const stamps = series.map((point) => stamp(point.at));
  return (
    <SparkChart
      series={[
        { name: event.optionNames[0], color: 'accent', points: series.map((point) => point.prices[0]) },
        { name: event.optionNames[1], color: 'tx-3', points: series.map((point) => point.prices[1]) },
      ]}
      yLabel={(value) => price(value)}
      xLabels={ticks}
      stamps={stamps}
      emptyMessage="No transactions yet." />
  );
}

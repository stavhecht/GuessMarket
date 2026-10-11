import { useEffect, useState } from 'react';
import type { EventView, OptionBookView, OrderBookStatusView, OrderLineView, OrderSide } from '../api/types';
import { useHost } from '../host';
import { Ticker } from '../components/ticker';
import { Pill, Tiny } from '../components/widgets';
import { money, price, quietDouble, quietLong, readDouble, readLong, shares } from '../format';
import { describeOrder } from '../marketData';

/*
 * The body of an order-book event: both options side by side as ladders, the sell queue
 * above, the spread, the buy queue below, and a form under each for taking either side
 * (ui.desktop.OrderBookPane). Both are shown together because a buyer of one and a buyer of
 * the other can between them mint a new pair, which you only see coming by looking at both.
 */
export function OrderBookPane(props: { event: EventView; status: OrderBookStatusView }) {
  return (
    <div className="cards ladders">
      {props.status.options.map((option, i) => (
        <Ladder key={i} event={props.event} status={props.status} option={option} optionIndex={i} />
      ))}
    </div>
  );
}

function Ladder(props: { event: EventView; status: OrderBookStatusView; option: OptionBookView; optionIndex: number }) {
  const { market, perform, version } = useHost();
  const { event, status, option, optionIndex } = props;
  const eventId = status.eventId;
  const open = !status.closed;
  const commissionOnPurchase = event.commissionMethod === 'PER_PURCHASE';

  const [quantity, setQuantity] = useState('100');
  const [limit, setLimit] = useState('');

  // The engine needs a limit, so the touch price is offered as one while the box is empty.
  const touch = option.bestAsk !== null ? option.bestAsk : option.lastPrice;
  useEffect(() => {
    if (touch !== null) {
      setLimit((typed) => typed.trim() === '' ? touch.toFixed(2) : typed);
    }
    // Only on a redraw of the market, never on typing: an emptied box stays empty until then.
  }, [eventId, version]);

  // What the order in the boxes would cost, before it meets anybody.
  const count = quietLong(quantity);
  const atPrice = quietDouble(limit);
  let estimate = '';
  if (count > 0 && atPrice !== null) {
    const cost = count * atPrice;
    const commission = commissionOnPurchase ? cost * event.commissionRate : 0;
    estimate = commission > 0 ? '≈ ' + money(cost) + ' + ' + money(commission) : '≈ ' + money(cost);
  }

  const place = (side: OrderSide) => perform(async () => {
    const at = readDouble(limit, 'price');
    const howMany = readLong(quantity, 'shares');
    return describeOrder(await market.placeOrder(eventId, optionIndex, side, at, howMany));
  });

  let deepest = 0;
  for (const line of [...option.asks, ...option.bids]) {
    deepest = Math.max(deepest, line.remaining);
  }
  // Asks read downwards to the spread, so the best one sits closest to it, while the engine
  // hands them over best first, which is the other way round.
  const asks = [...option.asks].reverse();

  return (
    <div className="opt-card">
      <div className="opt-head">
        <span className="opt-name">{option.name}</span>
        <Pill text="order book" kind="plain" />
        <span className="grow" />
        <Ticker className="opt-price" value={option.lastPrice} subject={eventId + ':' + optionIndex} format={price} />
        <span className="faint">{shares(option.sharesOutstanding)} sh</span>
      </div>
      <div className="stat-strip">
        <Stat label="last" value={price(option.lastPrice)} />
        <Stat label="bid" value={price(option.bestBid)} className="up" />
        <Stat label="ask" value={price(option.bestAsk)} className="down" />
        <Stat label="mid" value={price(option.midPrice)} />
        <Stat label="spread" value={price(option.spread)} className="muted" />
      </div>
      <div className="ladder-head">
        <span className="cell user"><Tiny>user</Tiny></span>
        <span className="cell shares"><Tiny>shares</Tiny></span>
        <span className="cell price"><Tiny>price</Tiny></span>
      </div>
      <Band title="sell orders · ask" colour="band-ask" count={option.asks.length} />
      <Rows orders={asks} deepest={deepest} colour="down" empty="No sellers." />
      <div className="spread-row">
        <Tiny>spread</Tiny><span className="grow" /><span className="figure">{price(option.spread)}</span>
      </div>
      <Band title="buy orders · bid" colour="band-bid" count={option.bids.length} />
      <Rows orders={option.bids} deepest={deepest} colour="up" empty="No buyers." />
      <div className="trade-row">
        <input className="field mini" style={{ width: 64, flex: '0 0 auto' }} value={quantity} disabled={!open}
               onChange={(e) => setQuantity(e.target.value)} />
        <input className="field mini" style={{ width: 60, flex: '0 0 auto' }} value={limit} placeholder="price"
               disabled={!open} onChange={(e) => setLimit(e.target.value)} />
        <span className="note ellipsis grow">{estimate}</span>
        <button className="btn sell compact" disabled={!open} onClick={() => place('SELL')}>Sell</button>
        <button className="btn buy compact" disabled={!open} onClick={() => place('BUY')}>Buy</button>
      </div>
    </div>
  );
}

function Stat(props: { label: string; value: string; className?: string }) {
  return (
    <div className="stat">
      <Tiny>{props.label}</Tiny>
      <span className={'stat-val' + (props.className ? ' ' + props.className : '')}>{props.value}</span>
    </div>
  );
}

function Band(props: { title: string; colour: string; count: number }) {
  return (
    <div className="ladder-band">
      <Tiny className={props.colour}>{props.title}</Tiny>
      <span className="grow" />
      <Tiny>{props.count === 1 ? '1 ORDER' : props.count + ' ORDERS'}</Tiny>
    </div>
  );
}

function Rows(props: { orders: OrderLineView[]; deepest: number; colour: 'up' | 'down'; empty: string }) {
  if (props.orders.length === 0) {
    return <div className="ladder-empty">{props.empty}</div>;
  }
  return (
    <>
      {props.orders.map((order) => {
        const depth = props.deepest === 0 ? 0 : order.remaining / props.deepest;
        return (
          <div className="ladder-row" key={order.sequence}>
            <span className={'depth ' + props.colour} style={{ width: (depth * 100) + '%' }} />
            <span className="cell user mono muted">{order.userName}</span>
            <span className="cell shares mono">{shares(order.remaining)}</span>
            <span className={'cell price mono strong ' + props.colour}>{order.price.toFixed(2)}</span>
          </div>
        );
      })}
    </>
  );
}

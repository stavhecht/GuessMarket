import { useEffect, useState } from 'react';
import type { EventStatusView, OptionView } from '../api/types';
import { useHost } from '../host';
import { useTicker } from '../components/ticker';
import { NONE, money, price, quietLong, readLong, shares } from '../format';

/*
 * The body of an LMSR event: one card per outcome, showing what the scoring rule quotes,
 * what the next purchase would cost, and a field to make it (ui.desktop.LmsrPane). There is
 * no selling and no order: the market maker is always the counterparty, so a card is a
 * price, a quantity and a Buy button. "What would this cost" is the engine's quote, never
 * worked out here: an LMSR cost is not the quoted price times the quantity.
 */
export function LmsrPane(props: { status: EventStatusView }) {
  return (
    <div className="cards">
      {props.status.options.map((option, i) => (
        <Card key={i} status={props.status} option={option} optionIndex={i} />
      ))}
    </div>
  );
}

function Card(props: { status: EventStatusView; option: OptionView; optionIndex: number }) {
  const { market, perform, version } = useHost();
  const { status, option, optionIndex } = props;
  const eventId = status.eventId;
  const open = !status.closed;

  const [quantity, setQuantity] = useState('100');
  const [quote, setQuote] = useState<{ cost: string; after: string }>({ cost: NONE, after: NONE });

  // The headline price counts to its new value, and the meter follows the rolling figure.
  const rolling = useTicker(option.currentPrice, eventId + ':' + optionIndex);
  const fill = Math.max(0, Math.min(1, rolling ?? 0));

  const wanted = quietLong(quantity);

  // Re-prices the quantity in the box, quietly: a half-typed number is not an error.
  useEffect(() => {
    if (wanted <= 0) {
      setQuote({ cost: NONE, after: NONE });
      return;
    }
    let current = true;
    market.quoteParticipation(eventId, optionIndex, wanted)
      .then((answer) => {
        if (current) {
          setQuote({ cost: money(answer.totalCost), after: price(answer.priceAfter) });
        }
      })
      .catch(() => {
        if (current) {
          setQuote({ cost: NONE, after: NONE });
        }
      });
    return () => { current = false; };
  }, [market, eventId, optionIndex, wanted, version]);

  // The card speaks in the option the user can see; the engine counts from 0.
  const buy = () => perform(async () => {
    const count = readLong(quantity, 'shares');
    const result = await market.participate(eventId, optionIndex, count);
    return 'Bought ' + shares(result.sharesBought) + ' of ' + result.optionName + ' for '
      + money(result.sharesCost) + ' (commission ' + money(result.commission) + ').';
  });

  return (
    <div className="opt-card">
      <div className="opt-head">
        <span className="opt-name">{option.name}</span>
        <span className="grow" />
        <span className="opt-price">{rolling === null ? NONE : price(rolling)}</span>
      </div>
      <div className="opt-body">
        <div className="meter">
          <div className={'meter-fill' + (optionIndex === 1 ? ' other' : '')} style={{ width: (fill * 100) + '%' }} />
        </div>
        <div className="col" style={{ gap: 6 }}>
          <div className="row"><span className="kv-key">Shares outstanding</span><span className="grow" />
            <span className="kv-val">{shares(option.totalShares)}</span></div>
          <div className="row"><span className="kv-key">{wanted > 0 ? 'Cost of next ' + shares(wanted) : 'Cost of next'}</span>
            <span className="grow" /><span className="kv-val">{quote.cost}</span></div>
          <div className="row"><span className="kv-key">Price after</span><span className="grow" />
            <span className="kv-val">{quote.after}</span></div>
        </div>
      </div>
      <div className="opt-foot">
        <input className="field mini" style={{ width: 72 }} value={quantity} disabled={!open}
               onChange={(event) => setQuantity(event.target.value)}
               onKeyDown={(event) => { if (event.key === 'Enter' && open) buy(); }} />
        <span className="grow" />
        <button className="btn primary compact" disabled={!open} onClick={buy}>Buy</button>
      </div>
    </div>
  );
}

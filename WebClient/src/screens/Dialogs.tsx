import { useEffect, useRef, useState } from 'react';
import type { CommissionMethod, EventView } from '../api/types';
import { useHost } from '../host';
import { Dialog, Tiny } from '../components/widgets';
import { money, readDouble, readInt } from '../format';
import { messageOf } from '../api/remoteMarket';

/*
 * The dialogs the window opens on the market: ui.desktop.EventDialogs and CreateEventDialog.
 * None of them holds a rule: each reads what was typed, turns the numbers into numbers, and
 * asks the server, whose engine owns every rule.
 */

/**
 * The create-event form. A refusal keeps it open: the server is asked while the form is
 * still up, and a rejected event paints the engine's reason at its foot instead of closing a
 * window full of typing. It closes only on an event the engine accepted, and hands back the
 * sentence describing it, which perform then reports like any other command.
 */
export function CreateEventDialog(props: { onDone: (made: string | null) => void }) {
  const { market } = useHost();
  const creator = market.getCurrentUserName() ?? '';

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [commission, setCommission] = useState('5');
  const [when, setWhen] = useState<CommissionMethod>('PER_PURCHASE');
  const [optionA, setOptionA] = useState('');
  const [optionB, setOptionB] = useState('');
  const [method, setMethod] = useState<'LMSR' | 'ORDER_BOOK'>('LMSR');
  const [b, setB] = useState('100');
  const [initialInvestment, setInitialInvestment] = useState('100');
  const [baseValue, setBaseValue] = useState('1');
  const [allowMint, setAllowMint] = useState(true);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const first = useRef<HTMLInputElement>(null);

  useEffect(() => first.current?.focus(), []);

  const create = async () => {
    setBusy(true);
    try {
      const percent = readInt(commission, 'commission percentage');
      const options = [optionA, optionB];
      const made: EventView = method === 'LMSR'
        ? await market.createLmsrEvent(name, description, percent, when, options, readDouble(b, 'liquidity b'))
        : await market.createOrderBookEvent(name, description, percent, when, options,
          readInt(initialInvestment, 'initial investment'), readInt(baseValue, 'base value'), allowMint);
      props.onDone('Created event ' + made.id + ', \'' + made.name + '\'. You are its market maker.');
    } catch (refused) {
      setProblem(messageOf(refused));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog title="Create event" header={'A new market, run by ' + creator} error={problem}
            onCancel={() => props.onDone(null)}
            footer={<>
              <button className="btn primary" disabled={busy} onClick={create}>Create</button>
              <button className="btn" onClick={() => props.onDone(null)}>Cancel</button>
            </>}>
      <Field label="event name">
        <input ref={first} className="field" placeholder="What is being predicted" value={name}
               onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="description">
        <input className="field" placeholder="A sentence about it" value={description}
               onChange={(e) => setDescription(e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="commission %" grow={false}>
          <input className="field qty" style={{ width: 70 }} placeholder="0" value={commission}
                 onChange={(e) => setCommission(e.target.value)} />
        </Field>
        <Field label="charged">
          <div className="row" style={{ gap: 6 }}>
            <Choice selected={when === 'PER_PURCHASE'} onClick={() => setWhen('PER_PURCHASE')}>On purchase</Choice>
            <Choice selected={when === 'ON_CLOSE'} onClick={() => setWhen('ON_CLOSE')}>On close</Choice>
          </div>
        </Field>
      </div>
      <div className="form-row">
        <Field label="option 1">
          <input className="field" placeholder="First outcome" value={optionA} onChange={(e) => setOptionA(e.target.value)} />
        </Field>
        <Field label="option 2">
          <input className="field" placeholder="Second outcome" value={optionB} onChange={(e) => setOptionB(e.target.value)} />
        </Field>
      </div>
      <Field label="traded by">
        <div className="row" style={{ gap: 6 }}>
          <Choice selected={method === 'LMSR'} onClick={() => setMethod('LMSR')}>LMSR</Choice>
          <Choice selected={method === 'ORDER_BOOK'} onClick={() => setMethod('ORDER_BOOK')}>Order book</Choice>
        </div>
      </Field>
      {method === 'LMSR' ? (
        <div className="card" style={{ gap: 10 }}>
          <Field label="liquidity b" note={'Larger b, flatter prices: a purchase moves them less. Opening it costs '
            + creator + ' b·ln 2, the most the market can lose.'}>
            <input className="field qty" style={{ width: 90 }} placeholder="100" value={b} onChange={(e) => setB(e.target.value)} />
          </Field>
        </div>
      ) : (
        <div className="card" style={{ gap: 10 }}>
          <Field label="initial investment"
                 note={'Paid by ' + creator + ', and returned as shares ' + creator + ' holds until they choose to sell.'}>
            <input className="field qty" style={{ width: 90 }} placeholder="100" value={initialInvestment}
                   onChange={(e) => setInitialInvestment(e.target.value)} />
          </Field>
          <Field label="base value d" note="What one winning share pays. A pair of them costs it, so each opens at half.">
            <input className="field qty" style={{ width: 90 }} placeholder="1" value={baseValue}
                   onChange={(e) => setBaseValue(e.target.value)} />
          </Field>
          <label className="check" style={{ fontSize: 12 }}>
            <input type="checkbox" checked={allowMint} onChange={(e) => setAllowMint(e.target.checked)} />
            Allow minting
          </label>
        </div>
      )}
    </Dialog>
  );
}

/** Asks which option won, then settles the event on it. */
export function CloseEventDialog(props: { event: EventView; onDone: () => void }) {
  const { market, perform } = useHost();
  const [winner, setWinner] = useState(0);
  const { event } = props;

  const close = () => {
    props.onDone();
    const winningIndex = winner;     // the engine counts from 0
    void perform(async () => {
      const result = await market.closeEvent(event.id, winningIndex);
      const returned = result.subsidyReturned > 0 ? ', ' + money(result.subsidyReturned) + ' back to the market maker' : '';
      return 'Event ' + result.eventId + ' closed on ' + result.winningOptionName + ': '
        + money(result.totalPaidToWinners) + ' paid out, ' + money(result.commissionMoved) + ' commission' + returned + '.';
    });
  };

  return (
    <Dialog title={'Close event ' + event.id} header="Which option won?" narrow onCancel={props.onDone}
            footer={<>
              <button className="btn primary" onClick={close}>OK</button>
              <button className="btn" onClick={props.onDone}>Cancel</button>
            </>}>
      <Field label="winning option">
        <select className="select" style={{ height: 30 }} value={winner} autoFocus
                onChange={(e) => setWinner(Number(e.target.value))}>
          {event.optionNames.map((name, i) => <option key={i} value={i}>{name}</option>)}
        </select>
      </Field>
    </Dialog>
  );
}

/**
 * Asks how much, then loads it into the account. The figure is read inside perform, so a
 * sum that is not a number is reported in the status line like any other refusal.
 */
export function LoadFundsDialog(props: { onDone: () => void }) {
  const { market, perform } = useHost();
  const [amount, setAmount] = useState('100');
  const user = market.getCurrentUserName() ?? '';

  const load = () => {
    props.onDone();
    const typed = amount;
    void perform(async () => {
      const sum = readDouble(typed, 'amount');
      const account = await market.loadFunds(sum);
      return 'Loaded ' + money(sum) + ' into ' + account.name + '\'s account; the balance is now '
        + money(account.balance) + '.';
    });
  };

  return (
    <Dialog title="Load funds" header={'Load funds into ' + user + '\'s account'} narrow onCancel={props.onDone}
            footer={<>
              <button className="btn primary" onClick={load}>OK</button>
              <button className="btn" onClick={props.onDone}>Cancel</button>
            </>}>
      <Field label="amount">
        <input className="field" value={amount} autoFocus onFocus={(e) => e.target.select()}
               onChange={(e) => setAmount(e.target.value)}
               onKeyDown={(e) => { if (e.key === 'Enter') load(); }} />
      </Field>
    </Dialog>
  );
}

function Field(props: { label: string; note?: string; grow?: boolean; children: React.ReactNode }) {
  return (
    <div className="form-field" style={props.grow === false ? { flex: '0 0 auto' } : undefined}>
      <Tiny>{props.label}</Tiny>
      {props.children}
      {props.note && <span className="note">{props.note}</span>}
    </div>
  );
}

function Choice(props: { selected: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" className={'toggle small' + (props.selected ? ' selected' : '')} onClick={props.onClick}>
      {props.children}
    </button>
  );
}

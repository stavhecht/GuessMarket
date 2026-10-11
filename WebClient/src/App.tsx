import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { RemoteMarket, messageOf } from './api/remoteMarket';
import type { EventView } from './api/types';
import { HostContext, type Host } from './host';
import { money } from './format';
import { EventsScreen } from './screens/EventsScreen';
import { AccountScreen } from './screens/AccountScreen';
import { CloseEventDialog, CreateEventDialog, LoadFundsDialog } from './screens/Dialogs';

/*
 * The web client: the exercise-3 client window, in a browser (client.ClientApp over
 * ui.desktop.Shell). A login page in front; once the server has accepted a name, the file
 * strip, the two tabs (Events and Account) and the status line. Everything is read from a
 * RemoteMarket and every command goes through perform, which reports it in the status line.
 *
 * Unlike the JavaFX client there is no Upload file button: this client works only with the
 * events already on the server and those created from the form.
 */

const THEMES = ['light', 'dark', 'neon'] as const;
type Theme = typeof THEMES[number];
const THEME_LABEL: Record<Theme, string> = { light: 'Light', dark: 'Dark', neon: 'Neon' };

/** Per-viewer conveniences, as a JavaFX window remembers nothing between runs either; tolerant of a browser that refuses. */
function remembered(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function remember(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // a browser that keeps nothing simply starts on the defaults next time
  }
}

type DialogState = { kind: 'create' } | { kind: 'close'; event: EventView } | { kind: 'funds' } | null;

/**
 * The page's one client, as a JavaFX window has one RemoteMarket for its whole life.
 *
 * Kept on the window rather than in React state or a module variable, because both of those
 * are rebuilt when Vite hot-reloads a saved source file, and a second RemoteMarket is a
 * second client with no session: Log out would then free nothing on the server, while the
 * first one went on polling under the old session and kept the name taken for good. On the
 * window it lives exactly as long as the page does.
 */
function pageMarket(): RemoteMarket {
  const page = window as Window & { guessMarket?: RemoteMarket };
  page.guessMarket ??= new RemoteMarket();
  return page.guessMarket;
}

export function App() {
  const market = pageMarket();
  const version = useSyncExternalStore(market.subscribe, market.getVersion);

  // Whether there is somebody logged in is the client's to say, not a copy of it here, so a
  // component rebuilt by a hot reload still agrees with the session it is showing.
  const loggedIn = market.getCurrentUserName() !== null;
  const [theme, setTheme] = useState<Theme>(() => {
    const saved = remembered('gm.theme');
    return THEMES.includes(saved as Theme) ? saved as Theme : 'light';
  });
  const [animated, setAnimated] = useState(() => remembered('gm.animations') !== 'off');
  const [tab, setTab] = useState<'events' | 'account'>('events');
  const [status, setStatus] = useState<{ message: string; error: boolean }>({ message: '', error: false });
  const [dialog, setDialog] = useState<DialogState>(null);

  const report = useCallback((message: string, error: boolean) => setStatus({ message, error }), []);

  // Commands run one at a time, in the order they were asked for, the way the JavaFX
  // client's run on its one FX thread: a second click cannot overtake the first.
  const queue = useRef<Promise<void>>(Promise.resolve());
  const perform = useCallback((action: () => Promise<string>) => {
    const run = queue.current.then(async () => {
      try {
        report(await action(), false);
      } catch (error) {
        report(messageOf(error), true);
      }
    });
    queue.current = run;
    return run;
  }, [report]);

  // The poll's complaints go to whichever App is on screen now.
  useEffect(() => {
    market.onTrouble = (trouble) => report(trouble, true);
  }, [market, report]);

  // Closing (or reloading) the tab is logging out, so the name is free for the next client.
  useEffect(() => {
    const goodbye = () => market.logoutOnClose();
    window.addEventListener('pagehide', goodbye);
    return () => window.removeEventListener('pagehide', goodbye);
  }, [market]);

  useEffect(() => remember('gm.theme', theme), [theme]);
  useEffect(() => remember('gm.animations', animated ? 'on' : 'off'), [animated]);

  const logIn = async (name: string): Promise<string | null> => {
    try {
      const me = await market.login(name);
      setTab('events');
      report('Logged in as ' + me.name + '. Create an event, or trade on the events already here.', false);
      market.startPolling();
      return null;
    } catch (error) {
      return messageOf(error);
    }
  };

  const logOut = async () => {
    setDialog(null);
    await market.logout();
    report('', false);
  };

  const host: Host = {
    market,
    version,
    perform,
    report,
    animated,
    createEvent: () => {
      if (market.getCurrentUserName() === null) {
        report('Log in first: whoever creates an event runs it.', true);
      } else {
        setDialog({ kind: 'create' });
      }
    },
    closeEvent: (event) => setDialog({ kind: 'close', event }),
    loadFunds: () => setDialog({ kind: 'funds' }),
  };

  const me = loggedIn ? market.account() : null;
  const actingAs = loggedIn && market.isFileLoaded()
    ? 'Logged in as ' + market.getCurrentUserName() + (me === null ? '' : ' · balance ' + money(me.balance)) + ' · ' + market.base
    : '';

  return (
    <div className={'gm-root theme-' + theme}>
      {!loggedIn ? (
        <Login onLogIn={logIn} />
      ) : (
        <HostContext.Provider value={host}>
          <div className="toolstrip">
            <div className="sunken">
              <span className="h-tiny">{market.isFileLoaded() ? 'CONNECTED' : 'NOT LOGGED IN'}</span>
            </div>
            <button className="btn" onClick={logOut}>Log out</button>
            <button className="btn tiny" title="Switch the look"
                    onClick={() => setTheme(THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length])}>
              {THEME_LABEL[theme]}
            </button>
          </div>
          <div className="tabstrip">
            <button className={'tab' + (tab === 'events' ? ' selected' : '')} onClick={() => setTab('events')}>Events</button>
            <button className={'tab' + (tab === 'account' ? ' selected' : '')} onClick={() => setTab('account')}>Account</button>
            <span className="grow" />
            <label className={'check' + (animated ? ' on' : '')}
                   title="Roll figures to their new value instead of jumping">
              <input type="checkbox" checked={animated} onChange={(e) => setAnimated(e.target.checked)} />
              Animations
            </label>
          </div>
          <div className="workspace">
            {/* Both screens stay mounted, as both JavaFX tabs do, so the Account tab's
                figures can hold their roll until the tab is opened. */}
            <EventsScreen visible={tab === 'events'} />
            <AccountScreen visible={tab === 'account'} />
          </div>
          <div className="statusbar">
            <span className={'ellipsis ' + (status.error ? 'down' : 'muted')}>{status.message}</span>
            <span className="grow" />
            <span className="note ellipsis">{actingAs}</span>
          </div>
          {dialog?.kind === 'create' && (
            <CreateEventDialog onDone={(made) => {
              setDialog(null);
              if (made !== null) {
                void perform(async () => made);
              }
            }} />
          )}
          {dialog?.kind === 'close' && <CloseEventDialog event={dialog.event} onDone={() => setDialog(null)} />}
          {dialog?.kind === 'funds' && <LoadFundsDialog onDone={() => setDialog(null)} />}
        </HostContext.Provider>
      )}
    </div>
  );
}

/**
 * The login page: client.Login.fxml. A refusal (the name is in use, or the server is out of
 * reach) is written under the field and the page stays, with the name still in it.
 */
function Login(props: { onLogIn: (name: string) => Promise<string | null> }) {
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    setError('');
    setBusy(true);
    const refusal = await props.onLogIn(name);
    setBusy(false);
    if (refusal !== null) {
      setError(refusal);
    }
  };

  return (
    <div className="login">
      <form className="panel" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        <span className="h-panel">Guess Market</span>
        <span className="note" style={{ fontSize: 12 }}>
          Enter a name to join the market. It has to be one nobody is logged in under right now.
        </span>
        <input className="field" placeholder="Your name" autoFocus value={name} onChange={(e) => setName(e.target.value)} />
        <div>
          <button className="btn primary" type="submit" disabled={busy}>Log in</button>
        </div>
        <span className="down">{error}</span>
      </form>
    </div>
  );
}

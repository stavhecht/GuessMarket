import { createContext, useContext } from 'react';
import type { RemoteMarket } from './api/remoteMarket';
import type { EventView } from './api/types';

/*
 * What a screen needs from the window it sits in: ui.desktop.Host, as a React context.
 * The screens are written against this and the RemoteMarket, never against App.
 */
export interface Host {
  market: RemoteMarket;
  /** Bumped whenever the market may have changed; a dependency for effects that must re-run on a refresh. */
  version: number;
  /** Runs one command and reports it: the one place a refusal is caught. */
  perform: (action: () => Promise<string>) => Promise<void>;
  report: (message: string, isError: boolean) => void;
  createEvent: () => void;
  closeEvent: (event: EventView) => void;
  loadFunds: () => void;
  /** The Animations switch: whether figures roll to their new value or land on it. */
  animated: boolean;
}

export const HostContext = createContext<Host | null>(null);

export function useHost(): Host {
  const host = useContext(HostContext);
  if (host === null) {
    throw new Error('useHost outside the shell');
  }
  return host;
}

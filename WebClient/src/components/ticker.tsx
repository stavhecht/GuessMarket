import { useEffect, useRef, useState } from 'react';
import { useHost } from '../host';
import { NONE } from '../format';

/*
 * A figure that rolls to its new value instead of jumping to it: ui.desktop.Ticker.
 *
 * The same rules decide when it moves:
 *   - the same subject (an event's option, a user) and a new value: roll to it;
 *   - a different subject: land on it at once, since another event's price is not this
 *     one's next value and rolling would invent a move that never happened;
 *   - null is a dash, neither rolled to nor rolled from;
 *   - out of view (the Account tab while the Events tab is showing), the roll waits and
 *     plays when the figure comes into view, which is the only moment it can be seen;
 *   - with Animations unticked, every figure simply lands.
 * Only the animation is ever held back: the value is always the engine's.
 */

/** Long enough to read as a movement, short enough not to delay the next command. */
const ROLL_MILLIS = 420;

function easeOut(t: number): number {
  return 1 - Math.pow(1 - t, 3);
}

/**
 * The value to show this frame: the rolling figure itself, so a meter bound to it moves
 * with the digits rather than snapping to the answer ahead of them.
 */
export function useTicker(value: number | null, subject: string, inView = true): number | null {
  const { animated } = useHost();
  const [shown, setShown] = useState<number | null>(value);
  const shownRef = useRef<number | null>(value);
  const subjectRef = useRef(subject);
  const frame = useRef<number | null>(null);
  const target = useRef<number | null>(value);

  const show = (next: number | null) => {
    shownRef.current = next;
    setShown(next);
  };

  useEffect(() => () => {
    if (frame.current !== null) {
      cancelAnimationFrame(frame.current);
    }
  }, []);

  useEffect(() => {
    const newSubject = subjectRef.current !== subject;
    subjectRef.current = subject;
    const from = shownRef.current;

    // Land: a new subject, a dash on either end, or no animation wanted.
    if (newSubject || value === null || from === null || !animated) {
      if (frame.current !== null) {
        cancelAnimationFrame(frame.current);
        frame.current = null;
      }
      target.current = value;
      show(value);
      return;
    }
    if (!inView) {
      return;     // held: the figure on screen waits, and the roll runs once it is seen
    }
    if (value === target.current && frame.current !== null) {
      return;     // already rolling there
    }
    if (value === from) {
      target.current = value;
      return;
    }
    if (frame.current !== null) {
      cancelAnimationFrame(frame.current);
    }
    target.current = value;
    const start = performance.now();
    const step = (now: number) => {
      const t = Math.min(1, (now - start) / ROLL_MILLIS);
      show(from + (value - from) * easeOut(t));
      if (t < 1) {
        frame.current = requestAnimationFrame(step);
      } else {
        frame.current = null;
        show(value);
      }
    };
    frame.current = requestAnimationFrame(step);
  }, [value, subject, inView, animated]);

  return shown;
}

/** A rolling figure written out: `format` for a number, the dash for none. */
export function Ticker(props: {
  value: number | null;
  subject: string;
  format: (value: number) => string;
  className?: string;
  inView?: boolean;
}) {
  const shown = useTicker(props.value, props.subject, props.inView ?? true);
  return <span className={props.className}>{shown === null ? NONE : props.format(shown)}</span>;
}

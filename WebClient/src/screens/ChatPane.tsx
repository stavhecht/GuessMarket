import { useLayoutEffect, useRef, useState } from 'react';
import type { EventView } from '../api/types';
import { CHAT_MAX_LENGTH } from '../api/types';
import { useHost } from '../host';
import { Pill, Tiny } from '../components/widgets';
import { clock, stamp } from '../format';

/*
 * The selected event's chat: everything everyone connected has said about it, oldest at the
 * top, and a line to add to it (ui.desktop.ChatPane). The conversation lives on the server
 * and the poll brings other clients' lines in. It follows the newest line only when the
 * reader was already at the foot, so a new line does not drag away someone reading back.
 */

/** How many author colours the sheet declares: .chat-c0 to .chat-c14. */
const AUTHOR_COLOURS = 15;

/** Java's String.hashCode, so the fallback colour is the one the JavaFX client picks too. */
function javaHash(text: string): number {
  let hash = 0;
  for (let i = 0; i < text.length; i++) {
    hash = (Math.imul(31, hash) + text.charCodeAt(i)) | 0;
  }
  return hash;
}

export function ChatPane(props: { event: EventView | null }) {
  const { market, perform } = useHost();
  const [input, setInput] = useState('');
  const lines = useRef<HTMLDivElement>(null);
  const field = useRef<HTMLInputElement>(null);
  const atFoot = useRef(true);
  const shownEvent = useRef<number | null>(null);

  const event = props.event;
  const messages = event === null ? [] : market.getChatMessages(event.id, 0);
  const lastId = messages.length === 0 ? 0 : messages[messages.length - 1].id;
  const me = market.getCurrentUserName();
  const users = market.isFileLoaded() ? market.getUsers() : [];

  // A person's colour is their place in the market's list of users, the order they joined
  // it, so the first fifteen people are fifteen colours and a person is the same colour in
  // every window; a hash of the name only as a fallback.
  const colourOf = (author: string) => {
    const place = users.findIndex((user) => user.name === author);
    return place >= 0 ? place % AUTHOR_COLOURS : ((javaHash(author) % AUTHOR_COLOURS) + AUTHOR_COLOURS) % AUTHOR_COLOURS;
  };

  useLayoutEffect(() => {
    const element = lines.current;
    if (element === null) {
      return;
    }
    const switched = shownEvent.current !== (event?.id ?? null);
    shownEvent.current = event?.id ?? null;
    if (switched || atFoot.current) {
      element.scrollTop = element.scrollHeight;
      atFoot.current = true;
    }
  }, [event?.id, lastId]);

  const onScroll = () => {
    const element = lines.current;
    if (element !== null) {
      atFoot.current = element.scrollTop + element.clientHeight >= element.scrollHeight - 4;
    }
  };

  // Sent through perform like every other command, so a refusal is painted where every
  // refusal is; the field is emptied only once the server has taken the line.
  const post = () => {
    if (event === null) {
      return;
    }
    const target = event.id;
    const name = event.name;
    const text = input;
    void perform(async () => {
      await market.postChatMessage(target, text);
      setInput('');
      atFoot.current = true;
      return 'Sent to the chat of ' + name + '.';
    });
    field.current?.focus();
  };

  return (
    <div className="framed">
      <div className="framed-head">
        <Tiny>chat</Tiny>
        <Pill text={String(messages.length)} kind="count" />
        <span className="grow" />
        <span className="note">everyone connected sees this</span>
      </div>
      <div className="chat-lines" ref={lines} onScroll={onScroll}>
        {messages.length === 0 && <div className="ladder-empty">Nobody has said anything about this event yet.</div>}
        {messages.map((message) => (
          <div key={message.id} className="col" style={{ gap: 2 }}>
            <div className="row">
              <span className={'chat-author chat-c' + colourOf(message.author)}>
                {message.author === me ? message.author + ' (you)' : message.author}
              </span>
              <span className="note" title={stamp(message.sentAt)}>{clock(message.sentAt)}</span>
            </div>
            <div className="chat-text">{message.text}</div>
          </div>
        ))}
      </div>
      <div className="footer-strip">
        <input ref={field} className="field grow" placeholder="Say something about this event"
               maxLength={CHAT_MAX_LENGTH} value={input} disabled={event === null}
               onChange={(e) => setInput(e.target.value)}
               onKeyDown={(e) => { if (e.key === 'Enter') post(); }} />
        <button className="btn small" disabled={event === null} onClick={post}>Send</button>
      </div>
    </div>
  );
}

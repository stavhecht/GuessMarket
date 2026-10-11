/*
 * How figures are written, the same way Widgets writes them for the JavaFX windows: money
 * grouped to two places, prices to two places, shares grouped, a dash for a figure that
 * does not exist. The engine never rounds; this is the only place that does, and only at
 * the moment of display.
 */

/** The design's dash for "no such figure": Widgets.NONE. */
export const NONE = '–';

const MONEY = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const SHARES = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });

/** {@code %,.2f}: 1,234.50. */
export function money(amount: number | null | undefined): string {
  return amount === null || amount === undefined || Number.isNaN(amount) ? NONE : MONEY.format(amount);
}

/** A price to two places and never grouped; a dash when there is none. */
export function price(value: number | null | undefined): string {
  return value === null || value === undefined || Number.isNaN(value) ? NONE : value.toFixed(2);
}

export function shares(count: number): string {
  return SHARES.format(count);
}

export function percent(rate: number): string {
  return (rate * 100).toFixed(2) + '%';
}

/** A gain or a loss, always signed, with the design's minus sign rather than a hyphen. */
export function signed(amount: number): string {
  if (amount > 0) {
    return '+' + money(amount);
  }
  return amount < 0 ? '−' + money(-amount) : money(0);
}

export function moveClass(amount: number): 'up' | 'down' | 'faint' {
  return amount > 0 ? 'up' : amount < 0 ? 'down' : 'faint';
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function two(value: number): string {
  return value < 10 ? '0' + value : String(value);
}

/**
 * When something happened, as Widgets.stamp writes it ("d MMM yyyy, HH:mm:ss", in English
 * whatever the machine's language), in the machine's own zone. Empty for a moment nobody
 * recorded, such as a market's opening price.
 */
export function stamp(when: string | Date | null | undefined): string {
  if (when === null || when === undefined) {
    return '';
  }
  const date = when instanceof Date ? when : new Date(when);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return date.getDate() + ' ' + MONTHS[date.getMonth()] + ' ' + date.getFullYear() + ', '
    + two(date.getHours()) + ':' + two(date.getMinutes()) + ':' + two(date.getSeconds());
}

/** The chat's HH:mm. */
export function clock(when: string | null): string {
  if (when === null) {
    return '';
  }
  const date = new Date(when);
  return Number.isNaN(date.getTime()) ? '' : two(date.getHours()) + ':' + two(date.getMinutes());
}

/** {@code 100} rather than {@code 100.00}; b and d are whole numbers in every file. */
export function trimmed(value: number): string {
  return Number.isInteger(value) ? String(value) : money(value);
}

/** Two-digit event id, as the table and the title write it. */
export function eventNumber(id: number): string {
  return id < 10 ? '0' + id : String(id);
}

/*
 * Reading what was typed: Shell.readLong / readInt / readDouble. These turn text into a
 * number and do nothing else; whether the number is acceptable is the engine's to say, and
 * it says it for every client alike.
 */

const WHOLE = /^[+-]?\d+$/;

export function readLong(text: string, what: string): number {
  const cleaned = text.trim().replace(/,/g, '');
  if (!WHOLE.test(cleaned) || !Number.isSafeInteger(Number(cleaned))) {
    throw new Error('\'' + text.trim() + '\' is not a whole number of ' + what + '.');
  }
  return Number(cleaned);
}

export function readInt(text: string, what: string): number {
  const cleaned = text.trim().replace(/,/g, '');
  const value = Number(cleaned);
  if (!WHOLE.test(cleaned) || value > 2147483647 || value < -2147483648) {
    throw new Error('\'' + text.trim() + '\' is not a whole ' + what + '.');
  }
  return value;
}

/** A figure that may have a fraction. Commas are not stripped: 0,5 is somebody's half and must not become 5. */
export function readDouble(text: string, what: string): number {
  const cleaned = text.trim();
  const value = Number(cleaned);
  if (cleaned.length === 0 || !/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(cleaned) || !Number.isFinite(value)) {
    throw new Error('\'' + cleaned + '\' is not a ' + what + '.');
  }
  return value;
}

/** A quantity box read quietly, for a preview: a half-typed number is 0, not an error. */
export function quietLong(text: string): number {
  const cleaned = text.trim().replace(/,/g, '');
  return WHOLE.test(cleaned) ? Number(cleaned) : 0;
}

/** A price box read quietly: null for anything that is not a positive number. */
export function quietDouble(text: string): number | null {
  const cleaned = text.trim();
  if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(cleaned)) {
    return null;
  }
  const value = Number(cleaned);
  return value > 0 ? value : null;
}

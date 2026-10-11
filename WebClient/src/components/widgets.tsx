import type { ReactNode } from 'react';

/*
 * The small pieces every screen is built from: ui.desktop.Widgets, as components.
 */

export function Pill(props: { text: string; kind: 'ok' | 'off' | 'mark' | 'plain' | 'count' }) {
  return <span className={'pill ' + props.kind}>{props.text}</span>;
}

export function StatusPill(props: { status: string }) {
  const active = props.status.toUpperCase() === 'ACTIVE';
  return <Pill text={active ? 'Active' : 'Closed'} kind={active ? 'ok' : 'off'} />;
}

/** The small-caps label the design puts above and beside everything. */
export function Tiny(props: { children: ReactNode; className?: string }) {
  return <span className={'h-tiny' + (props.className ? ' ' + props.className : '')}>{props.children}</span>;
}

/** The header strip of a panel: an upper-case subject, then whatever else. */
export function PanelHead(props: { title: string; children?: ReactNode }) {
  return (
    <div className="panel-head">
      <span className="h-caps">{props.title}</span>
      <span className="grow" />
      {props.children}
    </div>
  );
}

/** A key above its value, for the detail grids. */
export function KeyValue(props: { label: string; children: ReactNode }) {
  return (
    <div className="kv">
      <Tiny>{props.label}</Tiny>
      {props.children}
    </div>
  );
}

/** The design's filter control: a small labelled drop-down in the sub-bar. */
export function Filter<T extends string>(props: {
  label: string;
  value: T;
  options: readonly T[];
  onChange: (value: T) => void;
  width: number;
}) {
  return (
    <span className="filter">
      <Tiny>{props.label}</Tiny>
      <select className="select" value={props.value} style={{ width: props.width, minWidth: Math.min(props.width, 84) }}
              onChange={(event) => props.onChange(event.target.value as T)}>
        {props.options.map((option) => <option key={option} value={option}>{option}</option>)}
      </select>
    </span>
  );
}

export interface Column<T> {
  title: string;
  width?: number;
  numeric?: boolean;
  ident?: boolean;
  /** Extra classes for one row's cell, such as the P/L column's up or down. */
  cellClass?: (row: T) => string;
  render: (row: T) => ReactNode;
}

/**
 * A table of rows: the JavaFX TableView as the screens use it. A heading over a column of
 * figures is aligned where its figures are, as Widgets.overFigures does it.
 */
export function Table<T>(props: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string | number;
  empty: string;
  selected?: string | number | null;
  onSelect?: (row: T) => void;
  className?: string;
  style?: React.CSSProperties;
}) {
  return (
    <div className={'table-wrap' + (props.className ? ' ' + props.className : '')} style={props.style}>
      <table className={'table' + (props.onSelect ? ' selectable' : '')}>
        <thead>
          <tr>
            {props.columns.map((column) => (
              <th key={column.title} className={column.numeric ? 'numeric' : undefined}
                  style={column.width ? { minWidth: column.width } : undefined}>
                {column.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {props.rows.map((row) => {
            const key = props.rowKey(row);
            return (
              <tr key={key} className={key === props.selected ? 'selected' : undefined}
                  onClick={props.onSelect ? () => props.onSelect!(row) : undefined}>
                {props.columns.map((column) => (
                  <td key={column.title}
                      className={[column.numeric ? 'numeric' : '', column.ident ? 'ident' : '',
                        column.cellClass ? column.cellClass(row) : ''].join(' ').trim() || undefined}>
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
      {props.rows.length === 0 && <div className="table-empty">{props.empty}</div>}
    </div>
  );
}

/**
 * A modal dialog in the design's dress: a header panel, a body, a button bar. Escape and a
 * click outside are Cancel.
 */
export function Dialog(props: {
  title: string;
  header: string;
  children: ReactNode;
  footer: ReactNode;
  error?: string | null;
  narrow?: boolean;
  onCancel: () => void;
}) {
  return (
    <div className="overlay" onMouseDown={(event) => { if (event.target === event.currentTarget) props.onCancel(); }}
         onKeyDown={(event) => { if (event.key === 'Escape') props.onCancel(); }}>
      <div className={'dialog' + (props.narrow ? ' narrow' : '')} role="dialog" aria-label={props.title}>
        <div className="dialog-head">
          <div className="dialog-title">{props.title}</div>
          <div className="dialog-header">{props.header}</div>
        </div>
        <div className="dialog-body">{props.children}</div>
        {props.error && <div className="form-error">{props.error}</div>}
        <div className="dialog-foot">{props.footer}</div>
      </div>
    </div>
  );
}

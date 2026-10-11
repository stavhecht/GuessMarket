import { useEffect, useMemo, useRef, useState } from 'react';

/*
 * A small line chart: ui.desktop.SparkChart drawn in SVG instead of on a canvas.
 *
 * The same picture: five grid lines with the scale on the left, a few labels along the
 * bottom, each series as a line with a dot on every point and a ringed dot on the latest.
 * A NaN is a gap (an order-book option that has not traded yet). Hovering near a dot rings
 * it and names it in a popup, the value and, where the point has one, when it happened; two
 * series at the same value are one dot and get one popup naming both.
 */

export interface Series {
  name: string;
  /** A token name: "accent" reads var(--gm-accent). */
  color: string;
  points: number[];
}

/** The narrowest the scale on the left gets; it widens to fit its longest label. */
const MIN_LEFT = 40;
const RIGHT = 10;
const TOP = 10;
const BOTTOM = 18;
const GRID_LINES = 5;
const DOT = 2.4;
const RING = 5;
const HIT = 14;

interface Spot {
  series: number;
  index: number;
  x: number;
  y: number;
  value: number;
}

export function SparkChart(props: {
  series: Series[];
  yLabel: (value: number) => string;
  xLabels: string[];
  stamps?: string[];
  emptyMessage: string;
  height?: number;
}) {
  const height = props.height ?? 150;
  const holder = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  const [hover, setHover] = useState<{ series: number; index: number } | null>(null);

  useEffect(() => {
    const element = holder.current;
    if (element === null) {
      return;
    }
    const observer = new ResizeObserver(() => setWidth(element.clientWidth));
    observer.observe(element);
    setWidth(element.clientWidth);
    return () => observer.disconnect();
  }, []);

  // What was under the pointer belonged to the old data: point 3 of the event just
  // deselected is not point 3 of this one. Judged on the content, since the caller builds a
  // new array on every render and a redraw that changed nothing must not drop the popup.
  const signature = JSON.stringify(props.series);
  useEffect(() => setHover(null), [signature]);

  const points = props.series.reduce((longest, one) => Math.max(longest, one.points.length), 0);

  const layout = useMemo(() => {
    const values = props.series.flatMap((one) => one.points.filter((value) => !Number.isNaN(value)));
    let low = 0;
    let high = 1;
    if (values.length > 0) {
      low = Math.min(...values);
      high = Math.max(...values);
      if (high - low < 1e-9) {
        const nudge = Math.max(Math.abs(high) * 0.05, 0.05);
        low -= nudge;
        high += nudge;
      } else {
        const padding = (high - low) * 0.1;
        low -= padding;
        high += padding;
      }
    }
    // Wide enough for the longest label on the scale, so money in the thousands is not cut
    // off at the left edge: 9px mono runs about 5.6px a character.
    let widest = 0;
    for (let i = 0; i < GRID_LINES; i++) {
      widest = Math.max(widest, props.yLabel(low + (high - low) * i / (GRID_LINES - 1)).length);
    }
    const LEFT = Math.max(MIN_LEFT, Math.ceil(widest * 5.6) + 10);
    const plotWidth = width - LEFT - RIGHT;
    const plotHeight = height - TOP - BOTTOM;
    const x = (index: number) => points === 1 ? LEFT + plotWidth / 2 : LEFT + plotWidth * index / (points - 1);
    const ratio = (value: number) => high - low < 1e-12 ? 0.5 : (value - low) / (high - low);
    const y = (value: number) => TOP + plotHeight - plotHeight * ratio(value);

    const spots: Spot[] = [];
    const paths: string[] = [];
    props.series.forEach((one, s) => {
      let path = '';
      let drawing = false;
      one.points.forEach((value, i) => {
        if (Number.isNaN(value)) {
          drawing = false;
          return;
        }
        const px = x(i);
        const py = y(value);
        path += (drawing ? 'L' : 'M') + px.toFixed(2) + ' ' + py.toFixed(2);
        drawing = true;
        spots.push({ series: s, index: i, x: px, y: py, value });
      });
      paths.push(path);
    });
    return { low, high, plotHeight, x, spots, paths, LEFT };
  }, [signature, width, height, points]);

  const LEFT = layout.LEFT;
  const drawable = width > LEFT + RIGHT && height > TOP + BOTTOM;

  const onMove = (event: React.MouseEvent<SVGSVGElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    const px = event.clientX - box.left;
    const py = event.clientY - box.top;
    let nearest: Spot | null = null;
    let best = HIT * HIT;
    for (const spot of layout.spots) {
      const distance = (spot.x - px) ** 2 + (spot.y - py) ** 2;
      if (distance <= best) {
        best = distance;
        nearest = spot;
      }
    }
    const next = nearest === null ? null : { series: nearest.series, index: nearest.index };
    if (next?.series !== hover?.series || next?.index !== hover?.index) {
      setHover(next);
    }
  };

  const hovered = hover === null ? null
    : layout.spots.find((spot) => spot.series === hover.series && spot.index === hover.index) ?? null;
  const together = hovered === null ? []
    : layout.spots.filter((spot) => spot.index === hovered.index && Math.abs(spot.y - hovered.y) <= DOT);

  const step = Math.max(1, Math.ceil(points / 8));
  const color = (token: string) => 'var(--gm-' + token + ')';

  return (
    <div className="chart" ref={holder} style={{ height }}>
      {drawable && (
        <svg width={width} height={height} onMouseMove={onMove} onMouseLeave={() => setHover(null)}>
          {points === 0 ? (
            <text x={width / 2} y={height / 2} textAnchor="middle" fontSize={12} fill={color('tx-3')}>
              {props.emptyMessage}
            </text>
          ) : (
            <>
              {Array.from({ length: GRID_LINES }, (_, i) => {
                const value = layout.low + (layout.high - layout.low) * i / (GRID_LINES - 1);
                const gy = Math.round(TOP + layout.plotHeight - layout.plotHeight * i / (GRID_LINES - 1)) + 0.5;
                return (
                  <g key={i}>
                    <line x1={LEFT} y1={gy} x2={width - RIGHT} y2={gy} stroke={color('line')} strokeWidth={1} />
                    <text x={LEFT - 6} y={gy + 3} textAnchor="end" fontSize={9}
                          style={{ fontFamily: 'var(--gm-mono)' }} fill={color('tx-3')}>
                      {props.yLabel(value)}
                    </text>
                  </g>
                );
              })}
              {props.xLabels.map((label, i) => i < points && i % step === 0 && (
                <text key={i} x={layout.x(i)} y={height - 5} textAnchor="middle" fontSize={9}
                      style={{ fontFamily: 'var(--gm-mono)' }} fill={color('tx-3')}>
                  {label}
                </text>
              ))}
              {props.series.map((one, s) => {
                const mine = layout.spots.filter((spot) => spot.series === s);
                const last = mine.length > 0 && mine[mine.length - 1].index === lastDrawn(one.points)
                  ? mine[mine.length - 1] : null;
                return (
                  <g key={s}>
                    <path d={layout.paths[s]} fill="none" stroke={color(one.color)} strokeWidth={1.6}
                          strokeLinejoin="round" />
                    {mine.map((spot) => (
                      <circle key={spot.index} cx={spot.x} cy={spot.y} r={DOT} fill={color(one.color)} />
                    ))}
                    {last && (
                      <>
                        <circle cx={last.x} cy={last.y} r={3} fill={color(one.color)} />
                        <circle cx={last.x} cy={last.y} r={1.4} fill={color('surface')} />
                      </>
                    )}
                  </g>
                );
              })}
              {together.map((spot) => (
                <g key={'h' + spot.series}>
                  <circle cx={spot.x} cy={spot.y} r={RING} fill={color('surface')}
                          stroke={color(props.series[spot.series].color)} strokeWidth={1.6} />
                  <circle cx={spot.x} cy={spot.y} r={DOT} fill={color(props.series[spot.series].color)} />
                </g>
              ))}
            </>
          )}
        </svg>
      )}
      {hovered !== null && (
        <Tip spots={together} anchor={hovered} series={props.series} yLabel={props.yLabel}
             when={props.stamps?.[hovered.index] ?? ''} width={width} height={height} />
      )}
    </div>
  );
}

/** The index of the last plotted (non-NaN) point, where the series has got to. */
function lastDrawn(points: number[]): number {
  for (let i = points.length - 1; i >= 0; i--) {
    if (!Number.isNaN(points[i])) {
      return i;
    }
  }
  return -1;
}

/**
 * The popup beside a hovered dot: above and to the right, and on whichever other side it
 * would otherwise hang off the plot. Measured after it is drawn, since where it goes
 * depends on how wide the figures made it.
 */
function Tip(props: {
  spots: Spot[];
  anchor: Spot;
  series: Series[];
  yLabel: (value: number) => string;
  when: string;
  width: number;
  height: number;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [place, setPlace] = useState<{ left: number; top: number } | null>(null);
  const key = props.anchor.series + ':' + props.anchor.index;

  useEffect(() => {
    const element = box.current;
    if (element === null) {
      return;
    }
    const w = element.offsetWidth;
    const h = element.offsetHeight;
    let px = props.anchor.x + 10;
    if (px + w > props.width) {
      px = props.anchor.x - 10 - w;
    }
    let py = props.anchor.y - h - 8;
    if (py < 0) {
      py = props.anchor.y + 10;
    }
    const clamp = (value: number, most: number) => Math.max(0, Math.min(value, Math.max(0, most)));
    setPlace({ left: clamp(px, props.width - w), top: clamp(py, props.height - h) });
  }, [key, props.width, props.height, props.anchor.x, props.anchor.y]);

  return (
    <div className="chart-tip" ref={box}
         style={{ left: place?.left ?? 0, top: place?.top ?? 0, visibility: place ? 'visible' : 'hidden' }}>
      {props.spots.map((spot) => (
        <div key={spot.series} className="value">
          {props.series[spot.series].name + '   ' + props.yLabel(spot.value)}
        </div>
      ))}
      {props.when !== '' && <div className="when">{props.when}</div>}
    </div>
  );
}

/** The legend the design puts in a panel header: a dot and the series name. */
export function Legend(props: { name: string; color: string }) {
  return (
    <span className="legend">
      <span className="dot" style={{ background: 'var(--gm-' + props.color + ')' }} />
      <span>{props.name}</span>
    </span>
  );
}

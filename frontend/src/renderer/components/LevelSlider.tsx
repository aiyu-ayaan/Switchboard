import { Minus, Plus } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useEffect, useState } from 'react';

interface LevelSliderProps {
  icon: LucideIcon;
  label: string;
  value: number;
  min: number;
  max: number;
  disabled?: boolean;
  /** Fired continuously while dragging. */
  onChange: (value: number) => void;
  /** Fired once the gesture ends, for a final authoritative write. */
  onCommit?: (value: number) => void;
  onGestureChange?: (dragging: boolean) => void;
}

/**
 * A labelled level control.
 *
 * Built on a native range input so arrow keys, Home/End and Page Up/Down work
 * without reimplementation, and paired with step buttons: WCAG 2.2 requires a
 * single-pointer alternative to any dragging interaction.
 */
export function LevelSlider({
  icon: Icon,
  label,
  value,
  min,
  max,
  disabled = false,
  onChange,
  onCommit,
  onGestureChange
}: LevelSliderProps) {
  // Mirrors the prop so the thumb tracks the pointer even before the daemon
  // acknowledges the write.
  const [local, setLocal] = useState(value);
  useEffect(() => setLocal(value), [value]);

  const step = Math.max(1, Math.round((max - min) / 100));
  const percent = max > min ? Math.round(((local - min) / (max - min)) * 100) : 0;

  const clamp = (next: number) => Math.min(max, Math.max(min, next));

  const apply = (next: number) => {
    const bounded = clamp(next);
    setLocal(bounded);
    onChange(bounded);
  };

  const nudge = (delta: number) => {
    const next = clamp(local + delta);
    setLocal(next);
    onChange(next);
    onCommit?.(next);
  };

  return (
    <div className="flex items-center gap-3">
      <Icon aria-hidden="true" className="h-4 w-4 shrink-0 text-ink-dim" />

      <button
        type="button"
        onClick={() => nudge(-step)}
        disabled={disabled || local <= min}
        aria-label={`Decrease ${label}`}
        className="rounded p-1 text-ink-faint transition-colors hover:bg-raised hover:text-ink disabled:pointer-events-none disabled:opacity-30"
      >
        <Minus aria-hidden="true" className="h-3.5 w-3.5" />
      </button>

      <input
        type="range"
        className="level-slider"
        min={min}
        max={max}
        step={1}
        value={local}
        disabled={disabled}
        aria-label={label}
        aria-valuetext={`${percent} percent`}
        onChange={(e) => apply(Number(e.target.value))}
        onPointerDown={() => onGestureChange?.(true)}
        onPointerUp={() => {
          onGestureChange?.(false);
          onCommit?.(local);
        }}
        onBlur={() => onGestureChange?.(false)}
        onKeyUp={() => onCommit?.(local)}
        style={{
          background: `linear-gradient(to right, #9ece6a 0%, #9ece6a ${percent}%, #292b38 ${percent}%, #292b38 100%)`
        }}
      />

      <button
        type="button"
        onClick={() => nudge(step)}
        disabled={disabled || local >= max}
        aria-label={`Increase ${label}`}
        className="rounded p-1 text-ink-faint transition-colors hover:bg-raised hover:text-ink disabled:pointer-events-none disabled:opacity-30"
      >
        <Plus aria-hidden="true" className="h-3.5 w-3.5" />
      </button>

      {/* Tabular figures keep the row from twitching as the number changes. */}
      <span className="w-11 shrink-0 text-right font-mono text-tiny tabular-nums text-ink-dim">
        {percent}%
      </span>
    </div>
  );
}

import { useEffect, useState } from 'react';
import { Platform } from 'react-native';

/** How far the visible area sits inside the page: above it (`top`) and below it (`bottom`). */
export type ViewportGap = { top: number; bottom: number };

export const NO_VIEWPORT_GAP: ViewportGap = Object.freeze({ top: 0, bottom: 0 });

type VisualViewportLike = { height: number; offsetTop: number; scale?: number };

/**
 * iOS Safari doesn't resize the page for the on-screen keyboard: the layout viewport (what a
 * full-screen fixed layer like a sheet's Modal covers) keeps its height, and only the visual
 * viewport shrinks - and may pan down - so a bottom sheet ends up behind the keyboard. The gap
 * is how much of the layer is hidden above and below the visible area; padding the layer by it
 * puts the sheet right above the keyboard. Browsers that resize the page (Android Chrome) report
 * no gap. A pinch-zoomed page reports none either: that is the user's zoom, not a keyboard.
 */
export function visualViewportGap(layoutHeight: number, viewport: VisualViewportLike | null | undefined): ViewportGap {
  if (!viewport || (viewport.scale ?? 1) > 1.01) return NO_VIEWPORT_GAP;
  const top = Math.max(0, Math.round(viewport.offsetTop));
  const bottom = Math.max(0, Math.round(layoutHeight - viewport.height - viewport.offsetTop));
  return top === 0 && bottom === 0 ? NO_VIEWPORT_GAP : { top, bottom };
}

/**
 * Web: the live visual-viewport gap (window.visualViewport resize/scroll), while `enabled`.
 * Native, or a browser without visualViewport: always no gap - native sheets use
 * KeyboardAvoidingView instead.
 */
export function useWebKeyboardViewport(enabled = true, os: string = Platform.OS): ViewportGap {
  const [gap, setGap] = useState<ViewportGap>(NO_VIEWPORT_GAP);

  useEffect(() => {
    const browser = typeof window !== 'undefined';
    if (!enabled || os !== 'web' || !browser || !window.visualViewport) return;
    const win = window;
    const viewport = win.visualViewport!;
    const update = () => {
      const next = visualViewportGap(win.innerHeight, viewport);
      setGap((prev) => (prev.top === next.top && prev.bottom === next.bottom ? prev : next));
    };
    update();
    viewport.addEventListener('resize', update);
    viewport.addEventListener('scroll', update);
    return () => {
      viewport.removeEventListener('resize', update);
      viewport.removeEventListener('scroll', update);
    };
  }, [enabled, os]);

  return enabled ? gap : NO_VIEWPORT_GAP;
}

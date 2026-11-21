/**
 * Tipos mínimos de la YouTube IFrame Player API.
 * Se declaran aquí para no depender de @types/youtube.
 */

export const YOUTUBE_ID = /^[\w-]{11}$/;

export const YT_STATE = {
  UNSTARTED: -1,
  ENDED: 0,
  PLAYING: 1,
  PAUSED: 2,
  BUFFERING: 3,
  CUED: 5,
} as const;

export interface YtPlayer {
  getCurrentTime(): number;
  getDuration(): number;
  destroy(): void;
}

export interface YtPlayerOptions {
  videoId: string;
  host?: string;
  width?: string | number;
  height?: string | number;
  playerVars?: Record<string, string | number>;
  events?: {
    onReady?: (event: { target: YtPlayer }) => void;
    onStateChange?: (event: { data: number; target: YtPlayer }) => void;
    onError?: (event: { data: number; target: YtPlayer }) => void;
  };
}

export interface YtNamespace {
  Player: new (
    element: HTMLElement | string,
    options: YtPlayerOptions
  ) => YtPlayer;
}

declare global {
  interface Window {
    YT?: YtNamespace;
    onYouTubeIframeAPIReady?: () => void;
  }
}

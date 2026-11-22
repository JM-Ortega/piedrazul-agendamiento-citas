import { Injectable } from '@angular/core';
import { YtNamespace } from '../../shared/helpers/youtube';

const YT_API_SRC = 'https://www.youtube.com/iframe_api';
const API_TIMEOUT_MS = 10_000;
const COOKIE_PREFIX = 'piedrazul_video_visto_';
const COOKIE_DAYS = 365;

/** Fracción del video que debe reproducirse para considerarlo "visto". */
export const MIN_WATCHED_RATIO = 0.8;

/**
 * Apoyo para los videos de ayuda:
 * - Recuerda en una cookie (una por video) si el usuario ya vio un video
 *   que se abre automáticamente (`helpVideoAutoOpen` en las rutas).
 * - Carga una sola vez la YouTube IFrame Player API.
 *
 * Como la cookie lleva el ID del video, si se cambia el video en las rutas
 * todos lo verán de nuevo.
 */
@Injectable({ providedIn: 'root' })
export class HelpVideoService {
  private apiPromise?: Promise<YtNamespace>;

  /** True si ya vio el video (≥ 80 %) o pidió no volver a mostrarlo. */
  hasWatched(videoId: string): boolean {
    try {
      const name = COOKIE_PREFIX + videoId;
      return document.cookie
        .split(';')
        .some((c) => c.trim().startsWith(`${name}=1`));
    } catch {
      return false;
    }
  }

  /** Guarda la cookie para no volver a abrir el video automáticamente. */
  markAsWatched(videoId: string): void {
    const maxAge = COOKIE_DAYS * 24 * 60 * 60;
    // Secure solo en HTTPS: en http://localhost el navegador descartaría la cookie.
    const secure = window.location.protocol === 'https:' ? '; Secure' : '';
    try {
      document.cookie = `${COOKIE_PREFIX}${videoId}=1; Max-Age=${maxAge}; Path=/; SameSite=Lax${secure}`;
    } catch {
      // cookies bloqueadas: el video se volverá a abrir, sin romper la app
    }
  }

  /** Carga la YouTube IFrame API una sola vez y la reutiliza. */
  loadYoutubeApi(): Promise<YtNamespace> {
    if (window.YT?.Player) return Promise.resolve(window.YT);
    if (this.apiPromise) return this.apiPromise;

    this.apiPromise = new Promise<YtNamespace>((resolve, reject) => {
      const fail = (reason: string) => {
        window.clearTimeout(timeout);
        this.apiPromise = undefined; // permite reintentar más tarde
        reject(new Error(reason));
      };
      const timeout = window.setTimeout(
        () => fail('Tiempo de espera agotado al cargar YouTube'),
        API_TIMEOUT_MS
      );

      const previous = window.onYouTubeIframeAPIReady;
      window.onYouTubeIframeAPIReady = () => {
        previous?.();
        window.clearTimeout(timeout);
        if (window.YT?.Player) resolve(window.YT);
        else fail('YouTube API no disponible');
      };

      if (!document.querySelector(`script[src="${YT_API_SRC}"]`)) {
        const script = document.createElement('script');
        script.src = YT_API_SRC;
        script.async = true;
        script.onerror = () => {
          script.remove();
          fail('No se pudo cargar YouTube (¿bloqueador de anuncios?)');
        };
        document.head.appendChild(script);
      }
    });

    return this.apiPromise;
  }
}

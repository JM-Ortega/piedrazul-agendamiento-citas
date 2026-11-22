import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  NgZone,
  Signal,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import {
  LucideCircleHelp,
  LucideMaximize2,
  LucideMinimize2,
  LucidePlayCircle,
  LucideX,
} from '@lucide/angular';
import Keycloak from 'keycloak-js';
import { filter, map } from 'rxjs';
import {
  HelpVideoService,
  MIN_WATCHED_RATIO,
} from '../../../core/services/helpVideo.service';
import {
  YOUTUBE_ID,
  YT_STATE,
  YtPlayer,
} from '../../../shared/helpers/youtube';
import { ButtonComponent } from '../../atoms/button/button.component';

/** Cada cuánto se muestrea la posición del video (ms). Bajo para soportar 2x. */
const SAMPLE_MS = 250;

/**
 * Widget de ayuda con video de YouTube, configurado desde `data` de las rutas:
 *
 * - `helpVideoId`        ID del video (obligatorio para mostrar el widget).
 * - `helpVideoAutoOpen`  Si es true, el video se abre solo al entrar, salvo que
 *                        el usuario tenga sesión o ya lo haya visto (cookie).
 *                        Se considera visto al reproducir el 80 % del video.
 * - `helpVideoTitle`     Título del reproductor (por defecto "Video de ayuda").
 * - `helpVideoMessage`   Texto que se muestra al abrirse automáticamente.
 */
@Component({
  selector: 'app-help-widget',
  imports: [
    LucideCircleHelp,
    LucideMaximize2,
    LucideMinimize2,
    LucidePlayCircle,
    LucideX,
    ButtonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:mousedown)': 'onDocumentMouseDown($event)',
    '(document:keydown.escape)': 'onEscape()',
  },
  templateUrl: './helpWidget.component.html',
})
export class HelpWidgetComponent {
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly keycloak = inject(Keycloak);
  private readonly helpVideo = inject(HelpVideoService);
  private readonly ngZone = inject(NgZone);

  protected readonly showPopover = signal(false);
  protected readonly showVideo = signal(false);
  protected readonly expanded = signal(false);
  protected readonly offset = signal({ x: 0, y: 0 });
  /** True si el video se abrió solo (no por clic del usuario). */
  protected readonly autoOpened = signal(false);
  /** True si ya alcanzó el 80 % o lo había visto antes. */
  protected readonly completed = signal(false);
  protected readonly loadError = signal(false);

  private drag: {
    startX: number;
    startY: number;
    origin: { x: number; y: number };
    minDx: number;
    maxDx: number;
    minDy: number;
    maxDy: number;
  } | null = null;

  private readonly navigationEnd$ = this.router.events.pipe(
    filter((e): e is NavigationEnd => e instanceof NavigationEnd)
  );

  protected readonly videoId = this.routeData<string | null>(
    'helpVideoId',
    null,
    (id) => (typeof id === 'string' && YOUTUBE_ID.test(id) ? id : null)
  );
  protected readonly autoOpenRoute = this.routeData(
    'helpVideoAutoOpen',
    false,
    (v) => v === true
  );
  protected readonly title = this.routeData(
    'helpVideoTitle',
    'Video de ayuda',
    (v) => (typeof v === 'string' && v ? v : 'Video de ayuda')
  );
  protected readonly message = this.routeData('helpVideoMessage', '', (v) =>
    typeof v === 'string' ? v : ''
  );

  protected readonly watchUrl = computed(
    () => `https://www.youtube.com/watch?v=${this.videoId()}`
  );

  private readonly playerHost =
    viewChild<ElementRef<HTMLElement>>('playerHost');
  private player?: YtPlayer;
  private playerToken = 0;
  private sampleId?: number;
  private readonly watchedSeconds = new Set<number>();

  constructor() {
    // Al navegar a una pantalla con otro video (o sin video), se cierra el
    // actual y, si la ruta lo pide, se abre solo el nuevo.
    effect(() => {
      const id = this.videoId();
      const autoOpen = this.autoOpenRoute();
      untracked(() => {
        this.showPopover.set(false);
        this.closeVideo();
        if (
          id &&
          autoOpen &&
          !this.keycloak.authenticated &&
          !this.helpVideo.hasWatched(id)
        ) {
          this.openVideo(true);
        }
      });
    });

    // Cuando el contenedor del reproductor aparece en el DOM, se crea el player.
    effect(() => {
      const hostEl = this.playerHost();
      if (!hostEl) return;
      untracked(() => {
        const id = this.videoId();
        if (id) this.createPlayer(hostEl.nativeElement, id);
      });
    });

    inject(DestroyRef).onDestroy(() => this.destroyPlayer());
  }

  protected openVideo(auto = false): void {
    const id = this.videoId();
    this.autoOpened.set(auto);
    // Siempre abre en modo flotante (esquina superior derecha); el usuario
    // puede ampliarlo con el botón de ampliar.
    this.expanded.set(false);
    this.loadError.set(false);
    this.completed.set(!!id && this.helpVideo.hasWatched(id));
    this.showPopover.set(false);
    this.showVideo.set(true);
  }

  protected closeVideo(): void {
    this.destroyPlayer();
    this.showVideo.set(false);
    this.expanded.set(false);
    this.autoOpened.set(false);
    this.offset.set({ x: 0, y: 0 });
  }

  protected dontShowAgain(): void {
    const id = this.videoId();
    if (id) this.helpVideo.markAsWatched(id);
    this.closeVideo();
  }

  protected startDrag(event: PointerEvent, player: HTMLElement): void {
    if (this.expanded()) return;
    if ((event.target as HTMLElement).closest('button')) return;
    const rect = player.getBoundingClientRect();
    this.drag = {
      startX: event.clientX,
      startY: event.clientY,
      origin: this.offset(),
      minDx: -rect.left,
      maxDx: window.innerWidth - rect.right,
      minDy: -rect.top,
      maxDy: window.innerHeight - rect.bottom,
    };
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }

  protected onDrag(event: PointerEvent): void {
    const d = this.drag;
    if (!d) return;
    const dx = Math.min(Math.max(event.clientX - d.startX, d.minDx), d.maxDx);
    const dy = Math.min(Math.max(event.clientY - d.startY, d.minDy), d.maxDy);
    this.offset.set({ x: d.origin.x + dx, y: d.origin.y + dy });
  }

  protected endDrag(): void {
    this.drag = null;
  }

  protected onEscape(): void {
    if (this.expanded()) this.expanded.set(false);
    else if (this.showVideo()) this.closeVideo();
    else this.showPopover.set(false);
  }

  protected onDocumentMouseDown(event: MouseEvent): void {
    if (
      this.showPopover() &&
      !this.host.nativeElement.contains(event.target as Node)
    ) {
      this.showPopover.set(false);
    }
  }

  // ---------- Reproductor de YouTube ----------

  private async createPlayer(element: HTMLElement, videoId: string) {
    const token = ++this.playerToken;
    try {
      const YT = await this.helpVideo.loadYoutubeApi();
      // Si se cerró o cambió de video mientras cargaba la API, no se crea.
      if (token !== this.playerToken || !this.showVideo()) return;

      this.player = new YT.Player(element, {
        host: 'https://www.youtube-nocookie.com',
        videoId,
        width: '100%',
        height: '100%',
        playerVars: {
          // Abierto por clic: arranca solo. Abierto automáticamente: listo
          // para reproducir (además los navegadores bloquean el autoplay).
          autoplay: this.autoOpened() ? 0 : 1,
          rel: 0,
          playsinline: 1,
          origin: window.location.origin,
        },
        events: {
          onStateChange: (e) => this.onStateChange(e.data),
          onError: () => this.ngZone.run(() => this.loadError.set(true)),
        },
      });
    } catch {
      if (token === this.playerToken) this.loadError.set(true);
    }
  }

  private destroyPlayer(): void {
    this.playerToken++;
    this.stopSampling();
    this.player?.destroy();
    this.player = undefined;
    this.watchedSeconds.clear();
  }

  private onStateChange(state: number): void {
    if (state === YT_STATE.PLAYING) this.startSampling();
    else this.stopSampling();
  }

  /** Solo se mide el progreso en rutas con apertura automática. */
  private startSampling(): void {
    if (this.sampleId !== undefined) return;
    if (!this.autoOpenRoute() || this.completed()) return;
    // Fuera de la zona para no disparar detección de cambios cada 250 ms.
    this.ngZone.runOutsideAngular(() => {
      this.sampleId = window.setInterval(() => this.sample(), SAMPLE_MS);
    });
  }

  private stopSampling(): void {
    if (this.sampleId === undefined) return;
    window.clearInterval(this.sampleId);
    this.sampleId = undefined;
  }

  /**
   * Registra los segundos distintos que se reprodujeron: adelantar el video
   * no cuenta. Al llegar al 80 % guarda la cookie.
   */
  private sample(): void {
    const id = this.videoId();
    if (!this.player || !id) return;
    const duration = this.player.getDuration();
    if (!duration) return;

    this.watchedSeconds.add(Math.floor(this.player.getCurrentTime()));
    if (this.watchedSeconds.size / Math.ceil(duration) >= MIN_WATCHED_RATIO) {
      this.stopSampling();
      this.helpVideo.markAsWatched(id);
      this.ngZone.run(() => this.completed.set(true));
    }
  }

  // ---------- Datos de la ruta ----------

  /** Signal con un valor de `data` de la ruta activa, actualizado al navegar. */
  private routeData<T>(
    key: string,
    initial: T,
    parse: (value: unknown) => T
  ): Signal<T> {
    return toSignal(
      this.navigationEnd$.pipe(map(() => parse(this.findRouteData(key)))),
      { initialValue: initial }
    );
  }

  /** Busca `key` en la ruta activa; la ruta más profunda tiene prioridad. */
  private findRouteData(key: string): unknown {
    let route = this.router.routerState.snapshot.root;
    let value: unknown = route.data[key];
    while (route.firstChild) {
      route = route.firstChild;
      value = route.data[key] ?? value;
    }
    return value;
  }
}

import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { DomSanitizer } from '@angular/platform-browser';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import {
  LucideCircleHelp,
  LucideMaximize2,
  LucideMinimize2,
  LucidePlayCircle,
  LucideX,
} from '@lucide/angular';
import { ButtonComponent } from '../../atoms/button/button.component';

const YOUTUBE_ID = /^[\w-]{11}$/;

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
  private readonly sanitizer = inject(DomSanitizer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly showPopover = signal(false);
  protected readonly showVideo = signal(false);
  protected readonly expanded = signal(false);
  protected readonly offset = signal({ x: 0, y: 0 });
  private drag: {
    startX: number;
    startY: number;
    origin: { x: number; y: number };
    minDx: number;
    maxDx: number;
    minDy: number;
    maxDy: number;
  } | null = null;

  protected readonly videoId = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.findHelpVideoId())
    ),
    { initialValue: null }
  );

  protected readonly videoUrl = computed(() => {
    const id = this.videoId();
    if (!id || !YOUTUBE_ID.test(id)) return null;
    return this.sanitizer.bypassSecurityTrustResourceUrl(
      `https://www.youtube-nocookie.com/embed/${id}?autoplay=1&rel=0`
    );
  });

  constructor() {
    // Al navegar a una pantalla con otro video (o sin video), se cierra el actual
    effect(() => {
      this.videoId();
      this.showPopover.set(false);
      this.closeVideo();
    });
  }

  protected openVideo(): void {
    this.showVideo.set(true);
    this.showPopover.set(false);
  }

  protected closeVideo(): void {
    this.showVideo.set(false);
    this.expanded.set(false);
    this.offset.set({ x: 0, y: 0 });
  }

  protected startDrag(event: PointerEvent, player: HTMLElement): void {
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

  private findHelpVideoId(): string | null {
    let route = this.router.routerState.snapshot.root;
    let id: string | null = route.data['helpVideoId'] ?? null;
    while (route.firstChild) {
      route = route.firstChild;
      id = route.data['helpVideoId'] ?? id;
    }
    return id;
  }
}

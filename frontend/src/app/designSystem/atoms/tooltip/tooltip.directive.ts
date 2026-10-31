import {
  Directive,
  ElementRef,
  HostListener,
  inject,
  Input,
  OnDestroy,
  Renderer2,
} from '@angular/core';

type TooltipPosition = 'top' | 'bottom' | 'left' | 'right';

/**
 * Átomo de Tooltip del Design System. Se aplica a cualquier elemento con
 * [appTooltip]="'texto'". Se renderiza en document.body (no dentro del
 * elemento) para no cortarse por overflow-hidden de contenedores padres.
 */
@Directive({
  selector: '[appTooltip]',
  standalone: true,
})
export class TooltipDirective implements OnDestroy {
  @Input('appTooltip') text = '';
  @Input() appTooltipPosition: TooltipPosition = 'top';
  @Input() appTooltipDelay = 300;

  private host = inject(ElementRef<HTMLElement>);
  private renderer = inject(Renderer2);

  private tooltipEl: HTMLElement | null = null;
  private arrowEl: HTMLElement | null = null;
  private showTimeout: ReturnType<typeof setTimeout> | null = null;

  @HostListener('mouseenter')
  onMouseEnter(): void {
    if (!this.text) return;
    this.clearTimers();
    this.showTimeout = setTimeout(() => this.show(), this.appTooltipDelay);
  }

  @HostListener('mouseleave')
  @HostListener('click')
  onLeave(): void {
    this.clearTimers();
    this.destroyTooltip();
  }

  ngOnDestroy(): void {
    this.clearTimers();
    this.destroyTooltip();
  }

  private clearTimers(): void {
    if (this.showTimeout) {
      clearTimeout(this.showTimeout);
      this.showTimeout = null;
    }
  }

  private show(): void {
    if (this.tooltipEl) return;

    this.tooltipEl = this.renderer.createElement('div');
    this.arrowEl = this.renderer.createElement('div');

    this.renderer.setProperty(this.tooltipEl, 'textContent', this.text);
    this.renderer.setAttribute(this.tooltipEl, 'role', 'tooltip');

    this.renderer.addClass(this.tooltipEl, 'app-tooltip');
    this.renderer.addClass(this.arrowEl, 'app-tooltip-arrow');

    this.renderer.appendChild(this.tooltipEl, this.arrowEl);
    this.renderer.appendChild(document.body, this.tooltipEl);

    this.position();

    requestAnimationFrame(() => {
      this.tooltipEl?.classList.add('app-tooltip-visible');
    });

    window.addEventListener('scroll', this.reposition, true);
    window.addEventListener('resize', this.reposition);
  }

  private reposition = (): void => this.position();

  private position(): void {
    if (!this.tooltipEl) return;

    const hostRect = this.host.nativeElement.getBoundingClientRect();
    const tipRect = this.tooltipEl.getBoundingClientRect();
    const gap = 8;
    const arrowSize = 6;

    let top = 0;
    let left = 0;
    let arrowClass = '';

    switch (this.appTooltipPosition) {
      case 'top':
        top = hostRect.top - tipRect.height - gap;
        left = hostRect.left + hostRect.width / 2 - tipRect.width / 2;
        arrowClass = 'app-tooltip-arrow-bottom';
        break;
      case 'bottom':
        top = hostRect.bottom + gap;
        left = hostRect.left + hostRect.width / 2 - tipRect.width / 2;
        arrowClass = 'app-tooltip-arrow-top';
        break;
      case 'left':
        top = hostRect.top + hostRect.height / 2 - tipRect.height / 2;
        left = hostRect.left - tipRect.width - gap;
        arrowClass = 'app-tooltip-arrow-right';
        break;
      case 'right':
        top = hostRect.top + hostRect.height / 2 - tipRect.height / 2;
        left = hostRect.right + gap;
        arrowClass = 'app-tooltip-arrow-left';
        break;
    }

    left = Math.max(
      gap,
      Math.min(left, window.innerWidth - tipRect.width - gap)
    );

    this.renderer.setStyle(this.tooltipEl, 'top', `${top + window.scrollY}px`);
    this.renderer.setStyle(
      this.tooltipEl,
      'left',
      `${left + window.scrollX}px`
    );

    if (this.arrowEl) {
      this.arrowEl.className = `app-tooltip-arrow ${arrowClass}`;
      const arrowLeft = hostRect.left + hostRect.width / 2 - left - arrowSize;
      if (
        this.appTooltipPosition === 'top' ||
        this.appTooltipPosition === 'bottom'
      ) {
        this.renderer.setStyle(this.arrowEl, 'left', `${arrowLeft}px`);
      }
    }
  }

  private destroyTooltip(): void {
    window.removeEventListener('scroll', this.reposition, true);
    window.removeEventListener('resize', this.reposition);
    if (this.tooltipEl) {
      this.renderer.removeChild(document.body, this.tooltipEl);
      this.tooltipEl = null;
      this.arrowEl = null;
    }
  }
}

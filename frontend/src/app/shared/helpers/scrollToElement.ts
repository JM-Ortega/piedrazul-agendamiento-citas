/**
 * Hace scroll animado (manual, no scroll nativo) hacia un elemento por id,
 * dejándolo justo debajo de un header sticky.
 *
 * Resuelve tres problemas del scroll nativo `smooth` en este proyecto:
 *
 * También evita trabajo innecesario: si el elemento ya está completamente
 * visible debajo del header, no anima nada.
 */
export function scrollToElementById(
  elementId: string,
  options: {
    offset?: number;
    stickyHeaderSelector?: string;
    duration?: number;
  } = {}
): void {
  const {
    offset = 12,
    stickyHeaderSelector = 'header',
    duration = 400,
  } = options;

  waitForStableLayout(() => {
    const el = document.getElementById(elementId);
    if (!el) return;

    const header = document.querySelector(stickyHeaderSelector);
    const navbarHeight = header ? header.getBoundingClientRect().height : 80;

    // ── 1. Verificación previa: ¿ya está visible debajo del navbar? ──
    const rect = el.getBoundingClientRect();
    const visibleTop = navbarHeight + offset;
    const alreadyVisible =
      rect.top >= visibleTop && rect.bottom <= window.innerHeight;

    if (alreadyVisible) {
      if (el instanceof HTMLInputElement || el instanceof HTMLSelectElement) {
        el.focus({ preventScroll: true });
      }
      return; // nada que hacer, evita el reflow/animación innecesaria
    }

    const elementTop = rect.top + window.scrollY;
    const targetY = Math.max(elementTop - navbarHeight - offset, 0);

    // ── 2. Espaciador si no hay boundary suficiente ──
    const maxScroll =
      document.documentElement.scrollHeight - window.innerHeight;
    let spacer: HTMLDivElement | null = null;

    if (targetY > maxScroll) {
      spacer = document.createElement('div');
      spacer.style.height = `${targetY - maxScroll}px`;
      spacer.setAttribute('aria-hidden', 'true');
      document.body.appendChild(spacer);
    }

    // ── 3. Animación manual (no scroll nativo) ──
    const startY = window.scrollY;
    const distance = targetY - startY;
    const startTime = performance.now();
    const easeInOutQuad = (t: number) =>
      t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;

    function step(now: number): void {
      const elapsed = now - startTime;
      const progress = Math.min(elapsed / duration, 1);
      window.scrollTo(0, startY + distance * easeInOutQuad(progress));

      if (progress < 1) {
        requestAnimationFrame(step);
      } else {
        spacer?.remove();
        if (el instanceof HTMLInputElement || el instanceof HTMLSelectElement) {
          el.focus({ preventScroll: true });
        }
      }
    }

    requestAnimationFrame(step);
  });
}

/**
 * Espera (vía polling de rAF) a que document.documentElement.scrollHeight
 * deje de cambiar durante varios frames seguidos, indicando que el layout
 * (reflows por contenido insertado, cambios de grid, etc.) ya se asentó.
 * Tiene un tope de frames por seguridad para nunca esperar indefinidamente.
 */
function waitForStableLayout(callback: () => void): void {
  const scrollingEl = document.scrollingElement || document.documentElement;
  let lastHeight = -1;
  let stableFrames = 0;
  let totalFrames = 0;
  const REQUIRED_STABLE_FRAMES = 3;
  const MAX_FRAMES = 30; // ~0.5s de margen a 60fps

  function check(): void {
    totalFrames++;
    const height = scrollingEl.scrollHeight;

    if (height === lastHeight) {
      stableFrames++;
    } else {
      stableFrames = 0;
      lastHeight = height;
    }

    if (stableFrames >= REQUIRED_STABLE_FRAMES || totalFrames >= MAX_FRAMES) {
      callback();
    } else {
      requestAnimationFrame(check);
    }
  }

  requestAnimationFrame(check);
}

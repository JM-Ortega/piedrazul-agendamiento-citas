/**
 * Hace scroll suave hacia un elemento por id, esperando a que Angular
 * termine de pintar el DOM (doble rAF), y calculando el offset midiendo
 * directamente la altura real del navbar sticky en ese momento.
 *
 * Si el documento no tiene suficiente altura para scrollear hasta la
 * posición deseada (p. ej. una página corta con poco contenido debajo),
 * se agrega temporalmente un espaciador invisible al final del body para
 * permitir el scroll completo, y se elimina al terminar la animación.
 */
export function scrollToElementById(
  elementId: string,
  options: {
    behavior?: ScrollBehavior;
    offset?: number;
    stickyHeaderSelector?: string;
  } = {}
): void {
  const {
    behavior = 'smooth',
    offset = 12,
    stickyHeaderSelector = 'header',
  } = options;

  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      const el = document.getElementById(elementId);
      if (!el) return;

      const header = document.querySelector(stickyHeaderSelector);
      const navbarHeight = header ? header.getBoundingClientRect().height : 80;

      const elementTop = el.getBoundingClientRect().top + window.scrollY;
      const targetY = Math.max(elementTop - navbarHeight - offset, 0);

      // Si no hay suficiente contenido debajo para llegar al target,
      // agregamos un espaciador temporal al final del documento.
      const maxScroll =
        document.documentElement.scrollHeight - window.innerHeight;
      let spacer: HTMLDivElement | null = null;

      if (targetY > maxScroll) {
        spacer = document.createElement('div');
        spacer.style.height = `${targetY - maxScroll}px`;
        spacer.setAttribute('aria-hidden', 'true');
        document.body.appendChild(spacer);
      }

      window.scrollTo({ top: targetY, behavior });

      if (el instanceof HTMLInputElement || el instanceof HTMLSelectElement) {
        el.focus({ preventScroll: true });
      }

      if (spacer) {
        const removeSpacer = () => spacer?.remove();
        if ('onscrollend' in window) {
          window.addEventListener('scrollend', removeSpacer, { once: true });
        } else {
          setTimeout(removeSpacer, 500);
        }
      }
    });
  });
}

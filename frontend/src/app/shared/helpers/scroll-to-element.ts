/**
 * Hace scroll suave hacia un elemento por id, esperando a que Angular
 * termine de pintar el DOM (doble rAF: primero pintado, luego layout).
 * Si el elemento es un input/select, además le da foco.
 */
export function scrollToElementById(
  elementId: string,
  options: ScrollIntoViewOptions = { behavior: 'smooth', block: 'center' }
): void {
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      const el = document.getElementById(elementId);
      if (!el) return;

      el.scrollIntoView(options);

      if (el instanceof HTMLInputElement || el instanceof HTMLSelectElement) {
        el.focus({ preventScroll: true });
      }
    });
  });
}

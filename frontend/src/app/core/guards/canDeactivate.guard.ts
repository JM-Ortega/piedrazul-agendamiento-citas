import { CanDeactivateFn } from '@angular/router';

/**
 * Contrato que debe implementar un componente para poder usar
 * `unsavedChangesGuard` en su ruta.
 */
export interface CanComponentDeactivate {
  canDeactivate: () => boolean | Promise<boolean>;
}

/**
 * Guard genérico que delega la decisión de salir de la ruta al propio
 * componente. Funciona tanto para navegación programática (routerLink,
 * router.navigate) como para el botón "atrás" del navegador (popstate),
 * ya que Angular Router integra `CanDeactivate` con ambos mecanismos.
 */
export const unsavedChangesGuard: CanDeactivateFn<CanComponentDeactivate> = (
  component
) => component.canDeactivate();

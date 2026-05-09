import { inject } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { AuthGuardData, createAuthGuard } from 'keycloak-angular';

const isAccessAllowed = async ( 
  route: ActivatedRouteSnapshot,
  state: RouterStateSnapshot,
  authData: AuthGuardData,
): Promise<boolean | UrlTree> => {
  const { authenticated, grantedRoles, keycloak } = authData;
  const router = inject(Router);

  // 1. Si no está autenticado → login y redirigir a la ruta original
  if (!authenticated) {
    await keycloak.login({
      redirectUri: window.location.origin + state.url,
    });
    return false;
  }

  // 2. Obtener el rol requerido por la ruta
  const requiredRole = route.data['role'] as string | undefined;

  // 3. Seguridad: si la ruta usa el guard pero no define rol → bloquear acceso
  if (!requiredRole) {
    return router.createUrlTree(['/']);
  }

  // 4. Validar rol contra los realm roles del token
  const hasRole = grantedRoles.realmRoles.includes(requiredRole);

  if (hasRole) return true;

  // 5. No tiene el rol requerido → redirigir
  return router.createUrlTree(['/']);
};

export const AuthGuard: CanActivateFn =
  createAuthGuard<CanActivateFn>(isAccessAllowed);

import { inject, Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivate, Router } from '@angular/router';
import { AppService } from '../services/app.service';

@Injectable({ providedIn: 'root' })
export class AuthGuard implements CanActivate {
  private appService = inject(AppService);
  private router = inject(Router);

  canActivate(route: ActivatedRouteSnapshot): boolean {
    const requiredRole = route.data['role'] as string;
    const currentRole = this.appService.currentRole();
    console.log('Guard - required:', requiredRole, '| current:', currentRole);
    if (currentRole === requiredRole) return true;
    this.router.navigate(['/']);
    return false;
  }
}
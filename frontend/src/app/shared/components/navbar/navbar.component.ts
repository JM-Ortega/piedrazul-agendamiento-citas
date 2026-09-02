import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  OnDestroy,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import {
  NavigationStart,
  Router,
  RouterLink,
  RouterLinkActive,
} from '@angular/router';
import {
  LucideAlertCircle,
  LucideCalendar,
  LucideCalendarDays,
  LucideCheck,
  LucideChevronDown,
  LucideClipboardList,
  LucideDynamicIcon,
  LucideLogOut,
  LucideMenu,
  LucideSettings,
  LucideStethoscope,
  LucideUser,
  LucideUserCog,
  LucideUsers,
  LucideX,
  type LucideIcon,
} from '@lucide/angular';
import Keycloak from 'keycloak-js';
import { AppService } from '../../../core/services/app.service';
import { ButtonComponent } from '../../../design-system/atoms/button/button.component';

@Component({
  selector: 'app-navbar',
  templateUrl: './navbar.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideAlertCircle,
    LucideCalendar,
    LucideCalendarDays,
    LucideCheck,
    LucideChevronDown,
    LucideClipboardList,
    LucideLogOut,
    LucideMenu,
    LucideSettings,
    LucideStethoscope,
    LucideUser,
    LucideUserCog,
    LucideUsers,
    LucideX,
    LucideDynamicIcon,
    RouterLink,
    RouterLinkActive,
    ButtonComponent,
  ],
})
export class NavbarComponent implements OnInit, AfterViewInit, OnDestroy {
  private keycloak = inject(Keycloak);
  appService = inject(AppService);
  private router = inject(Router);
  readonly exactMatch = { exact: true };
  private topBar = viewChild.required<ElementRef<HTMLElement>>('topBar');
  private resizeObserver?: ResizeObserver;

  menuOpen = signal(false);
  showLogoutModal = signal(false);
  showRoleDropdown = signal(false);
  //Cambio del titulo del panel según el rol activo
  panelTitle = computed(() =>
    this.appService.currentRole()
      ? `Panel de ${this.appService.activeRoleLabel()}`
      : 'Piedra Azul Salud'
  );
  panelSubtitle = computed(() =>
    this.appService.currentRole() ? 'Piedra Azul Salud' : 'Centro Médico'
  );

  // Icono y color por rol para el dropdown
  readonly roleConfig: Record<
    string,
    { icon: LucideIcon; color: string; label: string }
  > = {
    ADMIN: {
      icon: LucideSettings,
      color: 'text-purple-600',
      label: 'Administrador',
    },
    SCHEDULER: {
      icon: LucideClipboardList,
      color: 'text-green-600',
      label: 'Agendador',
    },
    DOCTOR: {
      icon: LucideStethoscope,
      color: 'text-blue-600',
      label: 'Médico',
    },
    PATIENT: { icon: LucideUser, color: 'text-orange-600', label: 'Paciente' },
  };

  ngOnInit(): void {
    history.pushState(null, '', location.href);

    this.router.events.subscribe((event) => {
      if (
        event instanceof NavigationStart &&
        event.navigationTrigger === 'popstate' &&
        this.appService.currentRole()
      ) {
        // dejamos que el guard de esa ruta se encargue del popstate.
        if (this.isLeavingProtectedRoute()) return;

        history.pushState(null, '', location.href);
        this.showLogoutModal.set(true);
      }
    });
  }

  /**
   * True si la ruta activa actual (antes de procesar esta navegación)
   * está marcada con `data: { confirmExitLocally: true }`, indicando que
   * el propio componente controla la confirmación de salida.
   */
  private isLeavingProtectedRoute(): boolean {
    let route = this.router.routerState.snapshot.root;
    while (route.firstChild) route = route.firstChild;
    return !!route.data['confirmExitLocally'];
  }

  goToLogin(): void {
    this.keycloak.login({
      redirectUri: window.location.origin + '/',
    });
  }

  toggleRoleDropdown(): void {
    this.showRoleDropdown.update((v) => !v);
  }

  selectRole(role: string): void {
    this.appService.switchRole(role);
    this.showRoleDropdown.set(false);
    this.menuOpen.set(false);
  }

  openLogoutModal(): void {
    this.showLogoutModal.set(true);
    this.menuOpen.set(false);
    this.showRoleDropdown.set(false);
  }

  cancelLogout(): void {
    this.showLogoutModal.set(false);
  }

  confirmLogout(): void {
    this.showLogoutModal.set(false);
    this.appService.logout();
  }
  ngAfterViewInit(): void {
    this.updateNavbarHeightVar();
    this.resizeObserver = new ResizeObserver(() =>
      this.updateNavbarHeightVar()
    );
    this.resizeObserver.observe(this.topBar().nativeElement);
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
  }

  private updateNavbarHeightVar(): void {
    const height = this.topBar().nativeElement.offsetHeight;
    document.documentElement.style.setProperty(
      '--navbar-height',
      `${height}px`
    );
  }
}

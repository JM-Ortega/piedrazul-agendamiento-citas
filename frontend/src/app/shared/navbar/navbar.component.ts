import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
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
import { AppService } from '../../core/services/app.service';

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
  ],
})
export class NavbarComponent implements OnInit {
  appService = inject(AppService);
  private router = inject(Router);
  readonly exactMatch = { exact: true };

  menuOpen = signal(false);
  showLogoutModal = signal(false);
  showRoleDropdown = signal(false);

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
        history.pushState(null, '', location.href);
        this.showLogoutModal.set(true);
      }
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
}

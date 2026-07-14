import {
  Component,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import {
  NavigationStart,
  Router,
  RouterLink,
  RouterLinkActive,
} from '@angular/router';
import {
  AlertCircle,
  Calendar,
  CalendarDays,
  Check,
  ChevronDown,
  ClipboardList,
  FolderOpen,
  Home,
  Hospital,
  LogOut,
  LucideAngularModule,
  Menu,
  Plus,
  Settings,
  Stethoscope,
  User,
  UserCog,
  Users,
  X,
} from 'lucide-angular';
import { AppService } from '../../core/services/app.service';

@Component({
  selector: 'app-navbar',
  templateUrl: './navbar.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.Eager,
  imports: [RouterLink, RouterLinkActive, LucideAngularModule],
})
export class NavbarComponent implements OnInit {
  appService = inject(AppService);
  private router = inject(Router);

  readonly Home = Home;
  readonly Calendar = Calendar;
  readonly Plus = Plus;
  readonly ClipboardList = ClipboardList;
  readonly Stethoscope = Stethoscope;
  readonly Settings = Settings;
  readonly User = User;
  readonly Users = Users;
  readonly LogOut = LogOut;
  readonly Hospital = Hospital;
  readonly Menu = Menu;
  readonly X = X;
  readonly AlertCircle = AlertCircle;
  readonly CalendarDays = CalendarDays;
  readonly ChevronDown = ChevronDown;
  readonly UserCog = UserCog;
  readonly Check = Check;
  readonly FolderOpen = FolderOpen;
  readonly exactMatch = { exact: true };

  menuOpen = signal(false);
  showLogoutModal = signal(false);
  showRoleDropdown = signal(false);

  // Icono y color por rol para el dropdown
  readonly roleConfig: Record<
    string,
    { icon: any; color: string; label: string }
  > = {
    ADMIN: { icon: Settings, color: 'text-purple-600', label: 'Administrador' },
    SCHEDULER: {
      icon: ClipboardList,
      color: 'text-green-600',
      label: 'Agendador',
    },
    DOCTOR: { icon: Stethoscope, color: 'text-blue-600', label: 'Médico' },
    PATIENT: { icon: User, color: 'text-orange-600', label: 'Paciente' },
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

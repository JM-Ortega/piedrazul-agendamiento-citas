import { Component, inject, OnInit, signal } from '@angular/core';
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
  ClipboardList,
  Home,
  Hospital,
  LogOut,
  LucideAngularModule,
  Menu,
  Plus,
  Settings,
  Stethoscope,
  User,
  Users,
  X,
} from 'lucide-angular';
import { AppService } from '../../../services/app.service';

@Component({
  selector: 'app-navbar',
  templateUrl: './navbar.component.html',
  standalone: true,
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
  readonly exactMatch = { exact: true };

  menuOpen = signal(false);
  showLogoutModal = signal(false);

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

  openLogoutModal(): void {
    this.showLogoutModal.set(true);
    this.menuOpen.set(false);
  }

  cancelLogout(): void {
    this.showLogoutModal.set(false);
  }

  confirmLogout(): void {
    this.showLogoutModal.set(false);
    this.appService.logout();
  }
}

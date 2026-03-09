import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import {
  Calendar,
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
  X,
} from 'lucide-angular';
import { AppService } from '../../services/app.service';

@Component({
  selector: 'app-navbar',
  templateUrl: './navbar.component.html',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, LucideAngularModule],
})
export class NavbarComponent {
  appService = inject(AppService);
  private router = inject(Router);

  readonly Home = Home;
  readonly Calendar = Calendar;
  readonly Plus = Plus;
  readonly ClipboardList = ClipboardList;
  readonly Stethoscope = Stethoscope;
  readonly Settings = Settings;
  readonly User = User;
  readonly LogOut = LogOut;
  readonly Hospital = Hospital;
  readonly Menu = Menu;
  readonly X = X;

  menuOpen = signal(false);

  roleLabel = computed<string>(() => {
    switch (this.appService.currentRole()) {
      case 'patient':
        return `${this.appService.currentUser()?.firstName}`;
      case 'scheduler':
        return 'Agendador';
      case 'admin':
        return 'Administrador';
      case 'doctor':
        return `DR`;
      default:
        return '';
    }
  });

  logout(): void {
    this.appService.logout();
    this.router.navigate(['/']);
  }
}

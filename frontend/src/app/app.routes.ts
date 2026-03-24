import { Routes } from '@angular/router';
import { AuthGuard } from './guards/auth.guard';

import { AccesoComponent } from './pages/acceso/acceso.component';
import { AdminConfigComponent } from './pages/admin/admin-config.component';
import { SchedulerDashboardComponent } from './pages/agendador/scheduler-dashboard.component';
import { HomeComponent } from './pages/home/home.component';
import { NewAppointmentSchedulerComponent } from './pages/nueva-cita/new-appointment-scheduler.component';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'acceso', component: AccesoComponent },
  {
    path: 'agendador',
    component: SchedulerDashboardComponent,
    canActivate: [AuthGuard],
    data: { role: 'scheduler' },
  },
  {
    path: 'agendador/nueva',
    component: NewAppointmentSchedulerComponent,
    canActivate: [AuthGuard],
    data: { role: 'scheduler' },
  },
  {
    path: 'admin',
    component: AdminConfigComponent,
    canActivate: [AuthGuard],
    data: { role: 'admin' },
  },
  { path: '**', redirectTo: '' },
];

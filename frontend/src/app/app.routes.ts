import { Routes } from '@angular/router';
import { AuthGuard } from './guards/auth.guard';

import { SchedulerDashboardComponent } from './pages/agendador/scheduler-dashboard.component';
import { NewAppointmentSchedulerComponent } from './pages/nueva-cita/new-appointment-scheduler.component';
import { HomeComponent } from './pages/home/home.component';
export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'agendador', component: SchedulerDashboardComponent },
  {
    path: 'agendador/nueva',
    component: NewAppointmentSchedulerComponent,
    canActivate: [AuthGuard],
    data: { role: 'scheduler' },
  },
  { path: '**', redirectTo: '' },
];

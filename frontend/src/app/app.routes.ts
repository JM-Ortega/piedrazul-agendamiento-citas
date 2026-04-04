import { Routes } from '@angular/router';
import { AuthGuard } from './guards/auth.guard';

import { AccesoComponent } from './pages/acceso/acceso.component';
import { AdminConfigComponent } from './pages/admin/admin-config.component';
import { SchedulerDashboardComponent } from './pages/agendador/scheduler-dashboard.component';
import { HomeComponent } from './pages/home/home.component';
import { NewAppointmentSchedulerComponent } from './pages/nueva-cita/new-appointment-scheduler.component';
import { PatientNewAppointmentComponent } from './pages/paciente-agendar/patient-new-appointment.component';
import { PatientDashboardComponent } from './pages/paciente/patient-dashboard.component';
import { RegistroComponent } from './pages/registro/registro.component';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'acceso', component: AccesoComponent },

  {
    path: 'agendador',
    component: SchedulerDashboardComponent,
    canActivate: [AuthGuard],
    data: { role: 'SCHEDULER' },
  },
  {
    path: 'agendador/nueva',
    component: NewAppointmentSchedulerComponent,
    canActivate: [AuthGuard],
    data: { role: 'SCHEDULER' },
  },
  {
    path: 'admin',
    component: AdminConfigComponent,
    canActivate: [AuthGuard],
    data: { role: 'ADMIN' },
  },
  {
    path: 'paciente/agendar',
    component: PatientNewAppointmentComponent,
    canActivate: [AuthGuard],
    data: { role: 'PATIENT' },
  },
  {
    path: 'paciente',
    component: PatientDashboardComponent,
    canActivate: [AuthGuard],
    data: { role: 'PATIENT' },
  },
  { path: 'registro', component: RegistroComponent },

  { path: '**', redirectTo: '' },
];

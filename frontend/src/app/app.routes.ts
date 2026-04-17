import { Routes } from '@angular/router';
import { AuthGuard } from './guards/auth.guard';

import { AccesoComponent } from './design-system/pages/acceso/acceso.component';
import { AdminConfigComponent } from './features/admin/admin-config.component';
import { SchedulerDashboardComponent } from './features/appointment/pages/agendador-listar-citas/scheduler-dashboard.component';
import { HomeComponent } from './design-system/pages/home/home.component';
import { NewAppointmentSchedulerComponent } from './features/appointment/pages/agendador-agendar/new-appointment-scheduler.component';
import { PatientNewAppointmentComponent } from './features/appointment/pages/paciente-agendar/patient-new-appointment.component';
import { PatientDashboardComponent } from './features/appointment/pages/paciente-listar-citas/patient-dashboard.component';
import { RegistroComponent } from './features/registro/registro.component';

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

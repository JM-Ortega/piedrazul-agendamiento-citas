import { Routes } from '@angular/router';
import { AuthGuard } from './guards/auth.guard';

import { AccesoComponent } from './design-system/pages/acceso/acceso.component';
import { HomeComponent } from './design-system/pages/home/home.component';
import { AdminUsersComponent } from './features/admin-users/admin-users.component';
import { AdminConfigComponent } from './features/admin/admin-config.component';
import { NewAppointmentSchedulerComponent } from './features/appointment/pages/agendador-agendar/new-appointment-scheduler.component';
import { SchedulerDashboardComponent } from './features/appointment/pages/agendador-listar-citas/scheduler-dashboard.component';
import { PatientNewAppointmentComponent } from './features/appointment/pages/paciente-agendar/patient-new-appointment.component';
import { PatientDashboardComponent } from './features/appointment/pages/paciente-listar-citas/patient-dashboard.component';
import { DoctorAllAppointmentsComponent } from './features/doctor/doctor-all-appointments/doctor-all-appointments.component';
import { DoctorDashboardComponent } from './features/doctor/doctor-dashboard/doctor-dashboard.component';
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
    path: 'admin/usuarios', // ← añadir
    component: AdminUsersComponent,
    canActivate: [AuthGuard],
    data: { role: 'ADMIN' },
  },
  {
    path: 'medico',
    component: DoctorDashboardComponent,
    canActivate: [AuthGuard],
    data: { role: 'DOCTOR' },
  },
  {
    path: 'medico/citas',
    component: DoctorAllAppointmentsComponent,
    canActivate: [AuthGuard],
    data: { role: 'DOCTOR' },
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

import { Routes } from '@angular/router';
import { AuthGuard } from './core/guards/auth.guard';

import { AccesoComponent } from './design-system/pages/acceso/acceso.component';
import { HomeComponent } from './design-system/pages/home/home.component';
import { AdminCreateUserComponent } from './features/admin/pages/admin-create-user/admin-create-user.component';
import { AdminDoctorsComponent } from './features/admin/pages/admin-doctors/admin-doctors.component';
import { AdminConfigComponent } from './features/admin/pages/admin-orquestador/admin-config.component';
import { AdminUsersComponent } from './features/admin/pages/admin-users/admin-users.component';
import { NewAppointmentSchedulerComponent } from './features/appointment/pages/agendador-agendar/new-appointment-scheduler.component';
import { NewAppointmentDoctorComponent } from './features/appointment/pages/doctor-agendar/new-appointment-doctor.component';
import { PatientNewAppointmentComponent } from './features/appointment/pages/paciente-agendar/patient-new-appointment.component';
import { DoctorAllAppointmentsComponent } from './features/doctor/doctor-all-appointments/doctor-all-appointments.component';
import { DoctorDashboardComponent } from './features/doctor/doctor-dashboard/doctor-dashboard.component';
import { DoctorMedicalHistoryComponent } from './features/doctor/doctor-medical-history/doctor-medical-history.component';
import { PatientAppointmentHistoryComponent } from './features/patient/patient-appointment-history/patient-appointment-history.component';
import { PatientDashboardComponent } from './features/patient/patient-dashboard/patient-dashboard.component';
import { PatientMedicalHistoryComponent } from './features/patient/patient-medical-history/patient-medical-history.component';
import { RegistroComponent } from './features/registro/registro.component';
import { SchedulerDashboardComponent } from './features/scheduler-dashboard/scheduler-dashboard.component';

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
    path: 'admin/usuarios',
    component: AdminUsersComponent,
    canActivate: [AuthGuard],
    data: { role: 'ADMIN' },
  },
  {
    path: 'admin/usuarios/crear',
    component: AdminCreateUserComponent,
    canActivate: [AuthGuard],
    data: { role: 'ADMIN' },
  },
  {
    path: 'admin/medicos',
    component: AdminDoctorsComponent,
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
    path: 'medico/nueva-cita',
    component: NewAppointmentDoctorComponent,
    canActivate: [AuthGuard],
    data: { role: 'DOCTOR' },
  },
  {
    path: 'medico/control-medico/:idAppointment',
    component: DoctorMedicalHistoryComponent,
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
  {
    path: 'paciente/historial-citas',
    component: PatientAppointmentHistoryComponent,
    canActivate: [AuthGuard],
    data: { role: 'PATIENT' },
  },
  {
    path: 'paciente/control-medico/:id',
    component: PatientMedicalHistoryComponent,
    canActivate: [AuthGuard],
    data: { role: 'PATIENT' },
  },
  { path: 'registro', component: RegistroComponent },

  { path: '**', redirectTo: '' },
];

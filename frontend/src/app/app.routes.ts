import { Routes } from '@angular/router';
import { AuthGuard } from './core/guards/auth.guard';
import { unsavedChangesGuard } from './core/guards/canDeactivate.guard';
import { HomeComponent } from './designSystem/pages/home/home.component';
import { AdminCreateUserComponent } from './features/admin/pages/adminCreateUser/adminCreateUser.component';
import { AdminDoctorsComponent } from './features/admin/pages/adminDoctors/adminDoctors.component';
import { AdminConfigComponent } from './features/admin/pages/adminOrquestador/adminConfig.component';
import { AdminPatientsComponent } from './features/admin/pages/adminPatients/adminPatients.component';
import { AdminUsersComponent } from './features/admin/pages/adminUsers/adminUsers.component';
import { NewAppointmentDoctorComponent } from './features/appointment/pages/scheduleDoctor/newAppointmentDoctor.component';
import { PatientNewAppointmentComponent } from './features/appointment/pages/schedulePatient/patientNewAppointment.component';
import { NewAppointmentSchedulerComponent } from './features/appointment/pages/scheduleScheduler/newAppointmentScheduler.component';
import { DoctorUnscheduledAttentionComponent } from './features/doctor/components/unscheduledAttention/doctorUnscheduledAttention/doctorUnscheduledAttention.component';
import { DoctorAllAppointmentsComponent } from './features/doctor/doctorAllAppointments/doctorAllAppointments.component';
import { DoctorDashboardComponent } from './features/doctor/doctorDashboard/doctorDashboard.component';
import { DoctorPatientsComponent } from './features/doctor/doctorPatients/doctorPatients.component';
import { DoctorMedicalHistoryComponent } from './features/doctor/medicalCheckUp/medicalCheckUp.component';
import { PatientAppointmentHistoryComponent } from './features/patient/patientAppointmentHistory/patientAppointmentHistory.component';
import { PatientDashboardComponent } from './features/patient/patientDashboard/patientDashboard.component';
import { RegisterComponent } from './features/register/register.component';
import { SchedulerDashboardComponent } from './features/scheduler/pages/schedulerDashboard/schedulerDashboard.component';
import { SchedulerHistoryComponent } from './features/scheduler/pages/schedulerHistory/schedulerHistory.component';
import { AuditComponent } from './features/audit/pages/audit/audit.component';
import { EditProfileComponent } from './features/patient/PatientEditProfile/editProfile.component';

export const routes: Routes = [
  { path: '', component: HomeComponent },

  {
    path: 'agendador',
    component: SchedulerDashboardComponent,
    canActivate: [AuthGuard],
    data: { role: 'SCHEDULER', helpVideoId: 'abBUX1ZpilA' },
  },
  {
    path: 'agendador/historial-citas',
    component: SchedulerHistoryComponent,
    canActivate: [AuthGuard],
    data: { role: 'SCHEDULER', helpVideoId: 'W0sHWe7VOTs' },
  },
  {
    path: 'agendador/nueva',
    component: NewAppointmentSchedulerComponent,
    canActivate: [AuthGuard],
    data: { role: 'SCHEDULER', helpVideoId: 'HqhKBpcvIdU' },
  },
  {
    path: 'admin',
    component: AdminConfigComponent,
    canActivate: [AuthGuard],
    data: { role: 'ADMIN', helpVideoId: 'ypKX7-KxYak' },
  },
  {
    path: 'admin/usuarios',
    component: AdminUsersComponent,
    canActivate: [AuthGuard],
    data: { role: 'ADMIN', helpVideoId: 'pNMb3M1azZ8' },
  },
  {
    path: 'admin/usuarios/crear',
    component: AdminCreateUserComponent,
    canActivate: [AuthGuard],
    data: { role: 'ADMIN', helpVideoId: 'pNMb3M1azZ8' },
  },
  {
    path: 'admin/medicos',
    component: AdminDoctorsComponent,
    canActivate: [AuthGuard],
    data: { role: 'ADMIN', helpVideoId: '5jmnAJBdYOk' },
  },
  {
    path: 'admin/pacientes',
    component: AdminPatientsComponent,
    canActivate: [AuthGuard],
    data: { role: 'ADMIN', helpVideoId: 'QHxv2lxszNQ' },
  },
  {
    path: 'medico',
    component: DoctorDashboardComponent,
    canActivate: [AuthGuard],
    data: { role: 'DOCTOR', helpVideoId: '74op4dK3LpI' },
  },
  {
    path: 'medico/citas',
    component: DoctorAllAppointmentsComponent,
    canActivate: [AuthGuard],
    data: { role: 'DOCTOR', helpVideoId: 'Y7zfvJ2-uZ0' },
  },
  {
    path: 'medico/pacientes',
    component: DoctorPatientsComponent,
    canActivate: [AuthGuard],
    data: { role: 'DOCTOR', helpVideoId: 't5ux8BMStBk' },
  },
  {
    path: 'medico/nueva-cita',
    component: NewAppointmentDoctorComponent,
    canActivate: [AuthGuard],
    data: { role: 'DOCTOR', helpVideoId: 'mV4EIkks9n0' },
  },
  {
    path: 'medico/atencion-sin-cita',
    component: DoctorUnscheduledAttentionComponent,
    canActivate: [AuthGuard],
    data: { role: 'DOCTOR', helpVideoId: 'eHBz9479Nyg' },
  },
  {
    path: 'medico/control-medico/sin-cita',
    component: DoctorMedicalHistoryComponent,
    canActivate: [AuthGuard],
    canDeactivate: [unsavedChangesGuard],
    data: {
      role: 'DOCTOR',
      confirmExitLocally: true,
      helpVideoId: 'vygc5EbWJVg',
    },
  },
  {
    path: 'medico/control-medico/:idAppointment',
    component: DoctorMedicalHistoryComponent,
    canActivate: [AuthGuard],
    canDeactivate: [unsavedChangesGuard],
    data: {
      role: 'DOCTOR',
      confirmExitLocally: true,
      helpVideoId: 'vygc5EbWJVg',
    },
  },
  {
    path: 'paciente/agendar',
    component: PatientNewAppointmentComponent,
    canActivate: [AuthGuard],
    data: { role: 'PATIENT', helpVideoId: 'EZwjGLAGGL8' },
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
    path: 'paciente/editar-perfil',
    component: EditProfileComponent,
    canActivate: [AuthGuard],
    data: { role: 'PATIENT', helpVideoId: 'vpw0tfBKGMg' },
  },
  {
    path: 'auditor/auditoria',
    component: AuditComponent,
    canActivate: [AuthGuard],
    data: { role: 'AUDITOR', helpVideoId: 'u2dHGpCot08' },
  },
  { path: 'registro', component: RegisterComponent },

  { path: '**', redirectTo: '' },
];

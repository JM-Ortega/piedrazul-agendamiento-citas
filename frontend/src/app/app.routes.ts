import { Routes } from '@angular/router';

import { SchedulerDashboardComponent } from './pages/agendador/scheduler-dashboard.component';
import { HomeComponent } from './pages/home/home.component';
export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'agendador', component: SchedulerDashboardComponent },
  { path: '**', redirectTo: '' },
];

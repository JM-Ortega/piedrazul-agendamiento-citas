import { DaySchedule } from './daySchedule.model';

export interface Doctor {
  id: string;
  name: string;
  specialty: string;
  appointmentInterval: number;
  workdays: number[];
  startTime: string; // HH:mm — hora inicio jornada (aplica toda la semana por defecto)
  endTime: string; // HH:mm — hora fin jornada (aplica toda la semana por defecto)
  laborStart: string; // yyyy-MM-dd — fecha inicio período laboral en la clínica
  laborEnd: string; // yyyy-MM-dd — fecha fin período laboral en la clínica
  status?: boolean;
  daySchedules?: { [day: number]: DaySchedule };
  email?: string;
}

import { DaySchedule } from './daySchedule.model';

export interface Doctor {
  id: string;
  name: string;
  specialty: string;
  appointmentInterval: number;
  workdays: number[];
  startTime: string;
  endTime: string;
  laborStart: string;
  laborEnd: string;
  status?: boolean;
  daySchedules?: Record<number, DaySchedule>;
  email?: string;
}

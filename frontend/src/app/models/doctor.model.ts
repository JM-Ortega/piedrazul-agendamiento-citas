import { DaySchedule } from './daySchedule.model';

export interface Doctor {
  id: string;
  name: string;
  specialty: string;
  interval: number;
  workDays: number[];
  startTime: string;
  endTime: string;
  daySchedules?: { [day: number]: DaySchedule };
  email?: string;
  enabled?: boolean;
}

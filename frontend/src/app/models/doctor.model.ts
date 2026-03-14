import { DaySchedule } from "./daySchedule.model"; 

export interface Doctor {
  id: string;
  name: string;
  specialty: string;
  interval: number;
  workDays: number[];
  startTime: string;
  endTime: string;
  windowWeeks: number;
  daySchedules?: { [day: number]: DaySchedule };
  email?: string;
}
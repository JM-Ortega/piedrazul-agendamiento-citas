import { DaySchedule } from "./daySchedule.model"; 

export interface Doctor {
  id: string;
  name: string;
  specialty: string;
  photo: string;
  interval: number;
  workDays: number[];
  startTime: string;
  endTime: string;
  windowWeeks: number;
  daySchedules?: { [day: number]: DaySchedule };
  email?: string;
  //❌ No se debe sacar el password del back para autenticar, es inseguro
  password?: string;
}
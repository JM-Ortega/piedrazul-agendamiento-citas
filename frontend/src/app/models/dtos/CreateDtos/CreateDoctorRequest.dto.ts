import { CreateScheduleDto } from './CreateSchedule.dto';

export interface CreateDoctorRequestDto {
  firstName: string;
  lastName: string;
  identification: string;
  documentType: string;
  phone: string;
  specialty: string[];
  laborStart: string; // ISO date: "YYYY-MM-DD"
  laborEnd: string; // ISO date: "YYYY-MM-DD"
  appointmentInterval: number; // En minutos
  schedules: CreateScheduleDto[];
  email: string;
  password: string;
}

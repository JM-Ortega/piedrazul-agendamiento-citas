import { CreateScheduleDto } from './CreateSchedule.dto';

export interface CreateUserDoctorDto {
  documentType: string;
  phone: string;
  specialty: string[];
  laborStart: string;
  laborEnd: string;
  appointmentInterval: number;
  schedules: CreateScheduleDto[];
}

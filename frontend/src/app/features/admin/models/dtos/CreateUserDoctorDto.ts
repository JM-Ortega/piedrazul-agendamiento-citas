import { CreateScheduleDto } from './CreateSchedule.dto';

export interface CreateUserDoctorDto {
  specialty: string[];
  laborStart: string;
  laborEnd: string;
  appointmentInterval: number;
  schedules: CreateScheduleDto[];
}

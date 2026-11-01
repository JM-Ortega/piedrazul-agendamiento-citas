import { CreateScheduleDto } from './CreateSchedule.dto';

export interface CreateUserDoctorDto {
  specialty: string[];
  laborStart: string | null;
  laborEnd: string | null;
  appointmentInterval: number | null;
  schedules: CreateScheduleDto[];
  bookingWindowWeeks: number | null;
}

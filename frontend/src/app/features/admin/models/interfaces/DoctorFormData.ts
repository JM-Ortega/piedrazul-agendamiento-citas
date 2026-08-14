export interface DoctorFormData {
  specialty: string[];
  laborStart: string;
  laborEnd: string;
  interval: number | null;
  workDays: number[];
  startTime: string;
  endTime: string;
  bookingWindowWeeks: number;
}

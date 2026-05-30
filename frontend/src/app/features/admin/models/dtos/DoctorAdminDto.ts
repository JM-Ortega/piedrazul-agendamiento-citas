export interface DoctorAdminDto {
  doctorId: string;
  firstName: string;
  lastName: string;
  document: string;
  roles: ('doctor' | 'scheduler')[];
  specialties: string[];
}

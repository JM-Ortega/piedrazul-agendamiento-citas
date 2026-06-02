export interface DoctorAdminDto {
  id: string;
  firstName: string;
  lastName: string;
  documentId: string;
  roles: ('DOCTOR' | 'SCHEDULER')[];
  specialties: string[];
}

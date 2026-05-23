export interface SystemUser {
  id: string;
  firstName: string;
  lastName?: string; // ← opcional
  documentId?: string; // ← opcional
  roles: ('doctor' | 'scheduler')[];
  doctorData?: {
    specialty: string;
    startTime: string;
    endTime: string;
    interval: number;
  };
}

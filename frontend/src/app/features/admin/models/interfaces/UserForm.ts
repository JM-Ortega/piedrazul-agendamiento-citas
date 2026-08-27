export interface UserForm {
  documentId: string;
  documentType: string;
  password: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  specialty: string[];
  laborStart: string;
  laborEnd: string;
  interval: number;
  workDays: number[];
  startTime: string;
  endTime: string;
}

export interface FormErrors {
  [key: string]: string | undefined;
  documentId?: string;
  identificationType?: string;
  password?: string;
  firstName?: string;
  lastName?: string;
  roles?: string;
  email?: string;
  phone?: string;
  specialty?: string;
  laborStart?: string;
  laborEnd?: string;
  startTime?: string;
  endTime?: string;
  interval?: string;
  workDays?: string;
  bookingWindowWeeks?: string;
}

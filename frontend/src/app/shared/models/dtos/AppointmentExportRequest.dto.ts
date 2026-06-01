import { ExportColumnBackend } from '../types/ExportColumnBackend.type';
import { ExportFormatBackend } from '../types/ExportFormatBackend.type';
export interface AppointmentExportRequest {
  idDoctor?: string | null;
  format: ExportFormatBackend;
  columns: ExportColumnBackend[];
  state?: string | null;
}

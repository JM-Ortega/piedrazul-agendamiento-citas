export interface dtoSchedule {
  idSchedule: string;
  idDoctor: string;
  startTime: string; // HH:mm:ss
  endTime: string; // HH:mm:ss
  workday: 'LUNES' | 'MARTES' | 'MIERCOLES' | 'JUEVES' | 'VIERNES';
}

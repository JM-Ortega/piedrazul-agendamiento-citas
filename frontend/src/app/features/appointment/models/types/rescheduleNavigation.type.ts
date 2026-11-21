import { NavigationExtras } from '@angular/router';
import { AppointmentsPatient } from '../../../../shared/models/dtos/appointments.dto';

/** Ruta del agendamiento del agendador*/
export const SCHEDULER_NEW_APPOINTMENT_ROUTE = '/agendador/nueva';

/** Query param que activa el modo reagendamiento: /agendador/nueva?reagendar=<id> */
export const RESCHEDULE_QUERY_PARAM = 'reagendar';

/**
 * Datos de la cita original que viajan en el `state` de la navegación.
 * Van en el state y no en la URL para no exponer el documento del
 * paciente en el historial del navegador.
 */
export interface RescheduleNavigationState {
  appointmentId: string;
  documentNumber: string;
  patientName: string;
  doctorName: string;
  specialty: string;
  /** Ruta a la que se vuelve al cancelar o al terminar el reagendamiento. */
  returnTo: string;
}

/** Arma la navegación hacia el agendamiento en modo reagendamiento. */
export function buildRescheduleNavigation(
  apt: AppointmentsPatient,
  returnTo: string
): NavigationExtras {
  const reschedule: RescheduleNavigationState = {
    appointmentId: apt.idAppointment,
    documentNumber: apt.documentNumber,
    patientName: `${apt.patientFirstName} ${apt.patientLastName}`.trim(),
    doctorName: apt.doctorName,
    specialty: apt.specialty,
    returnTo,
  };
  return {
    queryParams: { [RESCHEDULE_QUERY_PARAM]: apt.idAppointment },
    state: { reschedule },
  };
}

import { Injectable } from '@angular/core';
import { timeToMinutes } from '../../../shared/helpers/time-utils';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';

export interface FormErrors {
  horarioGlobal: string;
  fechas: string;
  fechaInicio: string;
  fechaFin: string;
  intervalo: string;
  dias: string;
  bookingWindowWeeks: string;
  horariosDia: Record<number, string>;
}

@Injectable({ providedIn: 'root' })
export class DoctorFormValidationService {
  validateFranjaVsIntervalo(
    startTime: string,
    endTime: string,
    interval: number
  ): string {
    if (!startTime || !endTime || startTime >= endTime) return '';
    const duracion = timeToMinutes(endTime) - timeToMinutes(startTime);
    if (duracion < interval) {
      return `La franja horaria (${duracion} min) no puede ser menor al intervalo (${interval} min).`;
    }
    return '';
  }

  /**
   * Valida que la ventana de agendamiento (en semanas, contadas desde hoy)
   * no se extienda más allá de la fecha de fin del período laboral.
   * Sin esto, un paciente podría agendar una cita para una fecha en la
   * que el médico ya no está activo en el sistema.
   */
  validateBookingWindow(bookingWindowWeeks: number, laborEnd: string): string {
    if (!bookingWindowWeeks || bookingWindowWeeks <= 0) {
      return 'La ventana de agendamiento debe ser mayor a 0.';
    }
    if (bookingWindowWeeks > 52) {
      return 'La ventana de agendamiento no puede superar 52 semanas.';
    }
    if (!laborEnd) return '';

    const today = new Date();
    const limitDate = new Date(today);
    limitDate.setDate(limitDate.getDate() + bookingWindowWeeks * 7);

    const laborEndDate = new Date(laborEnd + 'T00:00:00');

    if (limitDate > laborEndDate) {
      return `Con ${bookingWindowWeeks} semanas, se podrían agendar citas después del ${laborEnd}, cuando el médico ya no estará activo. Reduzca la ventana o extienda el período laboral.`;
    }
    return '';
  }

  validateForm(form: Doctor): FormErrors {
    const errors: FormErrors = {
      horarioGlobal: '',
      fechas: '',
      fechaInicio: '',
      fechaFin: '',
      intervalo: '',
      dias: '',
      bookingWindowWeeks: '',
      horariosDia: {},
    };

    if (!form.startTime || !form.endTime) {
      errors.horarioGlobal =
        'La hora de inicio y hora de fin son obligatorias.';
    } else if (form.startTime >= form.endTime) {
      errors.horarioGlobal =
        'La hora de inicio no puede ser igual o posterior a la hora de fin.';
    } else {
      errors.horarioGlobal = this.validateFranjaVsIntervalo(
        form.startTime,
        form.endTime,
        form.appointmentInterval
      );
    }

    if (!form.appointmentInterval || form.appointmentInterval <= 0) {
      errors.intervalo = 'El intervalo debe ser mayor a 0.';
    }

    if (!form.laborStart)
      errors.fechaInicio = 'La fecha de inicio es obligatoria.';
    if (!form.laborEnd) errors.fechaFin = 'La fecha de fin es obligatoria.';

    if (form.laborStart && form.laborEnd && form.laborStart >= form.laborEnd) {
      errors.fechas =
        'La fecha de inicio no puede ser igual o posterior a la fecha de fin.';
    }

    errors.bookingWindowWeeks = this.validateBookingWindow(
      form.bookingWindowWeeks,
      form.laborEnd
    );

    if (!(form.workdays ?? []).length) {
      errors.dias = 'Debe seleccionar al menos un día de atención.';
    }

    for (const day of form.workdays ?? []) {
      const ds = form.daySchedules?.[day];
      if (!ds) continue;
      if (ds.startTime >= ds.endTime) {
        errors.horariosDia[day] =
          'La hora de inicio no puede ser igual o posterior a la hora de fin.';
      } else {
        const err = this.validateFranjaVsIntervalo(
          ds.startTime,
          ds.endTime,
          form.appointmentInterval
        );
        if (err) errors.horariosDia[day] = err;
      }
    }

    return errors;
  }

  horariosValidos(form: Doctor): boolean {
    if (!form.startTime || !form.endTime || form.startTime >= form.endTime)
      return false;
    const dur = timeToMinutes(form.endTime) - timeToMinutes(form.startTime);
    if (dur < form.appointmentInterval) return false;
    for (const day of form.workdays ?? []) {
      const ds = form.daySchedules?.[day];
      if (ds) {
        if (ds.startTime >= ds.endTime) return false;
        const d = timeToMinutes(ds.endTime) - timeToMinutes(ds.startTime);
        if (d < form.appointmentInterval) return false;
      }
    }
    return true;
  }
}

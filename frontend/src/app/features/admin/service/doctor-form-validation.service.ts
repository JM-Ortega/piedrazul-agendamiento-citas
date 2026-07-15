import { Injectable } from '@angular/core';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';

export interface FormErrors {
  horarioGlobal: string;
  fechas: string;
  fechaInicio: string;
  fechaFin: string;
  intervalo: string;
  dias: string;
  horariosDia: { [day: number]: string };
}

@Injectable({ providedIn: 'root' })
export class DoctorFormValidationService {
  timeToMinutes(time: string): number {
    if (!time) return 0;
    const [h, m] = time.split(':').map(Number);
    return h * 60 + m;
  }

  validateFranjaVsIntervalo(
    startTime: string,
    endTime: string,
    interval: number
  ): string {
    if (!startTime || !endTime || startTime >= endTime) return '';
    const duracion =
      this.timeToMinutes(endTime) - this.timeToMinutes(startTime);
    if (duracion < interval) {
      return `La franja horaria (${duracion} min) no puede ser menor al intervalo (${interval} min).`;
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
    const dur =
      this.timeToMinutes(form.endTime) - this.timeToMinutes(form.startTime);
    if (dur < form.appointmentInterval) return false;
    for (const day of form.workdays ?? []) {
      const ds = form.daySchedules?.[day];
      if (ds) {
        if (ds.startTime >= ds.endTime) return false;
        const d =
          this.timeToMinutes(ds.endTime) - this.timeToMinutes(ds.startTime);
        if (d < form.appointmentInterval) return false;
      }
    }
    return true;
  }
}

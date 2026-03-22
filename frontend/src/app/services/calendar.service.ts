import { Injectable } from '@angular/core';
import { isHoliday } from 'colombian-holidays';
import { SpecialtyDoctor } from '../DTOs/specialty-doctor';

@Injectable({ providedIn: 'root' })
export class CalendarService {

  buildDateFilter(doctor: SpecialtyDoctor): (date: Date | null) => boolean {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const max30 = new Date(today);
    max30.setDate(max30.getDate() + 30);

    let effectiveMax = max30;
    if (doctor.fechaFinalTrabajo) {
      const doctorLimit = new Date(doctor.fechaFinalTrabajo + 'T12:00:00');
      if (doctorLimit < max30) effectiveMax = doctorLimit;
    }

    const workDays = new Set(doctor.workDays);

    return (date: Date | null): boolean => {
      if (!date) return false;

      const d = new Date(date);
      d.setHours(0, 0, 0, 0);

      const dayOfWeek = d.getDay();

      return (
        d > today           &&
        d <= effectiveMax   &&
        dayOfWeek !== 0     &&
        dayOfWeek !== 6     &&
        !isHoliday(d)       &&
        workDays.has(dayOfWeek)
      );
    };
  }

  getMinDate(): Date {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    return tomorrow;
  }

  getMaxDate(doctor: SpecialtyDoctor): Date {
    const max30 = new Date();
    max30.setDate(max30.getDate() + 30);

    if (doctor.fechaFinalTrabajo) {
      const doctorLimit = new Date(doctor.fechaFinalTrabajo + 'T12:00:00');
      return doctorLimit < max30 ? doctorLimit : max30;
    }
    return max30;
  }
}
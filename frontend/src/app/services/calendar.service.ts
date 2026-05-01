import { Injectable } from '@angular/core';
import { isHoliday } from 'colombian-holidays';
import { SpecialtyDoctor } from '../models/dtos/specialty-doctor.dto';

@Injectable({ providedIn: 'root' })
export class CalendarService {
  buildDateFilter(
    doctor: SpecialtyDoctor,
    allowToday = false,
  ): (date: Date | null) => boolean {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const minDay = new Date(today);
    if (!allowToday) minDay.setDate(minDay.getDate());

    const max30 = new Date(today);
    max30.setDate(max30.getDate() + 30);

    let effectiveMax = max30;
    if (doctor.laborEnd) {
      const doctorLimit = new Date(doctor.laborEnd + 'T12:00:00');
      if (doctorLimit < max30) effectiveMax = doctorLimit;
    }

    const workDays = new Set(doctor.workdays);

    return (date: Date | null): boolean => {
      if (!date) return false;
      const d = new Date(date);
      d.setHours(0, 0, 0, 0);
      const dayOfWeek = d.getDay();

      return (
        (allowToday ? d >= today : d > today) &&
        d <= effectiveMax &&
        dayOfWeek !== 0 &&
        dayOfWeek !== 6 &&
        !isHoliday(d) &&
        workDays.has(dayOfWeek)
      );
    };
  }

  getMinDate(allowToday = false): Date {
    if (allowToday) return new Date();
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    return tomorrow;
  }

  getMaxDate(doctor: SpecialtyDoctor): Date {
    const max30 = new Date();
    max30.setDate(max30.getDate() + 30);

    if (doctor.laborEnd) {
      const doctorLimit = new Date(doctor.laborEnd + 'T12:00:00');
      return doctorLimit < max30 ? doctorLimit : max30;
    }
    return max30;
  }
}

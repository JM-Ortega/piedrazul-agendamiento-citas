import { Injectable } from '@angular/core';
import { isHoliday } from 'colombian-holidays/isHoliday';
import { SpecialtyDoctor } from '../models/dtos/specialty-doctor.dto';

@Injectable({ providedIn: 'root' })
export class CalendarService {
  private getEffectiveMinDate(
    doctor: SpecialtyDoctor | null,
    allowToday: boolean
  ): Date {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    let minDate = allowToday ? today : this.addDays(today, 1);

    if (doctor?.laborStart) {
      const laborStart = new Date(doctor.laborStart + 'T12:00:00');
      laborStart.setHours(0, 0, 0, 0);
      if (laborStart > minDate) {
        minDate = laborStart;
      }
    }
    return minDate;
  }

  private addDays(date: Date, days: number): Date {
    const d = new Date(date);
    d.setDate(d.getDate() + days);
    return d;
  }

  buildDateFilter(
    doctor: SpecialtyDoctor,
    allowToday = false
  ): (date: Date | null) => boolean {
    const minDate = this.getEffectiveMinDate(doctor, allowToday);
    const max30 = new Date();
    max30.setHours(0, 0, 0, 0);
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
        d >= minDate &&
        d <= effectiveMax &&
        dayOfWeek !== 0 &&
        dayOfWeek !== 6 &&
        !isHoliday(d) &&
        workDays.has(dayOfWeek)
      );
    };
  }

  getMinDate(doctor: SpecialtyDoctor | null = null, allowToday = false): Date {
    return this.getEffectiveMinDate(doctor, allowToday);
  }

  getMaxDate(doctor: SpecialtyDoctor): Date {
    const max30 = new Date();
    max30.setHours(0, 0, 0, 0);
    max30.setDate(max30.getDate() + 30);

    if (doctor.laborEnd) {
      const doctorLimit = new Date(doctor.laborEnd + 'T12:00:00');
      return doctorLimit < max30 ? doctorLimit : max30;
    }
    return max30;
  }
}

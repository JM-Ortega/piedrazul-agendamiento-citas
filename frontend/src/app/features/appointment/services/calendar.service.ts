import { Injectable } from '@angular/core';
import { isHoliday } from 'colombian-holidays/isHoliday';
import { SpecialtyDoctor } from '../models/dtos/specialty-doctor.dto';

/** Tope de agendamiento por defecto (en días) cuando el médico no trae `bookingWindowWeeks`. */
const DEFAULT_MAX_DAYS = 30;

/** Cantidad de días por semana, usada para convertir `bookingWindowWeeks` a días. */
const DAYS_PER_WEEK = 7;

/**
 * Calcula rangos y filtros de fechas disponibles para agendar una cita con
 * un médico determinado.
 *
 * Reglas de la ventana de agendamiento:
 * - Si el médico NO trae `bookingWindowWeeks` (es `null`/`undefined`),
 *   el tope es de {@link DEFAULT_MAX_DAYS} días contados desde hoy.
 * - Si trae `bookingWindowWeeks > 0`, el tope es esa cantidad de semanas contadas desde hoy.
 * - Si trae `bookingWindowWeeks === 0`, no hay fechas disponibles para ese médico.
 *
 * El tope final (`effectiveMax`) siempre se recorta contra `laborEnd` si hay fecha de fin labor.
 *
 * Reglas de la fecha mínima (`minDate`), según contexto:
 * - Agendador/médico (`allowToday = true`): arranca en hoy.
 * - Paciente (`allowToday = false`): arranca en mañana.
 * - En ambos casos, si `laborStart` es una fecha futura, esa pasa a ser el mínimo.
 */
@Injectable({ providedIn: 'root' })
export class CalendarService {
  /**
   * Calcula la fecha mínima de agendamiento para un médico dado.
   *
   * Parte de hoy (contexto doctor/agendador) o mañana (contexto paciente), y si `laborStart`
   * del médico es posterior a ese punto de partida, usa `laborStart` como mínimo en su lugar.
   *
   * @param doctor - Médico sobre el que se calcula el mínimo, o `null` si aún no hay médico seleccionado.
   * @param allowToday - `true` si hoy es una fecha válida para agendar (doctor/agendador), `false` si se debe arrancar desde mañana (paciente).
   * @returns Fecha mínima seleccionable, con horas normalizadas a 00:00:00.
   */
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

  /**
   * Calcula el tope de la ventana de agendamiento, contado siempre desde
   * HOY (independientemente de si `laborStart` es pasado o futuro).
   *
   * - `bookingWindowWeeks` es `0`: retorna una fecha anterior a hoy para
   *   garantizar que ninguna fecha quede disponible.
   * - `bookingWindowWeeks` es `null`/`undefined`: usa el tope por defecto
   *   de {@link DEFAULT_MAX_DAYS} días.
   * - `bookingWindowWeeks` es un número mayor a 0: usa esa cantidad de
   *   semanas convertida a días.
   *
   * @param doctor - Médico sobre el que se calcula el tope, o `null` si aún no hay médico seleccionado.
   * @returns Fecha tope de la ventana de agendamiento, con horas normalizadas a 00:00:00.
   */
  private getWindowCap(doctor: SpecialtyDoctor | null): Date {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const weeks = doctor?.bookingWindowWeeks;

    if (weeks === 0) {
      return this.addDays(today, -1);
    }

    const days =
      weeks !== null && weeks !== undefined && weeks > 0
        ? weeks * DAYS_PER_WEEK
        : DEFAULT_MAX_DAYS;

    return this.addDays(today, days);
  }

  /**
   * Calcula la fecha máxima efectiva de agendamiento para un médico: el menor valor entre el tope
   * de la ventana de agendamiento ({@link getWindowCap}) y la fecha de fin de labor del médico
   * (`laborEnd`), si la tiene.
   *
   * @param doctor - Médico sobre el que se calcula el máximo, o `null` si aún no hay médico seleccionado.
   * @returns Fecha máxima seleccionable, con horas normalizadas a 00:00:00.
   */
  private getEffectiveMaxDate(doctor: SpecialtyDoctor | null): Date {
    const windowCap = this.getWindowCap(doctor);

    if (doctor?.laborEnd) {
      const doctorLimit = new Date(doctor.laborEnd + 'T12:00:00');
      doctorLimit.setHours(0, 0, 0, 0);
      return doctorLimit < windowCap ? doctorLimit : windowCap;
    }
    return windowCap;
  }

  /**
   * Suma o resta días a una fecha.
   *
   * @param date - Fecha base.
   * @param days - Cantidad de días a sumar (puede ser negativa).
   * @returns Nueva instancia de `Date` con el desplazamiento aplicado.
   */
  private addDays(date: Date, days: number): Date {
    const d = new Date(date);
    d.setDate(d.getDate() + days);
    return d;
  }

  /**
   * Construye una función de filtro de fechas para el datepicker, que determina si una fecha es
   * seleccionable para agendar con el médico indicado.
   *
   * Una fecha es válida si:
   * - está dentro del rango `[minDate, effectiveMax]`,
   * - no cae en fin de semana,
   * - no es festivo colombiano, y
   * - el día de la semana está dentro de los `workdays` del médico.
   *
   * @param doctor - Médico para el que se agenda.
   * @param allowToday - `true` o `false` si se debe arrancar desde hoy o mañana.
   * @returns Función que recibe una fecha (o `null`) y retorna `true` si es seleccionable.
   */
  buildDateFilter(
    doctor: SpecialtyDoctor,
    allowToday = false
  ): (date: Date | null) => boolean {
    const minDate = this.getEffectiveMinDate(doctor, allowToday);
    const effectiveMax = this.getEffectiveMaxDate(doctor);

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

  /**
   * Fecha mínima seleccionable para agendar con un médico dado.
   *
   * @param doctor - Médico sobre el que se calcula el mínimo. Por defecto `null`.
   * @param allowToday - `true` o `false` si se arranca desde hoy o mañana. Por defecto `false`.
   * @returns Fecha mínima seleccionable.
   */
  getMinDate(doctor: SpecialtyDoctor | null = null, allowToday = false): Date {
    return this.getEffectiveMinDate(doctor, allowToday);
  }

  /**
   * Fecha máxima seleccionable para agendar con un médico dado.
   *
   * @param doctor - Médico sobre el que se calcula el máximo.
   * @returns Fecha máxima seleccionable.
   */
  getMaxDate(doctor: SpecialtyDoctor): Date {
    return this.getEffectiveMaxDate(doctor);
  }
}

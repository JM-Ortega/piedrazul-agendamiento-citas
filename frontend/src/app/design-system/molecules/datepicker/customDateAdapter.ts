import { Injectable } from '@angular/core';
import { NativeDateAdapter } from '@angular/material/core';

export type DateFormatPreset = 'dd/mm/yyyy' | 'mm/dd/yyyy' | 'yyyy-mm-dd';

export const DATE_INPUT_FORMAT_MARKER = 'app-date-input-format';

@Injectable()
export class CustomDateAdapter extends NativeDateAdapter {
  pattern: DateFormatPreset = 'dd/mm/yyyy';

  override parse(value: unknown): Date | null {
    if (typeof value !== 'string' || !value.trim()) return null;

    const parts = this.extractParts(value.trim());
    if (!parts) return null;

    const { day, month, year } = parts;
    const date = new Date(year, month - 1, day);

    if (
      date.getFullYear() !== year ||
      date.getMonth() !== month - 1 ||
      date.getDate() !== day
    ) {
      return null;
    }
    return date;
  }

  override format(date: Date, displayFormat: unknown): string {
    if (displayFormat === DATE_INPUT_FORMAT_MARKER) {
      return this.formatForInput(date);
    }
    return super.format(date, displayFormat as object);
  }

  /** Nombres cortos de días para el encabezado del calendario (dom, lun, mar...). */
  override getDayOfWeekNames(style: 'long' | 'short' | 'narrow'): string[] {
    if (style === 'narrow' || style === 'short') {
      return ['dom', 'lun', 'mar', 'mié', 'jue', 'vie', 'sáb'];
    }
    return super.getDayOfWeekNames(style);
  }

  private formatForInput(date: Date): string {
    const day = String(date.getDate()).padStart(2, '0');
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const year = date.getFullYear();

    switch (this.pattern) {
      case 'mm/dd/yyyy':
        return `${month}/${day}/${year}`;
      case 'yyyy-mm-dd':
        return `${year}-${month}-${day}`;
      default:
        return `${day}/${month}/${year}`;
    }
  }

  private extractParts(
    value: string
  ): { day: number; month: number; year: number } | null {
    switch (this.pattern) {
      case 'mm/dd/yyyy': {
        const m = value.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
        return m
          ? { month: Number(m[1]), day: Number(m[2]), year: Number(m[3]) }
          : null;
      }
      case 'yyyy-mm-dd': {
        const m = value.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
        return m
          ? { year: Number(m[1]), month: Number(m[2]), day: Number(m[3]) }
          : null;
      }
      default: {
        const m = value.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
        return m
          ? { day: Number(m[1]), month: Number(m[2]), year: Number(m[3]) }
          : null;
      }
    }
  }
}

export function parseLocalDateString(value: string): Date {
  const isoMatch = value.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (isoMatch) {
    const [, y, m, d] = isoMatch;
    return new Date(Number(y), Number(m) - 1, Number(d));
  }
  return new Date(value);
}

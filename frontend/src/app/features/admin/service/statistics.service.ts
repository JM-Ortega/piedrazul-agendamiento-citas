import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import {
  CancellationStats,
  MonthlyBreakdown,
  MonthlyTotalStat,
} from '../../../shared/models/dtos/statistics.dto';

/**
 * Servicio de estadísticas del panel de administración.
 *
 * Hoy TODOS los métodos devuelven datos simulados (mock). Cuando el backend
 * exponga los endpoints: inyectar `HttpClient` y `environment.apiUrl` (igual
 * que en `AdminService`), reemplazar el cuerpo de cada método por la
 * petición HTTP y eliminar los helpers marcados como MOCK.
 */
@Injectable({ providedIn: 'root' })
export class StatisticsService {
  // ── MOCK: datos base ──────────────────────────────────────────────────────
  private readonly MOCK_DOCTORS = [
    { name: 'Carlos Ramírez', specialty: 'Medicina General' },
    { name: 'Laura Gómez', specialty: 'Pediatría' },
    { name: 'Andrés Pérez', specialty: 'Fisioterapia' },
    { name: 'Marcela Ortiz', specialty: 'Psicología' },
    { name: 'Julián Torres', specialty: 'Medicina General' },
  ];
  private readonly MONTHS = Array.from({ length: 12 }, (_, i) => i);

  /** Años con citas registradas, del más reciente al más antiguo. */
  getAvailableYears(): Observable<number[]> {
    // TODO: Conectar con endpoint de estadísticas
    // Ruta sugerida: GET `${apiUrl}/statistics/years`
    const current = new Date().getFullYear();
    return of([current, current - 1]);
  }

  /** Total de citas (incluye canceladas) por mes del año indicado. */
  getMonthlyTotals(year: number): Observable<MonthlyTotalStat[]> {
    // TODO: Conectar con endpoint de estadísticas
    // Ruta sugerida: GET `${apiUrl}/statistics/appointments/monthly?year=${year}`
    return of(
      this.MONTHS.map((month) => ({
        month,
        total:
          this.mockAttendedTotal(year, month) + this.mockCancelled(year, month),
      }))
    );
  }

  /** Citas no canceladas por médico y por mes del año indicado. */
  getMonthlyByDoctor(year: number): Observable<MonthlyBreakdown> {
    // TODO: Conectar con endpoint de estadísticas
    // Ruta sugerida: GET `${apiUrl}/statistics/appointments/by-doctor?year=${year}`
    return of({
      series: this.MOCK_DOCTORS.map((d) => d.name),
      rows: this.MONTHS.map((month) => ({
        month,
        values: Object.fromEntries(
          this.MOCK_DOCTORS.map((d) => [
            d.name,
            this.mockAttended(year, month, d.name),
          ])
        ),
      })),
    });
  }

  /** Citas no canceladas por especialidad y por mes del año indicado. */
  getMonthlyBySpecialty(year: number): Observable<MonthlyBreakdown> {
    // TODO: Conectar con endpoint de estadísticas
    // Ruta sugerida: GET `${apiUrl}/statistics/appointments/by-specialty?year=${year}`
    const specialties = [...new Set(this.MOCK_DOCTORS.map((d) => d.specialty))];
    return of({
      series: specialties,
      rows: this.MONTHS.map((month) => ({
        month,
        values: Object.fromEntries(
          specialties.map((spec) => [
            spec,
            this.MOCK_DOCTORS.filter((d) => d.specialty === spec).reduce(
              (acc, d) => acc + this.mockAttended(year, month, d.name),
              0
            ),
          ])
        ),
      })),
    });
  }

  /** Tasa de cancelación mensual y global del año indicado. */
  getCancellationRate(year: number): Observable<CancellationStats> {
    // TODO: Conectar con endpoint de estadísticas
    // Ruta sugerida: GET `${apiUrl}/statistics/appointments/cancellation-rate?year=${year}`
    let totalYear = 0;
    let cancelledYear = 0;
    const months = this.MONTHS.map((month) => {
      const cancelled = this.mockCancelled(year, month);
      const total = this.mockAttendedTotal(year, month) + cancelled;
      totalYear += total;
      cancelledYear += cancelled;
      return {
        month,
        rate: total > 0 ? Math.round((cancelled / total) * 100) : 0,
      };
    });
    return of({
      months,
      yearRate:
        totalYear > 0 ? Math.round((cancelledYear / totalYear) * 100) : 0,
    });
  }

  // ── MOCK: helpers (eliminar al conectar el backend) ───────────────────────
  private mockIsFuture(year: number, month: number): boolean {
    const now = new Date();
    return (
      year > now.getFullYear() ||
      (year === now.getFullYear() && month > now.getMonth())
    );
  }

  /** Número pseudoaleatorio determinista en [0, 1) para que el mock sea estable. */
  private mockNoise(...parts: (string | number)[]): number {
    let h = 2166136261;
    for (const ch of parts.join('|')) {
      h ^= ch.charCodeAt(0);
      h = Math.imul(h, 16777619);
    }
    return ((h >>> 0) % 1000) / 1000;
  }

  private mockAttended(year: number, month: number, doctor: string): number {
    if (this.mockIsFuture(year, month)) return 0;
    return 10 + Math.floor(this.mockNoise(year, month, doctor) * 25);
  }

  private mockAttendedTotal(year: number, month: number): number {
    return this.MOCK_DOCTORS.reduce(
      (acc, d) => acc + this.mockAttended(year, month, d.name),
      0
    );
  }

  private mockCancelled(year: number, month: number): number {
    if (this.mockIsFuture(year, month)) return 0;
    const ratio = 0.05 + this.mockNoise(year, month, 'cancel') * 0.2;
    return Math.floor(this.mockAttendedTotal(year, month) * ratio);
  }
}

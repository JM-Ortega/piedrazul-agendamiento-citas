import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import {
  CancellationStats,
  EstadoCita,
  EstadoFiltro,
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
  /** Estados que suman por defecto médico y especialidad (sin filtro). */
  private readonly ESTADOS_AGENDADA_ATENDIDA: EstadoCita[] = [
    'AGENDADA',
    'ATENDIDA',
  ];
  private readonly ESTADOS_TODOS: EstadoCita[] = [
    'AGENDADA',
    'ATENDIDA',
    'NO_ASISTIO',
    'CANCELADA',
  ];

  /** Años con citas registradas, del más reciente al más antiguo. */
  getAvailableYears(): Observable<number[]> {
    // TODO: Conectar con endpoint de estadísticas
    // Ruta sugerida: GET `${apiUrl}/statistics/years`
    const current = new Date().getFullYear();
    return of([current, current - 1]);
  }

  /** Total de citas por mes. Sin `estado`, incluye todos (canceladas también). */
  getMonthlyTotals(
    year: number,
    estado?: EstadoFiltro
  ): Observable<MonthlyTotalStat[]> {
    // TODO: Conectar con endpoint de estadísticas
    // Ruta sugerida: GET `${apiUrl}/statistics/appointments/monthly?year=${year}&estado=${estado ?? ''}`
    const estados = estado ? [estado] : this.ESTADOS_TODOS;
    return of(
      this.MONTHS.map((month) => ({
        month,
        total: this.mockCount(year, month, estados),
      }))
    );
  }

  /** Citas por médico y por mes. Sin `estado`, suma todos menos canceladas. */
  getMonthlyByDoctor(
    year: number,
    estado?: EstadoFiltro
  ): Observable<MonthlyBreakdown> {
    // TODO: Conectar con endpoint de estadísticas
    // Ruta sugerida: GET `${apiUrl}/statistics/appointments/by-doctor?year=${year}&estado=${estado ?? ''}`
    const estados = estado ? [estado] : this.ESTADOS_AGENDADA_ATENDIDA;
    return of({
      series: this.MOCK_DOCTORS.map((d) => d.name),
      rows: this.MONTHS.map((month) => ({
        month,
        values: Object.fromEntries(
          this.MOCK_DOCTORS.map((d) => [
            d.name,
            this.mockCount(year, month, estados, d.name),
          ])
        ),
      })),
    });
  }

  /** Citas por especialidad y por mes. Sin `estado`, suma todos menos canceladas. */
  getMonthlyBySpecialty(
    year: number,
    estado?: EstadoFiltro
  ): Observable<MonthlyBreakdown> {
    // TODO: Conectar con endpoint de estadísticas
    // Ruta sugerida: GET `${apiUrl}/statistics/appointments/by-specialty?year=${year}&estado=${estado ?? ''}`
    const estados = estado ? [estado] : this.ESTADOS_AGENDADA_ATENDIDA;
    const specialties = [...new Set(this.MOCK_DOCTORS.map((d) => d.specialty))];
    return of({
      series: specialties,
      rows: this.MONTHS.map((month) => ({
        month,
        values: Object.fromEntries(
          specialties.map((spec) => [
            spec,
            this.MOCK_DOCTORS.filter((d) => d.specialty === spec).reduce(
              (acc, d) => acc + this.mockCount(year, month, estados, d.name),
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
      const cancelled = this.mockCount(year, month, ['CANCELADA']);
      const total = this.mockCount(year, month, this.ESTADOS_TODOS);
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

  private mockIsPast(year: number, month: number): boolean {
    const now = new Date();
    return (
      year < now.getFullYear() ||
      (year === now.getFullYear() && month < now.getMonth())
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

  /** Suma las citas de los estados indicados; sin `doctor` suma todos. */
  private mockCount(
    year: number,
    month: number,
    estados: EstadoCita[],
    doctor?: string
  ): number {
    const doctors = doctor ? [doctor] : this.MOCK_DOCTORS.map((d) => d.name);
    let total = 0;
    for (const d of doctors) {
      for (const e of estados) total += this.mockByState(year, month, d, e);
    }
    return total;
  }

  private mockByState(
    year: number,
    month: number,
    doctor: string,
    estado: EstadoCita
  ): number {
    switch (estado) {
      case 'ATENDIDA':
        if (this.mockIsFuture(year, month)) return 0;
        return 10 + Math.floor(this.mockNoise(year, month, doctor) * 25);
      case 'AGENDADA':
        if (this.mockIsPast(year, month)) return 0;
        return (
          3 + Math.floor(this.mockNoise(year, month, doctor, 'sched') * 10)
        );
      case 'NO_ASISTIO': {
        const attended = this.mockByState(year, month, doctor, 'ATENDIDA');
        const ratio =
          0.03 + this.mockNoise(year, month, doctor, 'noshow') * 0.07;
        return Math.floor(attended * ratio);
      }
      case 'CANCELADA': {
        const attended = this.mockByState(year, month, doctor, 'ATENDIDA');
        const ratio = 0.05 + this.mockNoise(year, month, 'cancel') * 0.2;
        return Math.floor(attended * ratio);
      }
    }
  }
}

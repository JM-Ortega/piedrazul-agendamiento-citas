import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import {
  CancellationStats,
  EstadoCita,
  EstadoAgendaFiltro,
  MonthlyBreakdown,
  MonthlyTotalStat,
  DailyWorkload,
  NivelCarga,
  MotivoInasistencia,
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
  private readonly ESTADOS_TODOS: EstadoCita[] = [
    'AGENDADA',
    'ATENDIDA',
    'NO_ASISTIO',
    'CANCELADA',
  ];

  /** Años con citas registradas, del más reciente al más antiguo. */
  getAvailableYears(): Observable<number[]> {
    // TODO: Conectar con endpoint de estadísticas
    // GET `${apiUrl}/statistics/years`
    const current = new Date().getFullYear();
    return of([current, current - 1]);
  }

  /** Citas atendidas por mes del año indicado. */
  getMonthlyTotals(year: number): Observable<MonthlyTotalStat[]> {
    // TODO: Conectar con endpoint de estadísticas
    // GET `${apiUrl}/statistics/appointments/monthly?year=${year}`
    return of(
      this.MONTHS.map((month) => ({
        month,
        total: this.mockCount(year, month, ['ATENDIDA']),
      }))
    );
  }

  /** Carga diaria por médico en una fecha (YYYY-MM-DD), con total, promedio y nivel. */
  getDailyWorkload(date: string): Observable<DailyWorkload> {
    // TODO: Conectar con endpoint de estadísticas
    // GET `${apiUrl}/statistics/appointments/daily-workload?date=${date}`
    const counts = this.MOCK_DOCTORS.map((d) => ({
      doctor: d.name,
      total: this.mockDailyCount(date, d.name),
    }));
    const withAppts = counts.filter((c) => c.total > 0);
    const dayTotal = counts.reduce((sum, c) => sum + c.total, 0);
    const average =
      withAppts.length > 1
        ? Math.round((dayTotal / withAppts.length) * 10) / 10
        : 0;
    return of({
      dayTotal,
      averagePerDoctor: average,
      doctors: counts.map((c) => ({
        ...c,
        level: this.mockLoadLevel(c.total, average),
      })),
    });
  }

  /** Citas por médico y por mes. `AGENDADA` incluye todas las citas; `ATENDIDA`, solo las atendidas. */
  getMonthlyByDoctor(
    year: number,
    estado: EstadoAgendaFiltro
  ): Observable<MonthlyBreakdown> {
    // TODO: Conectar con endpoint de estadísticas
    // GET `${apiUrl}/statistics/appointments/by-doctor?year=${year}&estado=${estado}`
    const estados = this.mockEstadosDe(estado);
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

  /** Citas por especialidad y por mes. `AGENDADA` incluye todas las citas; `ATENDIDA`, solo las atendidas. */
  getMonthlyBySpecialty(
    year: number,
    estado: EstadoAgendaFiltro
  ): Observable<MonthlyBreakdown> {
    // TODO: Conectar con endpoint de estadísticas
    // GET `${apiUrl}/statistics/appointments/by-specialty?year=${year}&estado=${estado}`
    const estados = this.mockEstadosDe(estado);
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

  /** Tasa mensual y del año de citas canceladas o con inasistencia, con su cantidad. */
  getCancellationRate(
    year: number,
    motivo: MotivoInasistencia = 'CANCELADA'
  ): Observable<CancellationStats> {
    // TODO: Conectar con endpoint de estadísticas
    // Ruta sugerida: GET `${apiUrl}/statistics/appointments/cancellation-rate?year=${year}&motivo=${motivo}`
    let totalYear = 0;
    let countYear = 0;
    const months = this.MONTHS.map((month) => {
      const count = this.mockCount(year, month, [motivo]);
      const total = this.mockCount(year, month, this.ESTADOS_TODOS);
      totalYear += total;
      countYear += count;
      return {
        month,
        rate: total > 0 ? Math.round((count / total) * 100) : 0,
        count,
      };
    });
    return of({
      months,
      yearRate: totalYear > 0 ? Math.round((countYear / totalYear) * 100) : 0,
      yearCount: countYear,
    });
  }

  // ── MOCK: helpers (eliminar al conectar el backend) ───────────────────────
  private mockEstadosDe(estado: EstadoAgendaFiltro): EstadoCita[] {
    return estado === 'ATENDIDA' ? ['ATENDIDA'] : this.ESTADOS_TODOS;
  }

  private mockLoadLevel(total: number, avg: number): NivelCarga {
    if (total === 0) return 'SIN_CITAS';
    if (avg === 0) return 'NORMAL';
    if (total >= avg * 2) return 'SOBRECARGA';
    if (total > avg * 1.3) return 'ALTA';
    return 'NORMAL';
  }

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

  /** Citas de un médico en una fecha: 0 en fines de semana, 0-12 entre semana. */
  private mockDailyCount(date: string, doctor: string): number {
    const [y, m, d] = date.split('-').map(Number);
    const weekday = new Date(y, m - 1, d).getDay();
    if (weekday === 0 || weekday === 6) return 0;
    return Math.floor(this.mockNoise(date, doctor, 'daily') * 13);
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

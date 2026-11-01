import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CancellationStats,
  DailyWorkload,
  EstadoAgendaFiltro,
  MonthlyBreakdown,
  MonthlyTotalStat,
  MotivoInasistencia,
} from '../../../shared/models/dtos/statistics.dto';

/** Servicio de estadísticas del panel de administración (módulo `statistics` del backend). */
@Injectable({ providedIn: 'root' })
export class StatisticsService {
  private http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/statistics`;

  /** Años consultables, del más reciente al más antiguo. */
  getAvailableYears(): Observable<number[]> {
    return this.http.get<number[]>(`${this.baseUrl}/years`);
  }

  /** Citas atendidas por mes del año indicado. */
  getMonthlyTotals(year: number): Observable<MonthlyTotalStat[]> {
    const params = new HttpParams().set('year', year);
    return this.http.get<MonthlyTotalStat[]>(
      `${this.baseUrl}/appointments/monthly`,
      { params }
    );
  }

  /** Carga diaria por médico en una fecha (YYYY-MM-DD), con total, promedio y nivel. */
  getDailyWorkload(date: string): Observable<DailyWorkload> {
    const params = new HttpParams().set('date', date);
    return this.http.get<DailyWorkload>(
      `${this.baseUrl}/appointments/daily-workload`,
      { params }
    );
  }

  /** Citas por médico y por mes. `AGENDADA` incluye todas las citas; `ATENDIDA`, solo las atendidas. */
  getMonthlyByDoctor(
    year: number,
    estado: EstadoAgendaFiltro
  ): Observable<MonthlyBreakdown> {
    const params = new HttpParams().set('year', year).set('estado', estado);
    return this.http.get<MonthlyBreakdown>(
      `${this.baseUrl}/appointments/by-doctor`,
      { params }
    );
  }

  /** Citas por especialidad y por mes. `AGENDADA` incluye todas las citas; `ATENDIDA`, solo las atendidas. */
  getMonthlyBySpecialty(
    year: number,
    estado: EstadoAgendaFiltro
  ): Observable<MonthlyBreakdown> {
    const params = new HttpParams().set('year', year).set('estado', estado);
    return this.http.get<MonthlyBreakdown>(
      `${this.baseUrl}/appointments/by-specialty`,
      { params }
    );
  }

  /** Tasa mensual y del año de citas canceladas o con inasistencia, con su cantidad. */
  getCancellationRate(
    year: number,
    motivo: MotivoInasistencia
  ): Observable<CancellationStats> {
    const params = new HttpParams().set('year', year).set('motivo', motivo);
    return this.http.get<CancellationStats>(
      `${this.baseUrl}/appointments/cancellation-rate`,
      { params }
    );
  }
}

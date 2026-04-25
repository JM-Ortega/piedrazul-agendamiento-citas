import { Component, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { saveAs } from 'file-saver';
import { jsPDF } from 'jspdf';
import autoTable from 'jspdf-autotable';
import { KEYCLOAK_EVENT_SIGNAL } from 'keycloak-angular';
import {
  Calendar,
  CheckCircle,
  Clock,
  CreditCard,
  Download,
  FileSpreadsheet,
  FileText,
  Filter,
  LucideAngularModule,
  Phone,
  UserCircle,
} from 'lucide-angular';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import * as XLSX from 'xlsx';
import { AppointmentsPatient } from '../../../models/dtos/appointments.dto';
import { Doctor } from '../../../models/interfaces/doctor.model';
import { Patient } from '../../../models/interfaces/patient.model';
import { DoctorService } from '../../../services/doctor.service';
import { SchedulerService } from '../../../services/scheduler.service';

type ExportColumnKey =
  | 'date'
  | 'time'
  | 'patient'
  | 'documentId'
  | 'phone'
  | 'status';
type ExportColumns = Record<ExportColumnKey, boolean>;
type ExportFormat = 'excel' | 'pdf' | 'csv';
type FilterDate = 'all' | 'specific' | 'upcoming' | 'past';
type FilterStatus =
  | 'all'
  | 'AGENDADA'
  | 'REPROGRAMADA'
  | 'CANCELADA'
  | 'NO_ASISTIO'
  | 'ATENDIDA';

interface ColumnDef {
  key: ExportColumnKey;
  label: string;
  icon: any;
}

@Component({
  selector: 'app-doctor-all-appointments',
  templateUrl: './doctor-all-appointments.component.html',
  standalone: true,
  imports: [LucideAngularModule],
})
export class DoctorAllAppointmentsComponent {
  private router = inject(Router);
  private doctorService = inject(DoctorService);
  private schedulerService = inject(SchedulerService);
  private keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);

  // ── Lucide icons ──────────────────────────────────────────────────────────
  readonly Calendar = Calendar;
  readonly Clock = Clock;
  readonly FileText = FileText;
  readonly Filter = Filter;
  readonly Download = Download;
  readonly FileSpreadsheet = FileSpreadsheet;
  readonly CreditCard = CreditCard;
  readonly UserCircle = UserCircle;
  readonly CheckCircle = CheckCircle;
  readonly Phone = Phone;

  // ── State ─────────────────────────────────────────────────────────────────
  today = new Date().toISOString().split('T')[0];
  currentDoctor = signal<Doctor | null>(null);
  private allAppointments = signal<AppointmentsPatient[]>([]);
  private loaded = signal(false);
  exportingInProgress = signal(false);

  filterStatus = signal<FilterStatus>('all');
  filterDate = signal<FilterDate>('all');
  filterSpecificDate = signal<string>('');
  showExportModal = signal(false);
  exportFormat = signal<ExportFormat>('excel');
  exportColumns = signal<ExportColumns>({
    date: true,
    time: true,
    patient: true,
    documentId: true,
    phone: true,
    status: true,
  });

  readonly monthNames = [
    'enero',
    'febrero',
    'marzo',
    'abril',
    'mayo',
    'junio',
    'julio',
    'agosto',
    'septiembre',
    'octubre',
    'noviembre',
    'diciembre',
  ];

  readonly columnDefs: ColumnDef[] = [
    { key: 'date', label: 'Fecha de la Cita', icon: Calendar },
    { key: 'time', label: 'Hora de la Cita', icon: Clock },
    { key: 'patient', label: 'Nombre del Paciente', icon: UserCircle },
    { key: 'documentId', label: 'Documento de Identidad', icon: CreditCard },
    { key: 'phone', label: 'Teléfono del Paciente', icon: Phone },
    { key: 'status', label: 'Estado de la Cita', icon: CheckCircle },
  ];

  // ── Computed ──────────────────────────────────────────────────────────────
  filteredAppointments = computed(() => {
    let result = this.allAppointments();

    if (this.filterStatus() !== 'all')
      result = result.filter((a) => a.appointmentState === this.filterStatus());

    if (this.filterDate() === 'specific' && this.filterSpecificDate())
      result = result.filter((a) => a.date === this.filterSpecificDate());
    else if (this.filterDate() === 'upcoming')
      result = result.filter(
        (a) => a.date >= this.today && a.appointmentState !== 'CANCELADA',
      );
    else if (this.filterDate() === 'past')
      result = result.filter((a) => a.date < this.today);

    return [...result].sort((a, b) => {
      const d = b.date.localeCompare(a.date);
      return d !== 0 ? d : b.startTime.localeCompare(a.startTime);
    });
  });

  stats = computed(() => ({
    total: this.allAppointments().length,
    upcoming: this.allAppointments().filter(
      (a) => a.date >= this.today && a.appointmentState !== 'CANCELADA',
    ).length,
    pending: this.allAppointments().filter(
      (a) => a.appointmentState === 'REPROGRAMADA',
    ).length,
  }));

  hasSelectedColumns = computed(() =>
    Object.values(this.exportColumns()).some((v) => v),
  );

  colors = computed(() => {
    switch (this.exportFormat()) {
      case 'excel':
        return {
          header: 'bg-green-700',
          border: 'border-green-600',
          bg: 'bg-green-50',
          icon: 'text-green-600',
          button: 'bg-green-600 hover:bg-green-700',
        };
      case 'pdf':
        return {
          header: 'bg-red-700',
          border: 'border-red-600',
          bg: 'bg-red-50',
          icon: 'text-red-600',
          button: 'bg-red-600 hover:bg-red-700',
        };
      default:
        return {
          header: 'bg-orange-700',
          border: 'border-orange-600',
          bg: 'bg-orange-50',
          icon: 'text-orange-600',
          button: 'bg-orange-600 hover:bg-orange-700',
        };
    }
  });

  // ── Constructor ───────────────────────────────────────────────────────────
  constructor() {
    effect(() => {
      this.keycloakEvent();
      if (this.loaded()) return;
      this.loaded.set(true);
      this.loadData();
    });
  }

  // ── Data loading ──────────────────────────────────────────────────────────
  private loadData(): void {
    this.doctorService.getMe().subscribe({
      next: (doctor) => {
        if (!doctor) {
          this.router.navigate(['/']);
          return;
        }
        this.currentDoctor.set(doctor);
        this.schedulerService
          .getAppointmentsByDoctor(doctor.id)
          .subscribe((data) => this.allAppointments.set(data));
      },
      error: () => this.router.navigate(['/']),
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  formatDate(dateStr: string): string {
    const date = new Date(dateStr + 'T12:00:00');
    return `${date.getDate()} de ${this.monthNames[date.getMonth()]} de ${date.getFullYear()}`;
  }

  getMonthShort(dateStr: string): string {
    return this.monthNames[parseInt(dateStr.split('-')[1]) - 1].slice(0, 3);
  }

  isPast(dateStr: string): boolean {
    return dateStr < this.today;
  }

  statusLabel(s: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'Confirmada',
      ATENDIDA: 'Atendida',
      CANCELADA: 'Cancelada',
      NO_ASISTIO: 'No asistió',
      REPROGRAMADA: 'Pendiente',
    };
    return map[s] ?? s;
  }

  statusColor(s: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'bg-green-100 text-green-700 border-green-200',
      REPROGRAMADA: 'bg-yellow-100 text-yellow-700 border-yellow-200',
      CANCELADA: 'bg-red-100 text-red-700 border-red-200',
      NO_ASISTIO: 'bg-orange-100 text-orange-700 border-orange-200',
      ATENDIDA: 'bg-blue-100 text-blue-700 border-blue-200',
    };
    return map[s] ?? 'bg-gray-100 text-gray-700 border-gray-200';
  }

  accentColor(): string {
    return this.exportFormat() === 'excel'
      ? '#16a34a'
      : this.exportFormat() === 'pdf'
        ? '#dc2626'
        : '#ea580c';
  }

  toggleColumn(key: ExportColumnKey): void {
    this.exportColumns.update((cols) => ({ ...cols, [key]: !cols[key] }));
  }

  isColumnChecked(key: ExportColumnKey): boolean {
    return this.exportColumns()[key];
  }

  // ── Export ────────────────────────────────────────────────────────────────
  private resolvePhones() {
    const requests = this.filteredAppointments().map((apt) =>
      apt.documentNumber
        ? this.schedulerService
            .getByDocument(apt.documentNumber)
            .pipe(catchError(() => of(null)))
        : of(null),
    );
    return forkJoin(requests as any);
  }

  private buildExportRows(
    patients: (Patient | null)[],
  ): Record<string, string>[] {
    const cols = this.exportColumns();
    return this.filteredAppointments().map((apt, i) => {
      const row: Record<string, string> = {};
      if (cols.date) row['Fecha'] = this.formatDate(apt.date);
      if (cols.time) row['Hora'] = apt.startTime;
      if (cols.patient)
        row['Paciente'] = `${apt.patientFirstName} ${apt.patientLastName}`;
      if (cols.documentId) row['Documento'] = apt.documentNumber;
      if (cols.phone) row['Teléfono'] = patients[i]?.phone ?? '';
      if (cols.status) row['Estado'] = this.statusLabel(apt.appointmentState);
      return row;
    });
  }

  handleExport(): void {
    if (!this.hasSelectedColumns()) return;
    this.exportingInProgress.set(true);

    if (!this.exportColumns().phone) {
      const data = this.buildExportRows(
        this.filteredAppointments().map(() => null),
      );
      this.generateFile(data);
      this.exportingInProgress.set(false);
      this.showExportModal.set(false);
      return;
    }

    this.resolvePhones().subscribe({
      next: (patients) => {
        const data = this.buildExportRows(patients as (Patient | null)[]);
        this.generateFile(data);
        this.exportingInProgress.set(false);
        this.showExportModal.set(false);
      },
      error: () => {
        this.exportingInProgress.set(false);
        this.showExportModal.set(false);
      },
    });
  }

  private generateFile(data: Record<string, string>[]): void {
    if (this.exportFormat() === 'excel') this.exportToExcel(data);
    else if (this.exportFormat() === 'pdf') this.exportToPDF(data);
    else this.exportToCSV(data);
  }

  private exportToExcel(data: Record<string, string>[]): void {
    const ws = XLSX.utils.json_to_sheet(data);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, 'Citas');
    XLSX.writeFile(wb, `Mis_Citas_${this.today}.xlsx`);
  }

  private exportToPDF(data: Record<string, string>[]): void {
    const doc = new jsPDF();
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(16);
    doc.text('Reporte de Mis Citas - Piedrazul Salud', 14, 15);
    doc.setFont('helvetica', 'normal');
    doc.setFontSize(10);
    doc.text(`Médico: ${this.currentDoctor()?.name}`, 14, 22);
    doc.text(`Generado: ${this.formatDate(this.today)}`, 14, 28);
    autoTable(doc, {
      head: [Object.keys(data[0] ?? {})],
      body: data.map((row) => Object.values(row)),
      startY: 34,
      styles: { fontSize: 9, cellPadding: 3 },
      headStyles: { fillColor: [37, 99, 235], textColor: 255 },
    });
    doc.save(`Mis_Citas_${this.today}.pdf`);
  }

  private exportToCSV(data: Record<string, string>[]): void {
    if (data.length === 0) return;
    const headers = Object.keys(data[0]);
    const csvContent = [
      headers.join(','),
      ...data.map((row) =>
        headers
          .map((h) => {
            const val = String(row[h] || '');
            return val.includes(',') || val.includes('\n') || val.includes('"')
              ? `"${val.replace(/"/g, '""')}"`
              : val;
          })
          .join(','),
      ),
    ].join('\n');
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    saveAs(blob, `Mis_Citas_${this.today}.csv`);
  }
}

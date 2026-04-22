import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { saveAs } from 'file-saver';
import { jsPDF } from 'jspdf';
import autoTable from 'jspdf-autotable';
import {
  Calendar,
  CheckCircle,
  Clock,
  CreditCard,
  Download,
  FileSpreadsheet,
  FileText,
  LucideAngularModule,
  Phone,
  PlusCircle,
  Search,
  Stethoscope,
  Tag,
  User,
  UserCircle,
} from 'lucide-angular';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import * as XLSX from 'xlsx';
import { AppointmentsPatient } from '../../../../models/dtos/appointments.dto';
import { dtoDoctor } from '../../../../models/dtos/doctor.dto';
import { Patient } from '../../../../models/interfaces/patient.model';
import { SchedulerService } from '../../../../services/scheduler.service';

// ── Tipos explícitos para evitar errores de inferencia en templates ──────────
type ExportColumnKey =
  | 'date'
  | 'time'
  | 'patient'
  | 'documentId'
  | 'phone'
  | 'doctor'
  | 'specialty'
  | 'status';

type ExportColumns = Record<ExportColumnKey, boolean>;

interface ColumnDef {
  key: ExportColumnKey;
  label: string;
  icon: any;
}

@Component({
  selector: 'app-scheduler-dashboard',
  templateUrl: './scheduler-dashboard.component.html',
  standalone: true,
  imports: [RouterLink, LucideAngularModule],
})
export class SchedulerDashboardComponent implements OnInit {
  private schedulerService = inject(SchedulerService);

  // ── Lucide icons ──────────────────────────────────────────────────────────
  readonly Calendar = Calendar;
  readonly CheckCircle = CheckCircle;
  readonly Clock = Clock;
  readonly CreditCard = CreditCard;
  readonly Download = Download;
  readonly FileSpreadsheet = FileSpreadsheet;
  readonly FileText = FileText;
  readonly Phone = Phone;
  readonly PlusCircle = PlusCircle;
  readonly Search = Search;
  readonly Stethoscope = Stethoscope;
  readonly Tag = Tag;
  readonly User = User;
  readonly UserCircle = UserCircle;

  // ── Date helpers ──────────────────────────────────────────────────────────
  today = new Date().toISOString().split('T')[0];
  dayNames = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];
  monthNames = [
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

  // ── Data signals ──────────────────────────────────────────────────────────
  doctors = signal<dtoDoctor[]>([]);
  private appointments = signal<AppointmentsPatient[]>([]);

  // ── Filter / view signals ─────────────────────────────────────────────────
  viewMode = signal<'all' | 'today'>('all');
  filterDate = signal('');
  filterDoctor = signal('');
  searched = signal(false);

  // ── Export signals ────────────────────────────────────────────────────────
  showExportModal = signal(false);
  exportFormat = signal<'excel' | 'pdf' | 'csv'>('excel');
  exportingInProgress = signal(false);
  exportColumns = signal<ExportColumns>({
    date: true,
    time: true,
    patient: true,
    documentId: true,
    phone: true,
    doctor: true,
    specialty: true,
    status: true,
  });

  // ── Column definitions (tipo explícito, sin typeof this) ─────────────────
  readonly columnDefs: ColumnDef[] = [
    { key: 'date', label: 'Fecha de la Cita', icon: Calendar },
    { key: 'time', label: 'Hora de la Cita', icon: Clock },
    { key: 'patient', label: 'Nombre del Paciente', icon: UserCircle },
    { key: 'documentId', label: 'Documento de Identidad', icon: CreditCard },
    { key: 'phone', label: 'Teléfono del Paciente', icon: Phone },
    { key: 'doctor', label: 'Nombre del Médico', icon: Stethoscope },
    { key: 'specialty', label: 'Especialidad', icon: Tag },
    { key: 'status', label: 'Estado de la Cita', icon: CheckCircle },
  ];

  // ── Computed ──────────────────────────────────────────────────────────────
  selectedDoctor = computed(() =>
    this.doctors().find((d) => d.name === this.filterDoctor()),
  );

  todayCount = computed(
    () =>
      this.appointments().filter(
        (a) => a.date === this.today && a.appointmentState !== 'CANCELADA',
      ).length,
  );

  allActiveCount = computed(
    () =>
      this.appointments().filter((a) => a.appointmentState !== 'CANCELADA')
        .length,
  );

  results = computed(() => {
    let filtered = this.appointments();
    if (this.viewMode() === 'today')
      filtered = filtered.filter((a) => a.date === this.today);
    if (this.filterDoctor())
      filtered = filtered.filter((a) => a.doctorName === this.filterDoctor());
    if (this.filterDate())
      filtered = filtered.filter((a) => a.date === this.filterDate());
    return [...filtered].sort((a, b) =>
      a.date === b.date
        ? a.startTime > b.startTime
          ? 1
          : -1
        : a.date > b.date
          ? 1
          : -1,
    );
  });

  activeResults = computed(() =>
    this.results().filter((a) => a.appointmentState !== 'CANCELADA'),
  );

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

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.schedulerService
      .getDoctors()
      .subscribe((data) => this.doctors.set(data));
    this.schedulerService.getAllAppointments().subscribe((data) => {
      this.appointments.set(data);
      this.searched.set(true);
    });
  }

  // ── View / filter methods ─────────────────────────────────────────────────
  setViewMode(mode: 'all' | 'today'): void {
    this.viewMode.set(mode);
  }
  clearDoctorFilter(): void {
    this.filterDoctor.set('');
  }
  clearDateFilter(): void {
    this.filterDate.set('');
  }

  search(): void {
    const date = this.filterDate();
    const doctorId = this.filterDoctor();
    let request$;
    if (date && doctorId)
      request$ = this.schedulerService.getAppointmentsByDateAndDoctor(
        date,
        doctorId,
      );
    else if (date) request$ = this.schedulerService.getAppointmentsByDate(date);
    else if (doctorId)
      request$ = this.schedulerService.getAppointmentsByDoctor(doctorId);
    else request$ = this.schedulerService.getAllAppointments();
    request$.subscribe((data) => {
      this.appointments.set(data);
      this.searched.set(true);
    });
  }

  formatDate(dateStr: string): string {
    const d = new Date(dateStr + 'T12:00:00');
    return `${this.dayNames[d.getDay()]} ${d.getDate()} de ${this.monthNames[d.getMonth()]} de ${d.getFullYear()}`;
  }

  statusLabel(s: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'Agendada',
      ATENDIDA: 'Atendida',
      CANCELADA: 'Cancelada',
      NO_ASISTIO: 'No asistió',
      REPROGRAMADA: 'Reprogramada',
    };
    return map[s] ?? s;
  }

  statusColor(s: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'bg-blue-100 text-blue-700',
      ATENDIDA: 'bg-green-100 text-green-700',
      CANCELADA: 'bg-red-100 text-red-700',
      NO_ASISTIO: 'bg-orange-100 text-orange-700',
      REPROGRAMADA: 'bg-yellow-100 text-yellow-700',
    };
    return map[s] ?? '';
  }

  // ── Export helpers ────────────────────────────────────────────────────────
  toggleColumn(column: ExportColumnKey): void {
    this.exportColumns.update((cols) => ({ ...cols, [column]: !cols[column] }));
  }

  isColumnChecked(key: ExportColumnKey): boolean {
    return this.exportColumns()[key];
  }

  accentColor(): string {
    return this.exportFormat() === 'excel'
      ? '#16a34a'
      : this.exportFormat() === 'pdf'
        ? '#dc2626'
        : '#ea580c';
  }

  // Resuelve los teléfonos en paralelo via getByDocument antes de exportar
  private resolvePhones() {
    const apts = this.results();
    const requests = apts.map((apt) =>
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
    return this.results().map((apt, i) => {
      const row: Record<string, string> = {};
      if (cols.date) row['Fecha'] = this.formatDate(apt.date);
      if (cols.time) row['Hora'] = apt.startTime;
      if (cols.patient)
        row['Paciente'] = `${apt.patientFirstName} ${apt.patientLastName}`;
      if (cols.documentId) row['Documento'] = apt.documentNumber ?? '';
      if (cols.phone) row['Teléfono'] = patients[i]?.phone ?? '';
      if (cols.doctor) row['Médico'] = apt.doctorName ?? '';
      if (cols.specialty) row['Especialidad'] = apt.specialty ?? '';
      if (cols.status) row['Estado'] = this.statusLabel(apt.appointmentState);
      return row;
    });
  }

  handleExport(): void {
    if (!this.hasSelectedColumns()) return;
    this.exportingInProgress.set(true);

    // Si el teléfono no está en las columnas seleccionadas, exporta directamente
    if (!this.exportColumns().phone) {
      const data = this.buildExportRows(this.results().map(() => null));
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
    XLSX.writeFile(wb, `Citas_${this.today}.xlsx`);
  }

  private exportToPDF(data: Record<string, string>[]): void {
    const doc = new jsPDF();
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(16);
    doc.text('Reporte de Citas - Piedrazul Salud', 14, 15);
    doc.setFont('helvetica', 'normal');
    doc.setFontSize(10);
    doc.text(`Generado: ${this.formatDate(this.today)}`, 14, 22);
    autoTable(doc, {
      head: [Object.keys(data[0] ?? {})],
      body: data.map((row) => Object.values(row)),
      startY: 28,
      styles: { fontSize: 9, cellPadding: 3 },
      headStyles: { fillColor: [37, 99, 235], textColor: 255 },
    });
    doc.save(`Citas_${this.today}.pdf`);
  }

  private exportToCSV(data: Record<string, string>[]): void {
    if (data.length === 0) return;
    const headers = Object.keys(data[0]);
    const csvContent = [
      headers.join(','),
      ...data.map((row) =>
        headers
          .map((header) => {
            const value = String(row[header] || '');
            return value.includes(',') ||
              value.includes('\n') ||
              value.includes('"')
              ? `"${value.replace(/"/g, '""')}"`
              : value;
          })
          .join(','),
      ),
    ].join('\n');
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    saveAs(blob, `Citas_${this.today}.csv`);
  }
}

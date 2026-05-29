import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  LucideAngularModule, FolderOpen, Calendar, ArrowLeft, ClipboardList,
} from 'lucide-angular';
import {MedicalRecord} from '../../../shared/models/dtos/medicalRecord.dto';
import {AppService} from '../../../core/services/app.service';
import { PatientService } from '../../../core/services/patient.service';

@Component({
  selector: 'app-patient-medical-history',
  templateUrl: './patient-medical-history.component.html',
  standalone: true,
  imports: [RouterLink, LucideAngularModule],
})
export class PatientMedicalHistoryComponent implements OnInit {
  protected appService = inject(AppService);
  protected patientService = inject(PatientService);

  readonly FolderOpen = FolderOpen;
  readonly Calendar = Calendar;
  readonly ArrowLeft = ArrowLeft;
  readonly ClipboardList = ClipboardList;

  records = signal<MedicalRecord[]>([]);
  error = signal<string | null>(null);

  ngOnInit(): void {
    this.patientService.getMe().subscribe({
      next: (patient) => {
        this.patientService.loadMyMedicalRecords(patient.id);
        this.records = this.patientService.medicalRecords;
        this.error = this.patientService.error;
      },
      error: () => {
        this.error.set('No se pudo obtener la información del paciente.');
      }
    });
  }
}
import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { DoctorService } from '../../../core/services/doctor.service';
import {
  LucideAngularModule, ClipboardPen, ArrowLeft,
  ClipboardPlus, Save, FolderOpen, Calendar,
} from 'lucide-angular';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';

@Component({
  selector: 'app-doctor-medical-history',
  templateUrl: './doctor-medical-history.component.html',
  standalone: true,
  imports: [RouterLink, LucideAngularModule, FormatoPipe],
})
export class DoctorMedicalHistoryComponent implements OnInit {
  private route = inject(ActivatedRoute);
  readonly doctorService = inject(DoctorService);

  readonly ClipboardPen = ClipboardPen;
  readonly ArrowLeft = ArrowLeft;
  readonly ClipboardPlus = ClipboardPlus;
  readonly Save = Save;
  readonly FolderOpen = FolderOpen;
  readonly Calendar = Calendar;

  readonly mostrarInfo = signal(false);
  toggleInfo() { this.mostrarInfo.update((v) => !v); }

  readonly records = this.doctorService.medicalRecords;
  readonly patient = signal<Patient | undefined>(undefined);
  readonly newObservation = signal('');
  private readonly idAppointment = signal<string>('');

  ngOnInit(): void {
    const idAppointment = this.route.snapshot.paramMap.get('idAppointment') ?? '';
    this.idAppointment.set(idAppointment);

    this.doctorService.getPatientByAppointment(idAppointment).subscribe((patient) => {
      this.patient.set(patient);
      this.doctorService.loadMedicalRecordsByPatient(patient.id);
    });
  }

  addRecord(): void {
    const observations = this.newObservation().trim();
    const idCita = this.idAppointment();

    if (!observations || !idCita) return;

    this.doctorService.addMedicalRecord(idCita, observations).subscribe((saved) => {
      this.doctorService.medicalRecords.update((current) => [saved, ...current]);
      this.newObservation.set('');
    });
  }
}
import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';
import {
  LucideAngularModule, FolderOpen, Calendar,
} from 'lucide-angular';

@Component({
  selector: 'app-patient-medical-history',
  templateUrl: './patient-medical-history.component.html',
  standalone: true,
  imports: [RouterLink, LucideAngularModule, FormatoPipe],
})
export class PatientMedicalHistoryComponent implements OnInit {
    readonly FolderOpen = FolderOpen;
    readonly Calendar = Calendar;

    ngOnInit(): void {
    }
}
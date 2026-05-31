import { Component, EventEmitter, Input, Output } from '@angular/core';
import {
  Calendar,
  CircleAlert,
  LucideAngularModule,
  Stethoscope,
} from 'lucide-angular';

type Role = 'doctor' | 'scheduler';

@Component({
  selector: 'app-create-user-roles',
  templateUrl: './create-user-roles.component.html',
  standalone: true,
  imports: [LucideAngularModule],
})
export class CreateUserRolesComponent {
  readonly Stethoscope = Stethoscope;
  readonly Calendar = Calendar;
  readonly CircleAlert = CircleAlert;

  @Input() selectedRoles: Role[] = [];
  @Input() errorRoles?: string;
  @Output() roleToggled = new EventEmitter<Role>();
}

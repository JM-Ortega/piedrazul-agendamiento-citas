import { Injectable } from '@angular/core';
import { validateDocumentForType } from '../../../shared/helpers/document-validation';
import { timeToMinutes } from '../../../shared/helpers/time-utils';
import { FormErrors } from '../models/interfaces/FormErrors';
import { UserForm } from '../models/interfaces/UserForm';

@Injectable({ providedIn: 'root' })
export class UserFormValidationService {
  readonly FIELD_ORDER: (keyof FormErrors)[] = [
    'roles',
    'documentId',
    'password',
    'firstName',
    'lastName',
    'email',
    'identificationType',
    'phone',
    'specialty',
    'laborStart',
    'laborEnd',
    'startTime',
    'endTime',
    'interval',
    'workDays',
  ];

  readonly FIELD_TO_ELEMENT_ID: Partial<Record<keyof FormErrors, string>> = {
    documentId: 'documentId',
    password: 'password',
    firstName: 'firstName',
    lastName: 'lastName',
    email: 'email',
    identificationType: 'identificationType',
    phone: 'phone',
    laborStart: 'laborStart',
    laborEnd: 'laborEnd',
    startTime: 'startTime',
    endTime: 'endTime',
    interval: 'interval',
  };

  readonly SCHEDULE_GROUP_FIELDS: (keyof FormErrors)[] = [
    'startTime',
    'endTime',
    'interval',
    'workDays',
    'bookingWindowWeeks',
  ];

  isScheduleGroupTouched(form: UserForm): boolean {
    return !!(
      form.startTime ||
      form.endTime ||
      form.interval ||
      (form.workDays && form.workDays.length > 0) ||
      form.bookingWindowWeeks
    );
  }

  validateField(
    field: keyof FormErrors,
    form: UserForm,
    hasDoctorRole: boolean,
    selectedRolesLength: number
  ): string | undefined {
    const scheduleTouched = this.isScheduleGroupTouched(form);

    switch (field) {
      case 'documentId': {
        const trimmed = form.documentId.trim();
        if (!trimmed) return 'El documento de identidad es obligatorio.';
        return (
          validateDocumentForType(form.identificationType, trimmed) || undefined
        );
      }

      case 'password':
        if (!form.password) return 'La contraseña es obligatoria.';
        if (form.password.length < 6) return 'Mínimo 6 caracteres.';
        return undefined;

      case 'firstName':
        if (!form.firstName.trim()) return 'El nombre es obligatorio.';
        if (form.firstName.trim().length < 2) return 'Mínimo 2 caracteres.';
        return undefined;

      case 'lastName':
        if (!form.lastName.trim()) return 'El apellido es obligatorio.';
        if (form.lastName.trim().length < 2) return 'Mínimo 2 caracteres.';
        return undefined;

      case 'roles':
        return selectedRolesLength === 0
          ? 'Debe seleccionar al menos un rol.'
          : undefined;

      case 'email':
        if (!form.email.trim()) return 'El correo electrónico es obligatorio.';
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim()))
          return 'Ingrese un correo electrónico válido.';
        return undefined;

      case 'identificationType':
        return !form.identificationType
          ? 'El tipo de documento es obligatorio.'
          : undefined;

      case 'phone':
        if (!form.phone.trim()) return 'El teléfono es obligatorio.';
        if (!/^\d{10}$/.test(form.phone))
          return 'El teléfono debe tener exactamente 10 dígitos.';
        return undefined;

      case 'specialty':
        return hasDoctorRole && form.specialty.length === 0
          ? 'Debe seleccionar al menos una especialidad.'
          : undefined;

      case 'laborStart':
        if (!hasDoctorRole) return undefined;
        if (!form.laborStart)
          return 'La fecha de inicio laboral es obligatoria.';
        if (parseInt(form.laborStart.split('-')[0], 10) > 9999)
          return 'El año no puede tener más de 4 dígitos.';
        if (form.laborEnd && form.laborStart >= form.laborEnd)
          return 'La fecha de inicio debe ser anterior a la fecha de fin.';
        return undefined;

      case 'laborEnd':
        if (!hasDoctorRole) return undefined;
        if (!form.laborEnd) return 'La fecha de fin laboral es obligatoria.';
        if (parseInt(form.laborEnd.split('-')[0], 10) > 9999)
          return 'El año no puede tener más de 4 dígitos.';
        if (form.laborStart && form.laborStart >= form.laborEnd)
          return 'La fecha de fin debe ser posterior a la fecha de inicio.';
        return undefined;

      case 'startTime': {
        if (!hasDoctorRole || !scheduleTouched) return undefined;
        if (!form.startTime) return 'La hora de inicio es obligatoria.';
        const start = timeToMinutes(form.startTime);
        const end = timeToMinutes(form.endTime);
        return form.endTime && start >= end
          ? 'La hora de inicio no puede ser igual o posterior a la hora de fin.'
          : undefined;
      }

      case 'endTime': {
        if (!hasDoctorRole || !scheduleTouched) return undefined;
        if (!form.endTime) return 'La hora de fin es obligatoria.';
        const end = timeToMinutes(form.endTime);
        const start = timeToMinutes(form.startTime);
        return form.startTime && start >= end
          ? 'La hora de fin debe ser posterior a la hora de inicio.'
          : undefined;
      }

      case 'interval': {
        if (!hasDoctorRole || !scheduleTouched) return undefined;
        if (!form.interval || form.interval < 10)
          return 'El intervalo mínimo es 10 minutos.';
        const duration =
          timeToMinutes(form.endTime) - timeToMinutes(form.startTime);
        if (duration > 0 && form.interval > duration)
          return `El intervalo no puede superar la duración del turno (${duration} min).`;
        return undefined;
      }

      case 'workDays':
        if (!hasDoctorRole || !scheduleTouched) return undefined;
        return form.workDays.length === 0
          ? 'Debe seleccionar al menos un día de atención.'
          : undefined;

      case 'bookingWindowWeeks':
        if (!hasDoctorRole || !scheduleTouched) return undefined;
        if (!form.bookingWindowWeeks)
          return 'Debe indicar la ventana de reserva en semanas.';
        if (form.bookingWindowWeeks < 1 || form.bookingWindowWeeks > 10)
          return 'La ventana de reserva debe estar entre 1 y 10 semanas.';
        return undefined;

      default:
        return undefined;
    }
  }

  validateAll(
    form: UserForm,
    hasDoctorRole: boolean,
    selectedRolesLength: number
  ): FormErrors {
    const fields: (keyof FormErrors)[] = [
      'documentId',
      'password',
      'identificationType',
      'phone',
      'firstName',
      'lastName',
      'email',
      'roles',
    ];
    if (hasDoctorRole) {
      fields.push(
        'specialty',
        'laborStart',
        'laborEnd',
        'startTime',
        'endTime',
        'interval',
        'workDays',
        'bookingWindowWeeks'
      );
    }
    const errors: FormErrors = {};
    fields.forEach((f) => {
      errors[f] = this.validateField(
        f,
        form,
        hasDoctorRole,
        selectedRolesLength
      );
    });
    return errors;
  }
}

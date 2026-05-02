import { Pipe, PipeTransform } from '@angular/core';

/**
 * Transforma especialidades que llegan del backend en texto legible.
 */
@Pipe({
  name: 'specialty',
  standalone: true,
})
export class SpecialtyPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) return '';
    return value
      .replace(/[\[\]"]/g, '')
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/^\w/, (c) => c.toUpperCase());
  }
}
import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'errores',
  standalone: true,
})
export class ErroresPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) return '';
    const formatted = value
        .replace(/[\[\]"]/g, '')
        .replace(/_/g, ' ')
        .toLowerCase();
    return formatted.charAt(0).toUpperCase() + formatted.slice(1);
    }
}
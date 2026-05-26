import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'formato',
  standalone: true,
})
export class FormatoPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) return '';
    return value
      .replace(/[\[\]"]/g, '')
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }
}
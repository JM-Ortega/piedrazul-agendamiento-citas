// to-select-options.pipe.ts
import { Pipe, PipeTransform } from '@angular/core';
import { SelectOption } from '../../design-system/atoms/select/select.component';
import { FormatoPipe } from './formatoPipe';

@Pipe({
  name: 'toSelectOptions',
  standalone: true,
})
export class ToSelectOptionsPipe implements PipeTransform {
  private formatoPipe = new FormatoPipe();

  transform(
    values: string[] | null | undefined,
    useFormato = true
  ): SelectOption[] {
    return (values ?? []).map((v) => ({
      value: v,
      label: useFormato ? this.formatoPipe.transform(v) : v,
    }));
  }
}

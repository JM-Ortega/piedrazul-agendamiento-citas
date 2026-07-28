import { Pipe, PipeTransform } from '@angular/core';
import { SelectOption } from '../../design-system/atoms/select/select.component';
import { FormatoPipe } from './formatoPipe';

@Pipe({
  name: 'toSelectOptions',
  standalone: true,
})
export class ToSelectOptionsPipe implements PipeTransform {
  private formatoPipe = new FormatoPipe(); // 👈 vuelve a 'new'

  transform(values: string[] | null | undefined): SelectOption[] {
    return (values ?? []).map((v) => ({
      value: v,
      label: this.formatoPipe.transform(v),
    }));
  }
}

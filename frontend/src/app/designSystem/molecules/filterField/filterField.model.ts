import { SelectOption } from '../../atoms/select/select.component';

export type FilterFieldType = 'select' | 'date';

export interface FilterFieldConfig {
  id: string;
  type: FilterFieldType;
  label: string;
  placeholder?: string;
  options?: SelectOption[];
  formatValue?: (value: string) => string;
}

import {
  Component,
  EventEmitter,
  forwardRef,
  inject,
  Input,
  Output,
} from '@angular/core';
import {
  ControlValueAccessor,
  NG_VALUE_ACCESSOR,
  FormsModule,
} from '@angular/forms';
import { MatDatepickerModule } from '@angular/material/datepicker';
import {
  DateAdapter,
  MAT_DATE_FORMATS,
  MAT_DATE_LOCALE,
} from '@angular/material/core';
import { LucideCalendar } from '@lucide/angular';
import { ButtonComponent } from '../../atoms/button/button.component';
import {
  CustomDateAdapter,
  DateFormatPreset,
  DATE_INPUT_FORMAT_MARKER,
  parseLocalDateString,
} from './customDateAdapter';

const APP_DATE_FORMATS = {
  parse: { dateInput: DATE_INPUT_FORMAT_MARKER },
  display: {
    dateInput: DATE_INPUT_FORMAT_MARKER,
    monthYearLabel: { year: 'numeric', month: 'short' },
    dateA11yLabel: { year: 'numeric', month: 'long', day: 'numeric' },
    monthYearA11yLabel: { year: 'numeric', month: 'long' },
  },
};

const DEFAULT_PLACEHOLDERS: Record<DateFormatPreset, string> = {
  'dd/mm/yyyy': 'dd/mm/aaaa',
  'mm/dd/yyyy': 'mm/dd/aaaa',
  'yyyy-mm-dd': 'aaaa-mm-dd',
};

/** Longitud de cada grupo de dígitos y separador, según el formato. */
const MASK_CONFIG: Record<
  DateFormatPreset,
  { groups: number[]; separator: string }
> = {
  'dd/mm/yyyy': { groups: [2, 2, 4], separator: '/' },
  'mm/dd/yyyy': { groups: [2, 2, 4], separator: '/' },
  'yyyy-mm-dd': { groups: [4, 2, 2], separator: '-' },
};

@Component({
  selector: 'app-datepicker',
  standalone: true,
  imports: [FormsModule, MatDatepickerModule, LucideCalendar, ButtonComponent],
  providers: [
    { provide: MAT_DATE_LOCALE, useValue: 'es-CO' },
    { provide: DateAdapter, useClass: CustomDateAdapter },
    { provide: MAT_DATE_FORMATS, useValue: APP_DATE_FORMATS },
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => DatepickerComponent),
      multi: true,
    },
  ],
  templateUrl: './datepicker.component.html',
})
export class DatepickerComponent implements ControlValueAccessor {
  private dateAdapter = inject(DateAdapter) as CustomDateAdapter;

  private _format: DateFormatPreset = 'dd/mm/yyyy';
  @Input()
  set format(value: DateFormatPreset) {
    this._format = value;
    this.dateAdapter.pattern = value;
  }
  get format(): DateFormatPreset {
    return this._format;
  }

  @Input() id = `app-datepicker-${Math.random().toString(36).slice(2, 10)}`;
  @Input() placeholder?: string;
  @Input() disabled = false;
  @Input() min?: Date;
  @Input() max?: Date;
  @Input() errorMessage?: string;
  @Input() helperText?: string;
  @Input() fullWidth = true;
  @Input() wrapperClass = '';
  @Input() inputClass = '';
  @Input() invalidDateMessage = 'La fecha ingresada no es válida';

  @Output() valueChange = new EventEmitter<Date | null>();
  @Output() dateBlur = new EventEmitter<FocusEvent>();
  @Output() internalErrorChange = new EventEmitter<string>();

  value: Date | null = null;
  internalMessage = '';

  // eslint-disable-next-line @typescript-eslint/no-unused-vars, @typescript-eslint/no-empty-function
  private onChange = (_: Date | null) => {};
  // eslint-disable-next-line @typescript-eslint/no-empty-function
  private onTouched = () => {};

  get resolvedErrorMessage(): string | undefined {
    return this.errorMessage || this.internalMessage || undefined;
  }

  get resolvedPlaceholder(): string {
    return this.placeholder ?? DEFAULT_PLACEHOLDERS[this.format];
  }

  get computedWrapperClasses(): string {
    return ['space-y-2', this.fullWidth ? 'w-full' : '', this.wrapperClass]
      .filter(Boolean)
      .join(' ');
  }

  get computedInputClasses(): string {
    const base = [
      this.fullWidth ? 'w-full' : '',
      'rounded-xl',
      'border',
      'shadow-sm',
      'transition duration-200',
      'text-gray-900',
      'bg-white',
      'placeholder:text-gray-400',
      'focus:outline-none',
      'border-[#c2d8f0]',
      'focus:border-[#4e92d9]',
      'px-4',
      'py-3',
      'pr-12',
    ];
    if (this.resolvedErrorMessage)
      base.push('border-red-400', 'focus:border-red-500');
    if (this.disabled)
      base.push('bg-gray-100', 'text-gray-500', 'cursor-not-allowed');
    if (this.inputClass) base.push(this.inputClass);
    return base.filter(Boolean).join(' ');
  }

  onRawInput(event: Event): void {
    const el = event.target as HTMLInputElement;
    const digits = el.value.replace(/\D/g, '').slice(0, 8);
    const masked = this.buildMask(digits);
    el.value = masked;

    if (digits.length < 8) {
      this.setInternalMessage('');
      return;
    }

    const parsed = this.dateAdapter.parse(masked);
    if (parsed && this.dateAdapter.isValid(parsed)) {
      this.setInternalMessage('');
      this.handleDateChange(parsed);
    } else {
      this.setInternalMessage(this.invalidDateMessage);
      this.handleDateChange(null);
    }
  }

  handleDateChange(date: Date | null): void {
    this.value = date;
    this.valueChange.emit(date);
    this.onChange(date);
  }

  handleBlur(event: FocusEvent): void {
    this.onTouched();
    this.dateBlur.emit(event);
  }

  writeValue(value: Date | string | null): void {
    if (!value) {
      this.value = null;
    } else if (value instanceof Date) {
      this.value = value;
    } else {
      this.value = parseLocalDateString(value);
    }
  }

  registerOnChange(fn: (value: Date | null) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }

  private buildMask(digits: string): string {
    const { groups, separator } = MASK_CONFIG[this.format];
    let result = '';
    let idx = 0;
    groups.forEach((len, i) => {
      if (digits.length > idx) {
        if (i > 0) result += separator;
        result += digits.slice(idx, idx + len);
        idx += len;
      }
    });
    return result;
  }

  private setInternalMessage(text: string): void {
    this.internalMessage = text;
    this.internalErrorChange.emit(text);
  }
}

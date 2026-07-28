import {
  Component,
  EventEmitter,
  forwardRef,
  Input,
  Output,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

export interface SelectOption {
  value: string;
  label: string;
}

export type SelectVariant = 'default' | 'outline' | 'ghost';
export type SelectSize = 'sm' | 'md' | 'lg';

@Component({
  selector: 'app-select',
  standalone: true,
  imports: [],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => SelectComponent),
      multi: true,
    },
  ],
  templateUrl: './select.component.html',
})
export class SelectComponent implements ControlValueAccessor {
  @Input() id = `app-select-${Math.random().toString(36).slice(2, 10)}`;
  @Input() options: SelectOption[] = [];
  @Input() placeholder = 'Seleccione...';
  @Input() loading = false;
  @Input() loadingText = 'Cargando...';
  @Input() disabled = false;
  @Input() variant: SelectVariant = 'default';
  @Input() size: SelectSize = 'md';
  @Input() fullWidth = true;
  @Input() errorMessage?: string;
  @Input() helperText?: string;
  @Input() hasPrefix = false;
  @Input() hasSuffix = false;

  @Output() valueChange = new EventEmitter<string>();
  @Output() selectBlur = new EventEmitter<FocusEvent>();
  @Output() selectFocus = new EventEmitter<FocusEvent>();

  value = '';

  // eslint-disable-next-line @typescript-eslint/no-unused-vars, @typescript-eslint/no-empty-function
  private onChange = (_value: string) => {};
  // eslint-disable-next-line @typescript-eslint/no-empty-function
  private onTouched = () => {};

  get computedWrapperClasses(): string {
    return ['space-y-2', this.fullWidth ? 'w-full' : '']
      .filter(Boolean)
      .join(' ');
  }

  get computedSelectClasses(): string {
    const baseClasses = [
      this.fullWidth ? 'w-full' : '',
      'rounded-xl',
      'border-2',
      'shadow-sm',
      'transition',
      'focus:outline-none',
      'focus:ring-2',
      this.hasPrefix ? 'pl-12' : 'px-4',
      this.hasSuffix ? 'pr-12' : 'pr-4',
      'py-3',
      'text-base',
    ];

    baseClasses.push(
      this.errorMessage
        ? 'border-red-400 focus:ring-red-400 bg-red-50'
        : 'border-gray-300 focus:ring-blue-500'
    );

    if (this.disabled) {
      baseClasses.push('bg-gray-100', 'text-gray-500', 'cursor-not-allowed');
    }

    return baseClasses.filter(Boolean).join(' ');
  }

  handleChange(event: Event): void {
    const target = event.target as HTMLSelectElement;
    this.value = target.value;
    this.valueChange.emit(this.value);
    this.onChange(this.value);
  }

  handleBlur(event: FocusEvent): void {
    this.onTouched();
    this.selectBlur.emit(event);
  }

  handleFocus(event: FocusEvent): void {
    this.selectFocus.emit(event);
  }

  writeValue(value: string): void {
    this.value = value;
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }
}

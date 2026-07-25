import {
  Component,
  EventEmitter,
  forwardRef,
  Input,
  Output,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

export type InputType =
  | 'text'
  | 'email'
  | 'tel'
  | 'date'
  | 'number'
  | 'password'
  | 'search'
  | 'url'
  | 'checkbox';

export type InputVariant = 'default' | 'outline' | 'ghost';
export type InputSize = 'sm' | 'md' | 'lg';

@Component({
  selector: 'app-input',
  standalone: true,
  imports: [],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => InputComponent),
      multi: true,
    },
  ],
  templateUrl: './input.component.html',
})
export class InputComponent implements ControlValueAccessor {
  @Input() id = `app-input-${Math.random().toString(36).slice(2, 10)}`;
  @Input() type: InputType = 'text';
  @Input() label?: string;
  @Input() placeholder?: string;
  @Input() helperText?: string;
  @Input() errorMessage?: string;
  @Input() autocomplete?: string;
  @Input() inputMode?: string;
  @Input() min?: string | number;
  @Input() max?: string | number;
  @Input() step?: string | number;
  @Input() readonly = false;
  @Input() disabled = false;
  @Input() variant: InputVariant = 'default';
  @Input() size: InputSize = 'md';
  @Input() fullWidth = true;
  @Input() inputClass = '';
  @Input() labelClass = '';
  @Input() wrapperClass = '';

  @Output() valueChange = new EventEmitter<string | number | boolean | null>();
  @Output() inputChange = new EventEmitter<Event>();
  @Output() inputBlur = new EventEmitter<FocusEvent>();
  @Output() inputFocus = new EventEmitter<FocusEvent>();

  value: string | number | boolean | null = '';
  // eslint-disable-next-line @typescript-eslint/no-unused-vars, @typescript-eslint/no-empty-function
  private onChange = (_: string | number | boolean | null) => {};
  // eslint-disable-next-line @typescript-eslint/no-empty-function
  private onTouched = () => {};

  get isCheckbox(): boolean {
    return this.type === 'checkbox';
  }

  get computedWrapperClasses(): string {
    const classes = [
      'space-y-2',
      this.fullWidth ? 'w-full' : '',
      this.wrapperClass,
    ];
    return classes.filter(Boolean).join(' ');
  }

  get computedInputClasses(): string {
    const baseClasses = [
      this.fullWidth && !this.isCheckbox ? 'w-full' : '',
      'rounded-xl',
      'border',
      'shadow-sm',
      'transition duration-200',
      'placeholder:text-gray-400',
      'focus:outline-none',
    ];

    if (this.isCheckbox) {
      baseClasses.push(
        'h-4',
        'w-4',
        'rounded-sm',
        'border-gray-300',
        'text-[#215c98]',
        'focus:ring-[#4e92d9]'
      );
    } else {
      baseClasses.push('text-gray-900', 'bg-white');

      switch (this.variant) {
        case 'outline':
          baseClasses.push('border-gray-300', 'focus:border-[#4e92d9]');
          break;
        case 'ghost':
          baseClasses.push(
            'border-transparent',
            'bg-transparent',
            'focus:border-[#4e92d9]'
          );
          break;
        default:
          baseClasses.push('border-[#c2d8f0]', 'focus:border-[#4e92d9]');
      }

      switch (this.size) {
        case 'sm':
          baseClasses.push('px-3', 'py-2', 'text-sm');
          break;
        case 'lg':
          baseClasses.push('px-5', 'py-4', 'text-base');
          break;
        default:
          baseClasses.push('px-4', 'py-3', 'text-base');
      }
    }

    if (this.errorMessage && !this.isCheckbox) {
      baseClasses.push('border-red-400', 'focus:border-red-500');
    }

    if (this.disabled) {
      baseClasses.push('bg-gray-100', 'text-gray-500', 'cursor-not-allowed');
    }

    if (this.inputClass) {
      baseClasses.push(this.inputClass);
    }

    return baseClasses.filter(Boolean).join(' ');
  }

  get labelClasses(): string {
    const classes = [
      'block',
      'text-gray-700',
      'text-sm',
      'font-medium',
      this.labelClass,
    ];
    return classes.filter(Boolean).join(' ');
  }

  handleInput(event: Event): void {
    const target = event.target as HTMLInputElement;
    let newValue: string | number | boolean | null;

    if (this.isCheckbox) {
      newValue = target.checked;
    } else if (this.type === 'number') {
      newValue = target.value === '' ? null : target.valueAsNumber;
    } else {
      newValue = target.value;
    }

    this.value = newValue;
    this.valueChange.emit(newValue);
    this.onChange(newValue);
    this.inputChange.emit(event);
  }

  handleBlur(event: FocusEvent): void {
    this.onTouched();
    this.inputBlur.emit(event);
  }

  handleFocus(event: FocusEvent): void {
    this.inputFocus.emit(event);
  }

  writeValue(value: string | number | boolean | null): void {
    this.value = value;
  }

  registerOnChange(
    fn: (value: string | number | boolean | null) => void
  ): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }
}

import {
  Component,
  EventEmitter,
  forwardRef,
  Input,
  Output,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { LucideCircleAlert } from '@lucide/angular';

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

/** Reglas de sanitización. */
export type SanitizeRule =
  'none' | 'numeric' | 'alpha' | 'alphaSpaces' | 'alphanumeric' | 'custom';

const DEFAULT_INVALID_CHARS_MESSAGES: Record<SanitizeRule, string> = {
  none: '',
  numeric: 'Solo se permiten números',
  alpha: 'Solo se permiten letras',
  alphaSpaces: 'Solo se permiten letras y espacios',
  alphanumeric: 'Solo se permiten letras y números',
  custom: 'Hay caracteres no permitidos en este campo',
};

@Component({
  selector: 'app-input',
  standalone: true,
  imports: [LucideCircleAlert],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => InputComponent),
      multi: true,
    },
  ],
  host: {
    '[class]': 'hostClasses',
  },
  templateUrl: './input.component.html',
})
export class InputComponent implements ControlValueAccessor {
  @Input() id = `app-input-${Math.random().toString(36).slice(2, 10)}`;
  @Input() type: InputType = 'text';
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
  @Input() wrapperClass = '';
  @Input() invalidCharsMessage?: string;

  // ── Reglas de sanitización ─────────────────────────────
  @Input() sanitize: SanitizeRule = 'none';
  @Input() customPattern?: RegExp;
  @Input() maxLength?: number;
  @Input() minLength?: number;
  @Input() titleCase = false;
  @Input() trimLeadingSpaces = false;
  @Input() collapseSpaces = false;
  @Input() stripAccents = false;
  @Input() validateEmailFormat?: boolean;

  @Input() maxLengthMessage?: string;
  @Input() minLengthMessage?: string;
  @Input() invalidEmailMessage =
    'Ingrese un correo con un formato válido, ej: nombre@dominio.com';

  @Output() valueChange = new EventEmitter<string | number | boolean | null>();
  @Output() inputChange = new EventEmitter<Event>();
  @Output() inputBlur = new EventEmitter<FocusEvent>();
  @Output() inputFocus = new EventEmitter<FocusEvent>();
  @Output() internalErrorChange = new EventEmitter<string>();

  get hostClasses(): string {
    return this.fullWidth ? 'block w-full flex-1' : '';
  }

  value: string | number | boolean | null = '';
  internalMessage = '';

  // eslint-disable-next-line @typescript-eslint/no-unused-vars, @typescript-eslint/no-empty-function
  private onChange = (_: string | number | boolean | null) => {};
  // eslint-disable-next-line @typescript-eslint/no-empty-function
  private onTouched = () => {};
  private flashTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly EMAIL_REGEX =
    /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;

  get isCheckbox(): boolean {
    return this.type === 'checkbox';
  }

  get resolvedErrorMessage(): string | undefined {
    return this.errorMessage || this.internalMessage || undefined;
  }

  get computedWrapperClasses(): string {
    return ['space-y-2', this.fullWidth ? 'w-full' : '', this.wrapperClass]
      .filter(Boolean)
      .join(' ');
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

    if (this.resolvedErrorMessage && !this.isCheckbox) {
      baseClasses.push('border-red-400', 'focus:border-red-500');
    }
    if (this.disabled) {
      baseClasses.push('bg-gray-100', 'text-gray-500', 'cursor-not-allowed');
    }
    if (this.inputClass) baseClasses.push(this.inputClass);

    return baseClasses.filter(Boolean).join(' ');
  }

  handleInput(event: Event): void {
    const target = event.target as HTMLInputElement;

    if (this.isCheckbox) {
      this.value = target.checked;
      this.valueChange.emit(this.value);
      this.onChange(this.value);
      this.inputChange.emit(event);
      return;
    }

    if (this.type === 'number') {
      const newValue = target.value === '' ? null : target.valueAsNumber;
      this.value = newValue;
      this.valueChange.emit(newValue);
      this.onChange(newValue);
      this.inputChange.emit(event);
      return;
    }

    const sanitized = this.applySanitization(target.value);
    target.value = sanitized;
    this.value = sanitized;
    this.valueChange.emit(sanitized);
    this.onChange(sanitized);
    this.inputChange.emit(event);
  }

  handleBlur(event: FocusEvent): void {
    this.onTouched();

    const shouldValidateEmail =
      this.validateEmailFormat ?? this.type === 'email';
    if (
      shouldValidateEmail &&
      typeof this.value === 'string' &&
      this.value.trim()
    ) {
      if (!this.EMAIL_REGEX.test(this.value.trim())) {
        this.setInternalMessage(this.invalidEmailMessage);
        this.inputBlur.emit(event);
        return;
      }
    }

    if (
      this.minLength &&
      typeof this.value === 'string' &&
      this.value.trim().length > 0 &&
      this.value.trim().length < this.minLength
    ) {
      this.setInternalMessage(
        this.minLengthMessage ??
          `Debe ingresar al menos ${this.minLength} caracteres`
      );
      this.inputBlur.emit(event);
      return;
    }

    this.setInternalMessage('');
    this.inputBlur.emit(event);
  }

  handleFocus(event: FocusEvent): void {
    this.inputFocus.emit(event);
  }

  private applySanitization(raw: string): string {
    let value = raw;

    if (this.stripAccents) {
      value = value.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
    }

    const beforeFilter = value;
    value = this.filterByPattern(value);
    const hadInvalidChars = value.length !== beforeFilter.length;

    if (this.trimLeadingSpaces) value = value.replace(/^\s+/, '');
    if (this.collapseSpaces) value = value.replace(/\s{2,}/g, ' ');
    if (this.titleCase) {
      value = value.replace(
        /(\S+)/g,
        (w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()
      );
    }

    if (this.maxLength && value.length > this.maxLength) {
      value = value.slice(0, this.maxLength);
      this.flashMessage(
        this.maxLengthMessage ??
          `Solo se permiten máximo ${this.maxLength} caracteres`
      );
    } else if (hadInvalidChars && this.sanitize !== 'none') {
      this.flashMessage(
        this.invalidCharsMessage ??
          DEFAULT_INVALID_CHARS_MESSAGES[this.sanitize]
      );
    } else {
      this.setInternalMessage('');
    }

    return value;
  }

  private filterByPattern(value: string): string {
    switch (this.sanitize) {
      case 'numeric':
        return value.replace(/\D/g, '');
      case 'alpha':
        return value.replace(/[^a-zA-ZñÑ]/g, '');
      case 'alphaSpaces':
        return value.replace(/[^a-zA-ZñÑ\s]/g, '');
      case 'alphanumeric':
        return value.replace(/[^a-zA-Z0-9]/g, '');
      case 'custom':
        return this.customPattern
          ? value.replace(this.customPattern, '')
          : value;
      default:
        return value;
    }
  }

  /** Muestra un mensaje temporal (3s) para límites alcanzados. */
  private flashMessage(text: string): void {
    this.setInternalMessage(text);
    if (this.flashTimer) clearTimeout(this.flashTimer);
    this.flashTimer = setTimeout(() => this.setInternalMessage(''), 3000);
  }

  private setInternalMessage(text: string): void {
    this.internalMessage = text;
    this.internalErrorChange.emit(text);
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

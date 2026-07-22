# AppButton Component

Componente reutilizable para botones que soporta múltiples variantes y estilos.

## Características

- **Variantes**: primary, secondary, danger, ghost, icon, card, chip, link
- **Tamaños**: sm, md (default), lg
- **Soporte para estados**: disabled, loading, active
- **Integración con Router**: soporta `routerLink` para navegación
- **Enlaces externos**: soporta `href` para enlaces externos
- **Flexible**: acepta contenido personalizado con `<ng-content>`

## Uso

### Importar el componente

```typescript
import { ButtonComponent } from '@app/design-system/components/button';

// O en tu componente:
@Component({
  imports: [ButtonComponent],
  ...
})
```

### Variantes

#### Primary (Por defecto)

```html
<app-button (click)="action()"> Click me </app-button>
```

#### Secondary

```html
<app-button variant="secondary"> Secondary Action </app-button>
```

#### Danger

```html
<app-button variant="danger"> Delete </app-button>
```

#### Ghost

```html
<app-button variant="ghost"> Ghost Button </app-button>
```

#### Icon (Solo icono)

```html
<app-button variant="icon">
  <lucide-angular [img]="X" [size]="20"></lucide-angular>
</app-button>
```

#### Link (Estilo texto)

```html
<app-button variant="link"> Click here </app-button>
```

#### Card (Tarjeta seleccionable)

```html
<app-button variant="card" [active]="isSelected()">
  <div class="flex flex-col items-center gap-2">
    <span class="text-2xl">📊</span>
    <span class="font-bold">Option Name</span>
  </div>
</app-button>
```

#### Chip (Píldora pequeña)

```html
<app-button variant="chip" [active]="selectedTime() === slot">
  {{ slot }}
</app-button>
```

### Propiedades

| Propiedad    | Tipo                            | Default   | Descripción                      |
| ------------ | ------------------------------- | --------- | -------------------------------- |
| `variant`    | ButtonVariant                   | 'primary' | Estilo del botón                 |
| `size`       | ButtonSize                      | 'md'      | Tamaño (sm, md, lg)              |
| `fullWidth`  | boolean                         | false     | Ocupar 100% del ancho            |
| `disabled`   | boolean                         | false     | Desactivar botón                 |
| `active`     | boolean                         | false     | Marcar como activo (uso en chip) |
| `loading`    | boolean                         | false     | Mostrar estado de carga          |
| `routerLink` | string \| string[]              | null      | Ruta para navegación             |
| `href`       | string                          | null      | URL para enlace externo          |
| `type`       | 'button' \| 'submit' \| 'reset' | 'button'  | Tipo de botón                    |
| `class`      | string                          | ''        | Clases CSS adicionales           |

### Eventos

#### click

Emitido cuando se hace click en el botón (no se emite si está disabled o loading)

```html
<app-button (click)="handleClick()"> Click </app-button>
```

## Ejemplos de uso en modales

### Modal con dos botones (Cancelar + Confirmar)

```html
<div class="flex gap-3">
  <app-button variant="secondary" fullWidth (click)="cancel()">
    Cancelar
  </app-button>
  <app-button variant="primary" fullWidth (click)="confirm()">
    Confirmar
  </app-button>
</div>
```

### Modal con botón de peligro

```html
<div class="flex gap-3">
  <app-button variant="secondary" fullWidth> Volver </app-button>
  <app-button variant="danger" fullWidth (click)="delete()">
    Sí, eliminar
  </app-button>
</div>
```

### Selector de formato (Con estado activo)

```html
<div class="grid grid-cols-3 gap-3">
  @for (fmt of formats; track fmt.value) {
  <app-button
    variant="card"
    [active]="selectedFormat() === fmt.value"
    (click)="selectFormat(fmt.value)"
  >
    <div class="flex flex-col items-center gap-2">
      <lucide-angular [img]="fmt.icon" [size]="32"></lucide-angular>
      <span class="font-semibold">{{ fmt.label }}</span>
    </div>
  </app-button>
  }
</div>
```

### Selector de horarios (Chips)

```html
<div class="grid grid-cols-5 gap-2">
  @for (slot of availableSlots; track slot) {
  <app-button
    variant="chip"
    [active]="selectedTime() === slot"
    (click)="selectTime(slot)"
  >
    {{ slot.slice(0, 5) }}
  </app-button>
  }
</div>
```

### Con RouterLink

```html
<app-button [routerLink]="'/dashboard'" fullWidth> Ir al Dashboard </app-button>
```

### Con href (enlace externo)

```html
<app-button
  href="https://example.com"
  target="_blank"
  rel="noopener noreferrer"
>
  Visitar sitio
</app-button>
```

### Estado de carga

```html
<app-button [loading]="isSubmitting()" (click)="submit()">
  @if (isSubmitting()) {
  <span
    class="inline-block w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin"
  ></span>
  Guardando... } @else { Guardar }
</app-button>
```

## Clases CSS personalizadas

Puedes añadir clases CSS adicionales usando la propiedad `class`:

```html
<app-button class="text-xl"> Botón más grande </app-button>
```

## Notas

- El componente es **standalone**, así que no necesita módulo.
- Los estados `disabled` y `loading` previenen que se emita el evento `click`.
- El componente genera clases dinámicamente basadas en la variante y tamaño.
- Se recomienda usar con Tailwind CSS para máxima flexibilidad en estilos adicionales.

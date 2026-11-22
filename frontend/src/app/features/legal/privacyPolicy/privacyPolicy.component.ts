import { ChangeDetectionStrategy, Component } from '@angular/core';
import {
  PRIVACY_POLICY_INTRO,
  PRIVACY_POLICY_SECTIONS,
  PRIVACY_POLICY_TITLE,
} from './privacyPolicy.content';

/** Trozo de texto: normal o marcador `[COMPLETAR: ...]` pendiente. */
interface TextSegment {
  readonly text: string;
  readonly isPlaceholder: boolean;
}

/** Detecta los marcadores `[COMPLETAR: ...]` dentro de un texto. */
const PLACEHOLDER_PATTERN = /(\[COMPLETAR:[^\]]*\])/g;

/**
 * Página pública con la Política de tratamiento de datos personales.
 *
 * Solo presenta el contenido definido en `privacyPolicy.content.ts`; no tiene
 * lógica propia. Los marcadores `[COMPLETAR: ...]` que aún queden en el
 * contenido se resaltan para que se noten.
 */
@Component({
  selector: 'app-privacy-policy',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './privacyPolicy.component.html',
})
export class PrivacyPolicyComponent {
  readonly title = PRIVACY_POLICY_TITLE;
  readonly intro = PRIVACY_POLICY_INTRO;
  readonly sections = PRIVACY_POLICY_SECTIONS;

  /** Divide un texto en segmentos para resaltar los marcadores pendientes. */
  segments(text: string): TextSegment[] {
    return text
      .split(PLACEHOLDER_PATTERN)
      .filter((part) => part.length > 0)
      .map((part) => ({
        text: part,
        isPlaceholder: part.startsWith('[COMPLETAR:'),
      }));
  }
}

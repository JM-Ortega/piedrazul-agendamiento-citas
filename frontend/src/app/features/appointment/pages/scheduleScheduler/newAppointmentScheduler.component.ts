import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AppointmentBookingComponent } from '../../components/schedulingOrchestrator/appointmentBooking.component';
import {
  RESCHEDULE_QUERY_PARAM,
  RescheduleNavigationState,
} from '../../models/types/rescheduleNavigation.type';

const DEFAULT_RETURN_ROUTE = '/agendador';
const RESCHEDULE_FALLBACK_ROUTE = '/agendador/historial-citas';

@Component({
  selector: 'app-new-appointment-scheduler',
  standalone: true,
  imports: [AppointmentBookingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './newAppointmentScheduler.component.html',
})
export class NewAppointmentSchedulerComponent implements OnInit {
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  /** Id de la cita a reagendar, si la URL trae ?reagendar=<id>. */
  private readonly rescheduleId = this.route.snapshot.queryParamMap.get(
    RESCHEDULE_QUERY_PARAM
  );

  /**
   * Datos de la cita original. Se leen en el constructor porque
   * `getCurrentNavigation()` solo existe mientras dura la navegación;
   * `history.state` cubre el caso de volver con atrás/adelante.
   */
  readonly reschedule: RescheduleNavigationState | null =
    this.readRescheduleState();

  /**
   * La URL pide reagendar pero no llegaron los datos (p. ej. se recargó la
   * página o se abrió el enlace directo). En ese caso no se monta el flujo
   * para no terminar creando una cita nueva por error.
   */
  readonly rescheduleMissing = !!this.rescheduleId && !this.reschedule;

  ngOnInit(): void {
    if (this.rescheduleMissing) {
      this.router.navigate([RESCHEDULE_FALLBACK_ROUTE], { replaceUrl: true });
    }
  }

  goToScheduler(): void {
    this.router.navigate([this.reschedule?.returnTo ?? DEFAULT_RETURN_ROUTE]);
  }

  private readRescheduleState(): RescheduleNavigationState | null {
    if (!this.rescheduleId) return null;
    const navState =
      this.router.getCurrentNavigation()?.extras.state ?? history.state;
    const data = (navState as { reschedule?: RescheduleNavigationState })
      ?.reschedule;
    return data?.appointmentId === this.rescheduleId ? data : null;
  }
}

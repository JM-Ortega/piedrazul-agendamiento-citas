import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DoctorAllAppointmentsComponent } from './doctor-all-appointments.component';

describe('DoctorAllAppointmentsComponent', () => {
  let component: DoctorAllAppointmentsComponent;
  let fixture: ComponentFixture<DoctorAllAppointmentsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DoctorAllAppointmentsComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(DoctorAllAppointmentsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});

import { Doctor } from '../interfaces/doctor.model';

export type dtoDoctor = Pick<Doctor, 'id' | 'name' | 'specialty' | 'email'>;

// CreateUserRequest.dto.ts
import { CreateUserDoctorDto } from './CreateUserDoctorDto';

export interface CreateUserRequestDto {
  user: {
    identification: string;
    identificationType: string;
    phone: string;
    firstName: string;
    lastName: string;
    email: string;
    password: string;
  };
  doctor: CreateUserDoctorDto | null;
  patient: null;
  roles: string[];
}

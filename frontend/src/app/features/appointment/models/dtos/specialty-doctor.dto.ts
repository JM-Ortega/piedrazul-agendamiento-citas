export interface SpecialtyDoctor {
  specialty: string;
  id: string;
  name: string;
  laborStart: string | null;
  laborEnd: string | null;
  workdays: number[];
}

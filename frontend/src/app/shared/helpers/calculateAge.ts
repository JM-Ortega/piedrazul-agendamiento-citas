/**
 * Calcula la edad en años a partir de una fecha Date o string
 */
export function calculateAge(birthDate: string | Date): number {
  const birth =
    typeof birthDate === 'string'
      ? new Date(birthDate + 'T12:00:00')
      : birthDate;
  const today = new Date();
  let age = today.getFullYear() - birth.getFullYear();
  const monthDifference = today.getMonth() - birth.getMonth();

  if (
    monthDifference < 0 ||
    (monthDifference === 0 && today.getDate() < birth.getDate())
  ) {
    age--;
  }
  return age;
}

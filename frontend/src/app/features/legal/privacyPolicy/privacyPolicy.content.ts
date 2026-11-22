/**
 * Contenido de la Política de tratamiento de datos personales.
 *
 * Este archivo es el único que hay que editar para completar la política:
 * reemplaza cada marcador `[COMPLETAR: ...]` por el texto real. Los
 * marcadores se resaltan en la página para que no pasen desapercibidos; al
 * quitarlos, el texto se muestra normal. No hace falta tocar el componente.
 */

/** Subapartado de una sección con su propia lista (con viñetas o numerada). */
export interface PrivacyPolicySubsection {
  readonly title: string;
  /** Si es `true` la lista se numera; si no, usa viñetas. */
  readonly ordered?: boolean;
  readonly items: readonly string[];
}

export interface PrivacyPolicySection {
  /** Número de la sección (se muestra junto al título). */
  readonly number: number;
  readonly title: string;
  /** Párrafos de la sección, en orden. */
  readonly paragraphs: readonly string[];
  /** Lista opcional que se muestra después de los párrafos. */
  readonly items?: readonly string[];
  /** Subapartados opcionales con lista propia, después de los párrafos y la lista. */
  readonly subsections?: readonly PrivacyPolicySubsection[];
}

export const PRIVACY_POLICY_TITLE =
  'Política de tratamiento de datos personales';

export const PRIVACY_POLICY_INTRO =
  'La Red de Servicios de Piedrazul, en cumplimiento de la Ley 1581 de 2012 y del Decreto 1377 de 2013 (compilado en el Decreto 1074 de 2015), adopta la presente Política de Tratamiento de Datos Personales. Esta política aplica a los datos personales que se recolectan a través de la plataforma de agendamiento de citas Piedrazul y tiene como objetivo informar a los titulares cómo se recolectan, usan, almacenan y protegen sus datos, así como los derechos que les asisten y la forma de ejercerlos.';

export const PRIVACY_POLICY_SECTIONS: readonly PrivacyPolicySection[] = [
  {
    number: 1,
    title: 'Responsable del tratamiento',
    paragraphs: [
      'Responsable: Clara Inés Córdoba, quien presta sus servicios bajo el nombre Red de Servicios de Piedrazul.',
      'Identificación: C.C. 34548670',
      'Domicilio: Popayán, Cauca',
      'Dirección: Kilómetro 5 vía al Huila',
      'Teléfono: 3126069041',
      'Correo electrónico: cicordob@unicauca.edu.co',
    ],
  },
  {
    number: 2,
    title: 'Datos que se recolectan y finalidad del tratamiento',
    paragraphs: [],
    subsections: [
      {
        title: 'Datos que se recolectan',
        items: [
          'Identificación: tipo y número de documento, nombres, apellidos, fecha de nacimiento y género.',
          'Contacto: teléfono celular, correo electrónico y, cuando aplique, teléfono de un acudiente.',
          'Cuenta de usuario: credenciales de acceso a la plataforma.',
          'Citas: fecha, hora, especialidad, profesional que atiende y estado de la cita (agendada, reprogramada, cancelada, atendida o no asistió).',
          'Salud: controles médicos y registro de las atenciones realizadas. Son datos sensibles y se tratan según la sección 3.',
          'Seguridad: códigos de verificación de un solo uso y registro de las acciones hechas en la plataforma.',
        ],
      },
      {
        title: 'Finalidades',
        ordered: true,
        items: [
          'Identificar al paciente, crear su cuenta y permitirle el acceso a la plataforma.',
          'Agendar, reprogramar, cancelar y gestionar sus citas.',
          'Registrar y conservar los controles médicos y las atenciones, conforme a la normativa de salud vigente.',
          'Generar reportes internos de agenda para los profesionales que atienden.',
          'Elaborar estadísticas agregadas, que no identifican a ningún paciente, para la gestión del servicio.',
          'Garantizar la seguridad de la plataforma y la trazabilidad de las operaciones.',
          'Atender peticiones, consultas y reclamos.',
        ],
      },
    ],
  },
  {
    number: 3,
    title: 'Datos sensibles',
    paragraphs: [
      'Se consideran datos sensibles los relacionados con la salud del titular. A través de la plataforma se tratan los siguientes: los controles médicos y el registro de las atenciones realizadas por los profesionales, y la especialidad o terapia recibida. Estos datos se usan únicamente para prestar los servicios de salud solicitados, dar continuidad a la atención y registrar y conservar los controles médicos conforme a la normativa de salud vigente. Solo tienen acceso a ellos los profesionales y el personal autorizado.',
      'Por tratarse de datos sensibles, el titular no está obligado a autorizar su tratamiento ni a responder preguntas que versen sobre ellos.',
    ],
  },
  {
    number: 4,
    title: 'Derechos del titular',
    paragraphs: [
      'De acuerdo con el artículo 8 de la Ley 1581 de 2012, el titular de los datos personales tiene los siguientes derechos, que puede ejercer directamente o por medio de su representante legal o apoderado:',
    ],
    items: [
      'Conocer, actualizar y rectificar sus datos personales.',
      'Solicitar la supresión de sus datos.',
      'Solicitar prueba de la autorización otorgada.',
      'Ser informado sobre el uso que se ha dado a sus datos.',
      'Presentar quejas ante la Superintendencia de Industria y Comercio.',
      'Revocar la autorización otorgada.',
    ],
  },
  {
    number: 5,
    title: 'Responsable de atender peticiones, consultas y reclamos',
    paragraphs: [
      'Las peticiones, consultas y reclamos relacionados con el tratamiento de datos personales son atendidos directamente por la responsable del tratamiento, Clara Inés Córdoba, a través del teléfono 3126069041, del correo electrónico indicado en la sección 1 o de forma presencial en Kilómetro 5 vía al Huila, Popayán.',
    ],
  },
  {
    number: 6,
    title: 'Procedimiento para ejercer los derechos y revocar la autorización',
    paragraphs: [
      'El titular, su representante legal o su apoderado puede presentar consultas y reclamos por los canales indicados en las secciones 1 y 5 de esta política. La solicitud debe incluir el nombre y documento de identificación del titular, la descripción de lo que se solicita, la dirección o correo electrónico de respuesta y los documentos que se quieran hacer valer.',
      'Consultas: se responderán en un plazo máximo de diez (10) días hábiles, contados a partir de la fecha de recibo. Si no es posible atenderla en ese término, se informará al interesado el motivo y la nueva fecha de respuesta, que no podrá superar cinco (5) días hábiles adicionales.',
      'Reclamos: cuando el titular considere que sus datos deben ser corregidos, actualizados o suprimidos, o advierta un incumplimiento de esta política, podrá presentar un reclamo. Si el reclamo está incompleto, se pedirá al interesado completarlo dentro de los cinco (5) días hábiles siguientes. Si pasan dos (2) meses sin que lo complete, se entenderá que desistió. El reclamo se atenderá en un plazo máximo de quince (15) días hábiles, contados desde el día siguiente a su recibo. Si no es posible atenderlo en ese término, se informará al interesado el motivo y la nueva fecha, que no podrá superar ocho (8) días hábiles adicionales.',
      'Revocatoria y supresión: el titular puede solicitar en cualquier momento la revocatoria de la autorización o la supresión de sus datos por los mismos canales. Esta solicitud no procede cuando exista un deber legal o contractual de conservar los datos, como ocurre con los registros de las atenciones en salud.',
      'El titular solo podrá presentar quejas ante la Superintendencia de Industria y Comercio después de haber agotado este trámite ante la Red de Servicios de Piedrazul.',
    ],
  },
  {
    number: 7,
    title: 'Vigencia',
    paragraphs: [
      'La presente política entra en vigencia a partir del 21 de noviembre de 2026 y permanecerá vigente mientras la Red de Servicios de Piedrazul realice el tratamiento de datos personales. Cualquier cambio sustancial será informado a los titulares a través de la plataforma antes de su aplicación.',
      'Las bases de datos permanecerán vigentes durante el tiempo que sea necesario para cumplir las finalidades descritas en esta política y, en todo caso, durante el término que exija la normativa aplicable, en particular la relacionada con la conservación de los registros de atención en salud. Cumplido ese término, los datos serán suprimidos, salvo que exista un deber legal o contractual de conservarlos.',
    ],
  },
];

# ADR-004: Adopción de Angular como framework para el frontend

* **Estado:** Aceptado
* **Fecha:** 2026-08-05
* **Impacto:** Frontend

## Contexto

La plataforma Piedra Azul requiere una aplicación web que permita implementar interfaces interactivas para los diferentes roles del sistema, integrándose con los servicios expuestos por el backend y ofreciendo una base sólida para la evolución del proyecto.

Era necesario seleccionar un framework para el desarrollo del frontend que facilitara el mantenimiento del código, promoviera una arquitectura consistente y permitiera incorporar nuevas funcionalidades sin incrementar innecesariamente la complejidad del sistema.

## Opciones consideradas

### Opción 1: React

Desarrollar el frontend utilizando React como biblioteca principal para la construcción de la interfaz de usuario.

#### Ventajas

* Amplio ecosistema y comunidad.
* Gran flexibilidad para construir aplicaciones de diferentes tamaños.
* Buen rendimiento.

#### Desventajas

* Requiere integrar bibliotecas adicionales para funcionalidades como enrutamiento, manejo de formularios y gestión de estado.
* La arquitectura depende en mayor medida de las decisiones del equipo de desarrollo.

---

### Opción 2: Vue

Desarrollar el frontend utilizando Vue como framework progresivo.

#### Ventajas

* Curva de aprendizaje reducida.
* Desarrollo rápido de interfaces.
* Buena documentación oficial.

#### Desventajas

* Menor alineación con la experiencia y tecnologías adoptadas en el proyecto.
* Menor estandarización dentro del equipo de desarrollo.

---

### Opción 3: Angular

Desarrollar el frontend como una aplicación SPA utilizando Angular.

#### Ventajas

* Framework completo para el desarrollo de aplicaciones empresariales.
* Arquitectura basada en componentes y fuerte integración con TypeScript.
* Incluye de forma nativa funcionalidades como enrutamiento, formularios e inyección de dependencias.
* Facilita el mantenimiento de una arquitectura consistente conforme el proyecto evoluciona.

#### Desventajas

* Mayor curva de aprendizaje.
* Framework con mayor complejidad inicial que otras alternativas.

## Decisión

Se decidió adoptar **Angular** como framework para el desarrollo del frontend de Piedra Azul.

Angular proporciona un ecosistema integrado para construir aplicaciones SPA y ofrece una arquitectura consistente que facilita el desarrollo, mantenimiento y evolución del sistema sin depender de múltiples herramientas externas.

Adicionalmente, el proyecto ya contaba con una base de código desarrollada sobre Angular. Migrar a otro framework implicaría reestructurar la arquitectura existente, adaptar los componentes implementados y asumir un costo considerable de desarrollo y pruebas, sin aportar beneficios que justificaran dicho esfuerzo para las necesidades actuales del proyecto.

Por estas razones, se decidió mantener Angular como tecnología base del frontend y continuar su evolución sobre la arquitectura ya establecida.

## Consecuencias

### Beneficios

* Se aprovecha la inversión realizada en la implementación actual del frontend.
* Se mantiene una arquitectura consistente para toda la aplicación.
* El ecosistema de Angular simplifica el desarrollo y mantenimiento del proyecto.
* Se reduce el esfuerzo necesario para incorporar nuevas funcionalidades sobre la base de código existente.
* Se evita el costo y riesgo asociado a una migración tecnológica.

### Costos y limitaciones

* El equipo debe mantener conocimientos sobre el ecosistema de Angular.
* Angular presenta una curva de aprendizaje mayor que otras alternativas.
* Una futura migración hacia otro framework requerirá un esfuerzo considerable debido al tamaño de la base de código y a la dependencia con el ecosistema de Angular.

## Referencias

No aplica.

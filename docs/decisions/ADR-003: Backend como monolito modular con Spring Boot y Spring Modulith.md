# ADR-003: Implementar el backend como un monolito modular utilizando Spring Boot y Spring Modulith

* **Estado:** Aceptado
* **Fecha:** 2026-07-28
* **Impacto:** Backend

## Contexto

El sistema de agendamiento médico debía permitir incorporar nuevas funcionalidades de manera progresiva sin introducir una complejidad operativa innecesaria. Aunque una arquitectura de microservicios ofrecía una alta separación entre componentes, sus costos de desarrollo, despliegue y mantenimiento no eran proporcionales al tamaño y necesidades actuales del proyecto.

Al mismo tiempo, era importante evitar que el backend evolucionara hacia un monolito tradicional con fuerte acoplamiento entre sus componentes, ya que esto dificultaría el mantenimiento, las pruebas y la evolución del sistema.

Por ello, se buscó una arquitectura que permitiera mantener una única aplicación desplegable, pero con límites claros entre los distintos dominios del negocio.

## Opciones consideradas

### Opción 1: Monolito tradicional

Desarrollar toda la aplicación en un único proyecto sin mecanismos que hicieran cumplir la separación entre módulos.

**Ventajas**

* Implementación sencilla.
* Menor curva de aprendizaje.
* Menor cantidad de herramientas adicionales.
* Menos costos.

**Desventajas**

* Mayor riesgo de acoplamiento entre componentes.
* Dependencias entre módulos difíciles de controlar.
* La arquitectura depende principalmente de la disciplina del equipo.
* Mayor probabilidad de que el proyecto evolucione hacia un monolito difícil de mantener.

---

### Opción 2: Arquitectura de microservicios

Separar cada dominio del negocio en servicios independientes.

**Ventajas**

* Aislamiento completo entre dominios.
* Escalabilidad independiente por servicio.
* Despliegue independiente de cada componente.

**Desventajas**

* Mayor complejidad de desarrollo.
* Infraestructura considerablemente más compleja.
* Mayor esfuerzo para comunicación entre servicios, observabilidad y despliegue.
* Sobredimensionada para el alcance actual del proyecto.
* Costoso de implementar.

---

### Opción 3: Monolito modular con Spring Boot y Spring Modulith

Construir una única aplicación utilizando Spring Boot como framework principal y Spring Modulith para organizar el sistema en módulos bien definidos y verificar el cumplimiento de sus límites arquitectónicos.

**Ventajas**

* Desarrollo rápido gracias a las facilidades proporcionadas por Spring Boot, como autoconfiguración, servidor embebido e integración sencilla con el ecosistema Spring.
* Gestión simplificada de dependencias mediante Maven y Spring Boot.
* Integración sencilla con herramientas utilizadas en el proyecto, como Lombok, Spring Data JPA, Spring Security y Spring Validation.
* Spring Modulith permite definir módulos con responsabilidades claras.
* Posibilidad de verificar mediante pruebas que los módulos respetan las reglas arquitectónicas establecidas.
* Reduce el riesgo de introducir dependencias indebidas entre dominios del negocio.
* Mantiene una única aplicación desplegable sin perder organización interna.

**Desventajas**

* Requiere comprender los principios de diseño modular.
* La correcta modularización sigue dependiendo de un buen diseño del dominio.
* Añade una herramienta adicional que el equipo tuvo que aprender.

## Decisión

Se decidió implementar el backend como un **monolito modular**, utilizando **Spring Boot** como framework principal y **Spring Modulith** para estructurar y validar la arquitectura del sistema.

Spring Boot proporciona un ecosistema maduro que simplifica el desarrollo mediante autoconfiguración, gestión de dependencias e integración con múltiples componentes utilizados por el proyecto, reduciendo el código de configuración y facilitando el mantenimiento.

Por su parte, Spring Modulith permite definir fronteras claras entre los módulos del dominio y verificar automáticamente que dichas fronteras se respeten. Esto ayuda a prevenir dependencias innecesarias entre módulos y favorece una arquitectura más mantenible conforme el sistema crece.

Esta combinación permite obtener los beneficios de una arquitectura modular sin asumir la complejidad operativa que implica una solución basada en microservicios.

## Consecuencias

### Beneficios

* El backend permanece organizado en módulos con responsabilidades bien definidas.
* Se reduce el riesgo de acoplamiento excesivo entre dominios.
* Es posible validar mediante pruebas que la arquitectura modular se mantiene.
* Se facilita el mantenimiento y la incorporación de nuevas funcionalidades.
* Se aprovecha el ecosistema de Spring para acelerar el desarrollo e integrar fácilmente las tecnologías utilizadas por el proyecto.
* Se mantiene un único proceso de despliegue, simplificando la operación del sistema.

### Costos y limitaciones

* El equipo tuvo que aprender el funcionamiento de Spring Modulith y adaptar la organización del proyecto a sus principios.
* La modularidad no reemplaza un buen diseño del dominio; continúa siendo responsabilidad del equipo mantener los límites entre módulos.
* Todo el sistema continúa desplegándose como una única aplicación, por lo que no es posible escalar módulos de forma independiente como ocurre en una arquitectura de microservicios.

## Referencias

No aplica.

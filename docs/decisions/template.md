# Architecture Decision Records (ADR)

Este repositorio utiliza **Architecture Decision Records (ADR)** para documentar las decisiones de arquitectura más importantes del proyecto.

Un ADR es un documento breve que explica **qué decisión se tomó, por qué se tomó y cuáles son sus consecuencias**. Su objetivo es conservar el contexto detrás de las decisiones para que cualquier miembro del equipo pueda entenderlas en el futuro.

## ¿Cuándo crear un ADR?

Se debe crear un ADR cuando una decisión tenga un impacto significativo en la arquitectura o en el funcionamiento del sistema, por ejemplo:

* Arquitectura general del sistema.
* Cambios importantes en la base de datos.
* Autenticación y autorización.
* Integración entre módulos o servicios.
* Infraestructura.
* Estrategias de despliegue.
* Comunicación síncrona o asíncrona.
* Propiedad y flujo de datos.
* Incorporación o reemplazo de dependencias importantes.
* Decisiones relacionadas con seguridad.
* Patrones de diseño o arquitectura que afecten al proyecto.

## ¿Cuándo NO crear un ADR?

No es necesario crear un ADR para cambios cotidianos del desarrollo, como:

* Agregar un endpoint.
* Corregir un bug.
* Cambiar nombres de clases, métodos o variables.
* Añadir una validación.
* Refactorizar una clase interna.
* Actualizar una dependencia menor.
* Cambios de formato o estilo de código.

## Regla práctica

Antes de crear un ADR, hazte la siguiente pregunta:

> **¿Dentro de seis meses alguien podría preguntarse por qué se tomó esta decisión o considerar reemplazarla?**

Si la respuesta es **sí**, probablemente el cambio merece un ADR.

---

# Plantilla para crear un ADR

Cada ADR debe almacenarse en este directorio siguiendo el formato `ADR-XXXX`, donde `XXXX` corresponde a un identificador secuencial.

```markdown
# ADR-XXXX: Título de la decisión

- Estado: Propuesto | Aceptado | Reemplazado | Obsoleto
- Fecha: YYYY-MM-DD
- Impacto: Backend | Fontend | Despliegue

## Contexto

Describe el problema o la necesidad que motivó esta decisión.

## Opciones consideradas

Enumera las alternativas evaluadas y resume sus ventajas y desventajas.

## Decisión

Explica claramente la solución elegida y las razones por las que fue seleccionada.

## Consecuencias

Describe los beneficios, costos, limitaciones, riesgos o compromisos que implica esta decisión.

## Referencias

Incluye enlaces a Issues, Pull Requests, documentación u otros ADR relacionados.
```

## Recomendaciones

* Mantén cada ADR corto y fácil de leer.
* Documenta la decisión en el momento en que se toma, no meses después.
* Si una decisión cambia con el tiempo, crea un nuevo ADR en lugar de modificar el anterior. El nuevo ADR puede indicar cuál reemplaza.
* Procura que el título describa claramente la decisión y que continue la numeración anterior, por ejemplo:

  * `ADR-0005: Adoptar Keycloak como proveedor de identidad`
  * `ADR-0006: Utilizar PostgreSQL como base de datos principal`
  * `ADR-0007: Comunicación entre módulos mediante puertos`

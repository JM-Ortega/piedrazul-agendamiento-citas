# Auditar la configuración de confianza de GitHub

## Propósito

Verificar desde la máquina de un mantenedor que la configuración externa de GitHub que
protege las credenciales de producción coincide con el contrato aprobado. La auditoría es de
solo lectura: no modifica GitHub y nunca lee valores de secretos.

El contrato que verifica (alcances autorizados por secreto, conjunto exacto de revisores,
environments sensibles) está definido al inicio de `.github/scripts/audit-github-trust.sh`.

La tabla de alcances dice **dónde puede vivir** cada secreto de producción conocido; no es un
inventario que deba estar completo. Que un secreto tenga que existir en un environment lo
determina un job del árbol analizado que lo consume. Un secreto nuevo se agrega a la tabla
cuando se decide su alcance.

Un nombre que no está en la tabla (referenciado por un workflow o guardado en un environment)
es `FAIL` hasta que se decida su alcance: el hecho se puede verificar y contradice el
contrato, así que no es `UNKNOWN`. `secrets.GITHUB_TOKEN` es un mecanismo de GitHub, no un
secreto del repositorio, y no cuenta como nombre sin clasificar.

## Cuándo ejecutarla

- Después de cambiar a mano environments, revisores, secretos, rulesets o la política de
  Actions del repositorio.
- Antes de promover a `main` cambios en `.github/workflows/**` (con `--ref` apuntando a la rama).
- Periódicamente, para detectar deriva respecto al contrato.

## Requisitos

- `gh` autenticado con tu cuenta de mantenedor. Para leer environments, secretos y rulesets
  hace falta permiso de **admin** en el repositorio.
- `jq`, `yq` (mikefarah v4) y `git`.
- Un checkout del repositorio con las refs remotas actualizadas.

No requiere tokens adicionales ni credenciales de CI.

## Ejecución

```bash
git fetch origin
.github/scripts/audit-github-trust.sh
```

Opciones:

| Opción | Uso |
|---|---|
| `--ref <ref>` | árbol de workflows a analizar (por defecto `origin/main`) |
| `--reviewers <lista>` | conjunto exacto de revisores autorizados, separado por comas (`User:login` o `Team:slug`; por defecto `User:JM-Ortega`) |
| `--repo <owner/repo>` | repositorio a auditar (por defecto, el del checkout) |

`--ref` analiza solo lo que está en ese árbol git: los cambios sin commit no se incluyen.

## Qué revisa

**Estado vivo en GitHub:**
- `production-hetzner` y `terraform-plan`: existencia y conjunto exacto de revisores
  requeridos; restricción de `production-hetzner` a la rama `main`.
- Cualquier environment que contenga un secreto protegido sin ser alcance autorizado para él,
  o un secreto sin clasificar.
- Secretos de Actions con alcance de repositorio: el objetivo es **cero**, conocidos o no. La
  salida separa los de producción de los sin clasificar.
- Política de Actions: `sha_pinning_required` y permisos por defecto del `GITHUB_TOKEN`.
- Ruleset `Protect main`: activo sobre `main`, reglas de borrado, force-push y PR, y que el
  contexto heredado en rojo `Build and Verify` no sea requerido.

**Árbol de workflows (`--ref`):**
- Acciones externas fijadas por SHA completo (las locales `./…` no cuentan).
- Para cada job que referencia un secreto protegido, de dónde lo obtendría hoy: la copia del
  environment declarado si existe ahí; si no, la copia del repositorio; si no, ninguna. El
  resultado se clasifica:

| Clasificación | Estado |
|---|---|
| vía environment autorizado para ese secreto | `INFO` (correcto) |
| environment autorizado, pero resuelve la copia de repositorio | `FAIL` |
| copia de repositorio sin environment autorizado | `FAIL` |
| workflow histórico (OCI) que aún resuelve una copia viva | `FAIL` |
| secreto presente en un environment no autorizado | `FAIL` |
| environment autorizado sin ninguna fuente: el job lo referencia y no lo obtendría (cargar el secreto si tiene consumidor real, o quitar la referencia) | `FAIL` |
| sin ninguna fuente viva y fuera de alcance autorizado | `INFO` (referencia inerte) |

- Secretos referenciados como `secrets.NOMBRE` sin alcance decidido (`FAIL`), y acceso a
  secretos por expresión (`secrets[...]`, `toJSON(secrets)`), cuyo nombre no se puede
  resolver (`UNKNOWN`).
- Secretos protegidos presentes en `production-hetzner` o `terraform-plan` que ningún job del
  árbol consume (`INFO`): no se exigen ni se prohíben; sirven para detectar secretos muertos o
  transicionales que no conviene arrastrar al environment.
- Environments declarados por jobs que no existen en GitHub (GitHub los crearía sin
  protección al ejecutar el job).
- Que el plan especulativo de Hetzner use el environment `terraform-plan`.
- Ausencia de `pull_request_target`.

Un `environment: ${{ inputs.x }}` de un workflow reutilizable se resuelve con los valores
literales que le pasan sus llamadores en el mismo árbol; si no es posible, queda `UNKNOWN`.

### OCI

OCI está histórico e inactivo. Sus workflows (`terraform-oci-*.yml`) no se mantienen
operativos y `production-oci` no es alcance autorizado para ningún secreto protegido. Mientras
exista una copia de repositorio que puedan resolver, se reportan como ruta viva (`FAIL`); sin
fuente, como referencia inerte (`INFO`). Que dejen de funcionar no es un fallo.

## Interpretar el resultado

| Estado | Significado |
|---|---|
| `PASS` | verificado positivamente |
| `FAIL` | el control falta o contradice el contrato |
| `UNKNOWN` | no se pudo determinar (permisos, error de API, dato no resoluble) |
| `INFO` | informativo; no afecta el código de salida |

Código de salida: `0` todo `PASS`, `1` algún `FAIL`, `2` algún `UNKNOWN` sin `FAIL`.

Un `UNKNOWN` **no** es un `PASS`: significa que el control no se pudo comprobar, no que esté
bien. Cuando un dato del que dependen otros no se puede leer, esos controles también quedan
`UNKNOWN`.

Las listas de la API (secretos, environments, políticas de rama, rulesets) se leen completas,
todas las páginas. Si la API informa un total distinto de lo leído, el dato se marca
incompleto (`HTTP incompleto`) y sus controles quedan `UNKNOWN`.

## Problemas frecuentes

| Síntoma | Causa probable | Acción |
|---|---|---|
| `acceso: admin del mantenedor` en `UNKNOWN` y varios `HTTP 403` | la cuenta de `gh` no es admin | ejecutar con una cuenta admin (`gh auth status`) |
| `HTTP err` aislado | error transitorio de red o API | volver a ejecutar |
| `HTTP incompleto` | la lista paginada cambió durante la lectura o la API devolvió un total inconsistente | volver a ejecutar; si persiste, revisar en la interfaz de GitHub |
| `sin alcance decidido: …` | secreto nuevo o huérfano sin clasificar | decidir su alcance (agregarlo a la tabla del script) o eliminar la referencia o el secreto |
| `workflows@…` en `UNKNOWN` por ref no disponible | ref no presente localmente | `git fetch origin` |
| `parseo YAML` en `UNKNOWN` | workflow con YAML inválido en ese árbol | corregir el workflow |
| `falta jq`/`yq` | herramienta no instalada | instalarla (yq debe ser la de mikefarah) |

Un `FAIL` en un control de confianza (revisores adicionales, secreto en un environment no
autorizado, ruta de repositorio inesperada) se escala al revisor privilegiado del repositorio
antes de cualquier despliegue o aprobación de `terraform-plan`.

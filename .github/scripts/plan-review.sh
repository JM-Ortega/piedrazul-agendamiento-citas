#!/usr/bin/env bash
# Revisión pública y saneada de un plan de Terraform (JSON de `terraform show -json`).
#
# Uso: plan-review.sh <plan.json> <revisión.md> [clases.json]
#
# Escribe en <revisión.md> (Markdown, para el resumen del job) los efectos materiales del
# plan, primero los destructivos: destruir, reemplazar (con el motivo y las rutas que lo
# fuerzan), actualizar, crear, olvidar (sale del estado sin destruirse) e importar; además
# los cambios detectados fuera de Terraform (drift) y los outputs que cambian. Escribe en
# stdout los conteos (review_creates, review_updates, review_replaces, review_deletes,
# review_forgets, review_imports, review_reads, review_drift, review_outputs).
#
# Confidencialidad (el repositorio y sus resúmenes son públicos): el JSON del plan trae los
# valores reales, también los sensibles. Por cada recurso se muestran solo las RUTAS de los
# atributos que cambian (hasta 3 niveles). Una ruta sensible (ella, un prefijo o un
# descendiente marcados en before_sensitive/after_sensitive) se reduce a su atributo de
# primer nivel, que es un nombre del esquema del provider: debajo puede haber claves
# dinámicas de mapas, y ni ellas ni sus valores se publican. Un valor se muestra únicamente
# si el atributo es de primer nivel, figura en [clases.json] (por defecto
# plan-review-classes.json junto a este script) para ese tipo de recurso, Terraform no lo
# marca sensible ni desconocido, y el valor anterior y el nuevo cumplen la clase declarada
# (enum, patrón, booleano o entero con rango). Nunca se muestran valores de outputs ni de
# drift. El JSON del plan no se copia a ningún lado.
#
# Falla (sin escribir la revisión) ante una acción desconocida, un JSON inesperado o una
# revisión demasiado grande para leerse con seguridad (REVIEW_MAX_RESOURCES, por defecto 150).
# Sus mensajes de error solo dicen la causa: un error interno de jq (que podría citar un
# valor del plan) se reemplaza por un mensaje genérico.
set -euo pipefail

fail() {
  echo "Error: $*" >&2
  exit 1
}

[ "$#" -ge 2 ] && [ "$#" -le 3 ] || fail "uso: $0 <plan.json> <revisión.md> [clases.json]"
PLAN_JSON="$1"
OUT="$2"
CLASSES="${3:-$(cd "$(dirname "$0")" && pwd)/plan-review-classes.json}"
MAX="${REVIEW_MAX_RESOURCES:-150}"
[[ "${MAX}" =~ ^[0-9]+$ ]] || fail "REVIEW_MAX_RESOURCES inválido"
jq -e 'type == "object"' "${CLASSES}" > /dev/null 2>&1 || fail "clases ilegibles: ${CLASSES}"
jq -e 'type == "object" and ((.resource_changes // []) | type == "array")
    and all(.resource_changes[]?; (.address | type == "string") and (.type | type == "string") and (.change.actions | type == "array"))
    and all((.resource_drift // [])[]?; (.address | type == "string") and (.change.actions | type == "array"))
    and ((.output_changes // {}) | type == "object")' "${PLAN_JSON}" > /dev/null 2>&1 ||
  fail "JSON del plan ilegible o con una forma inesperada"

TMP_OUT="$(umask 077 && mktemp)"
TMP_ERR="$(umask 077 && mktemp)"
trap 'rm -f "${TMP_OUT}" "${TMP_ERR}"' EXIT

jq -r --slurpfile classes "${CLASSES}" --argjson max "${MAX}" '
  $classes[0] as $cls

  # Acción de Terraform → categoría; cualquier otra combinación es desconocida
  | def kind: .change.actions as $a
      | if $a == ["no-op"] then "no-op"
        elif $a == ["read"] then "read"
        elif $a == ["create"] then "create"
        elif $a == ["update"] then "update"
        elif $a == ["delete"] then "delete"
        elif $a == ["delete", "create"] or $a == ["create", "delete"] then "replace"
        elif $a == ["forget"] then "forget"
        else "unknown" end;

  def at($v; $p): try ($v | getpath($p)) catch null;
  def safe: if type == "string" then gsub("[^A-Za-z0-9_.\\[\\]\"/:=-]"; "?") else "[no mostrable]" end;
  def keyok: type == "string" and test("^[A-Za-z0-9_-]{1,64}$");
  def fmtpath: map(if type == "number" then "[\(.)]" elif keyok then ".\(.)" else ".[clave]" end)
    | join("") | ltrimstr(".");
  # Sensible: la ruta o un prefijo suyo marcado en before_sensitive/after_sensitive
  def sens($c; $p): any(range(0; ($p | length) + 1);
      . as $i | (at($c.before_sensitive; $p[0:$i]) == true) or (at($c.after_sensitive; $p[0:$i]) == true));
  # Entrada de la revisión: ruta visible (hasta 3 niveles) o, si es sensible, solo su
  # atributo de primer nivel (nombre del esquema; nada de lo que cuelga de él). Si el objeto
  # entero está marcado sensible, ni siquiera eso: "(atributos sensibles)"
  def rootsens($c): ($c.before_sensitive == true) or ($c.after_sensitive == true);
  def entry($c; $p): if rootsens($c) then {p: [], s: true}
    elif sens($c; $p) then {p: $p[0:1], s: true}
    else {p: $p[0:3], s: false} end;
  def leaves($v): [$v | paths((type != "object" and type != "array") or length == 0)];
  def unknowns($c): [($c.after_unknown // {}) | paths(. == true)];

  # Rutas que cambian (o quedan desconocidas), colapsadas a 3 niveles o al prefijo sensible
  def changed($c): (leaves($c.before) + leaves($c.after) + unknowns($c))
    | map(select(length > 0)) | unique
    | map(select(. as $p | (at($c.before; $p) != at($c.after; $p)) or (at($c.after_unknown; $p) == true)))
    | map(entry($c; .))
    | unique;
  def toplevel($v): if ($v | type) == "object" then [$v | keys[] | [.]] else [] end;
  def created($c): (if rootsens($c) then [[]] else (toplevel($c.after) + toplevel($c.after_unknown // {})) end) | unique
    | map(entry($c; .))
    | map(select(.s or (at($c.after; .p) != null) or (at($c.after_unknown; .p) == true)))
    | unique;

  def classok($k; $v): if $v == null then false
      elif $k.type == "boolean" then ($v | type) == "boolean"
      elif $k.type == "integer" then ($v | type) == "number" and ($v | floor) == $v and $v >= ($k.min // 0) and $v <= ($k.max // 0)
      elif $k.type == "enum" then ($v | type) == "string" and any($k.values[]; . == $v)
      elif $k.type == "pattern" then ($v | type) == "string" and ($v | length) <= 128 and ($v | test($k.pattern))
      else false end;
  def shown($v): if ($v | type) == "string" then $v else ($v | tojson) end;

  # Una ruta, con su valor solo si está clasificado y lo cumplen el valor anterior y el nuevo
  def item($rc; $e; $mode):
    $rc.change as $c | ($cls[$rc.type] // {}) as $t | ($e.p | fmtpath) as $name
    | if $e.s then (if ($e.p | length) == 0 then "(atributos sensibles)" else "`\($name)` (sensible)" end)
      elif ($e.p | length) == 1 and ($t | has($e.p[0])) and (at($c.after_unknown; $e.p) != true) then
        $t[$e.p[0]] as $k | at($c.before; $e.p) as $b | at($c.after; $e.p) as $a
        | if $mode == "create" and classok($k; $a) then "`\($name)` = `\(shown($a))`"
          elif $mode == "delete" and classok($k; $b) then "`\($name)` = `\(shown($b))`"
          elif ($mode == "update") and classok($k; $b) and classok($k; $a) then "`\($name)`: `\(shown($b))` → `\(shown($a))`"
          else "`\($name)`" end
      elif at($c.after_unknown; $e.p) == true then "`\($name)` (se conoce al aplicar)"
      else "`\($name)`" end;
  def items($rc; $es; $mode): ($es | length) as $n
    | ([$es[0:20][] | item($rc; .; $mode)] | join(", ")) + (if $n > 20 then ", … (+\($n - 20))" else "" end);

  def line($rc):
    $rc.change as $c | ($rc | kind) as $k
    | "- `\($rc.address | safe)`"
      + (if $c.importing != null then " — **importado**" else "" end)
      + (if $k == "create" then (created($c) as $es | if ($es | length) > 0 then " — " + items($rc; $es; "create") else "" end)
         elif $k == "delete" then (toplevel($c.before) | map(entry($c; .)) | map(select((.s | not) and (.p[0] as $a | ($cls[$rc.type] // {}) | has($a))))) as $es
           | ([$es[] | item($rc; .; "delete") | select(test("="))] | join(", ")) as $id
           | (if $id != "" then " — " + $id else "" end)
         elif $k == "forget" then " — sale del estado; el recurso real NO se destruye"
         else
           ((if $k == "replace" then
               ["motivo: `\(($rc.action_reason // "sin motivo informado") | safe)`"]
               + (if ($rc.replace_paths // []) | length > 0
                  then ["fuerzan el reemplazo: " + ([$rc.replace_paths[] | select(type == "array") | map(if type == "number" then . else tostring end) | entry($c; .) | item($rc; .; "update")] | unique | join(", "))]
                  else [] end)
             else [] end)
            + (changed($c) as $es | if ($es | length) > 0 then ["cambia: " + items($rc; $es; "update")] else [] end))
           | if length > 0 then " — " + join("; ") else "" end
         end);

  (.resource_changes // []) as $rcs
  | [$rcs[] | select(kind == "unknown") | .address] as $unknown
  | if ($unknown | length) > 0 then error("revisión: acciones desconocidas en: \($unknown | map(safe) | join(", "))") else . end
  | [$rcs[] | select(kind | IN("no-op", "read") | not)] as $mat
  | if ($mat | length) > $max then error("revisión: \($mat | length) recursos con cambios: más de \($max), la revisión no se puede leer con seguridad") else . end
  | ((.deferred_changes // []) | length) as $deferred
  | if $deferred > 0 then error("revisión: el plan tiene \($deferred) cambios diferidos: no admitidos") else . end
  | [$rcs[] | select(.change.importing != null)] as $imp
  | [(.resource_drift // [])[] | select(.change.actions != ["no-op"])] as $drift
  | [(.output_changes // {}) | to_entries[] | select(.value.actions != ["no-op"])] as $outs
  | {creates: [$mat[] | select(kind == "create")], updates: [$mat[] | select(kind == "update")],
     replaces: [$mat[] | select(kind == "replace")], deletes: [$mat[] | select(kind == "delete")],
     forgets: [$mat[] | select(kind == "forget")]} as $g
  | ([
      "| crear | actualizar | reemplazar | destruir | olvidar | importar | lecturas | drift | outputs |",
      "|---|---|---|---|---|---|---|---|---|",
      "| \($g.creates | length) | \($g.updates | length) | \($g.replaces | length) | \($g.deletes | length) | \($g.forgets | length) | \($imp | length) | \([$rcs[] | select(kind == "read")] | length) | \($drift | length) | \($outs | length) |",
      ""]
     + (if ($g.deletes | length) + ($g.replaces | length) > 0 then
          ["> [!WARNING]",
           "> **Plan destructivo:** \($g.deletes | length) recurso(s) se destruyen y \($g.replaces | length) se reemplazan (se destruyen y se vuelven a crear). Revisar cada uno antes de aprobar; un efecto sobre recursos ajenos a este cambio es motivo para rechazar e investigar.",
           ""]
        else [] end)
     + (if ($g.deletes | length) > 0 then ["#### Destruir (\($g.deletes | length))"] + [$g.deletes[] | line(.)] + [""] else [] end)
     + (if ($g.replaces | length) > 0 then ["#### Reemplazar (\($g.replaces | length))"] + [$g.replaces[] | line(.)] + [""] else [] end)
     + (if ($g.updates | length) > 0 then ["#### Actualizar (\($g.updates | length))"] + [$g.updates[] | line(.)] + [""] else [] end)
     + (if ($g.creates | length) > 0 then ["#### Crear (\($g.creates | length))"] + [$g.creates[] | line(.)] + [""] else [] end)
     + (if ($g.forgets | length) > 0 then ["#### Olvidar (\($g.forgets | length))"] + [$g.forgets[] | line(.)] + [""] else [] end)
     + (if ($imp | length) > 0 then ["#### Importar (\($imp | length))"] + [$imp[] | "- `\(.address | safe)`"] + [""] else [] end)
     + (if ($drift | length) > 0 then ["#### Cambios fuera de Terraform (drift, \($drift | length))"]
          + [$drift[] | "- `\(.address | safe)` (\(.change.actions | join(",") | safe))"] + [""] else [] end)
     + (if ($outs | length) > 0 then ["#### Outputs que cambian (\($outs | length); valores no publicados)"]
          + [$outs[] | "- `\(.key | safe)` (\(.value.actions | join(",") | safe))"] + [""] else [] end)
     + (if ($mat | length) == 0 and ($outs | length) == 0 then ["_Sin cambios de recursos ni de outputs._", ""] else [] end)
     + ["_Valores: solo los clasificados en `.github/scripts/plan-review-classes.json`; el resto se muestra como ruta sin valor._"]
    ) as $md
  | ($md | join("\n")),
    "@@conteos@@",
    "review_creates=\($g.creates | length)",
    "review_updates=\($g.updates | length)",
    "review_replaces=\($g.replaces | length)",
    "review_deletes=\($g.deletes | length)",
    "review_forgets=\($g.forgets | length)",
    "review_imports=\($imp | length)",
    "review_reads=\([$rcs[] | select(kind == "read")] | length)",
    "review_drift=\($drift | length)",
    "review_outputs=\($outs | length)"
' "${PLAN_JSON}" > "${TMP_OUT}" 2> "${TMP_ERR}" || {
  # Solo los errores propios (prefijo "revisión:") se muestran; cualquier otro diagnóstico
  # de jq puede citar un valor del plan y se reemplaza por uno genérico
  if grep -qE '^jq: error \([^)]*\): revisión: ' "${TMP_ERR}"; then
    sed -nE 's/^jq: error \([^)]*\): revisión: /Error: /p' "${TMP_ERR}" | head -1 >&2
  else
    echo "Error: error interno al generar la revisión del plan (detalle omitido: podría citar valores del plan)" >&2
  fi
  exit 1
}

# Separador: antes la revisión, después los conteos (las direcciones se sanean y los valores
# publicados cumplen su clase, así que la revisión no puede contener esta línea)
SEP=$(grep -n -m1 -x '@@conteos@@' "${TMP_OUT}" | cut -d: -f1) || fail "salida interna inesperada"
head -n "$((SEP - 1))" "${TMP_OUT}" > "${OUT}"
tail -n "+$((SEP + 1))" "${TMP_OUT}"

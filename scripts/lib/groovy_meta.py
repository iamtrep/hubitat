"""Parse Hubitat Groovy app/driver metadata.

Extracts the `definition()` block (name, description, parent, namespace) and
the `APP_VERSION` / `UI_VERSION` constants used by this repo's apps. Tolerant
of the variations in formatting seen across the codebase (single- vs.
multi-line definition, name pulled from an `APP_NAME` field constant, etc.).

This is not a full Groovy parser. It is regex-driven and only recognises the
shapes of `definition()` and `@Field static final String FOO = "bar"` that
appear in this repo. Verify with the actual sources if you add new shapes.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path


@dataclass
class GroovyMeta:
    path: Path
    name: str | None
    description: str | None
    namespace: str | None
    parent: str | None  # "iamtrep:Some Parent" or None
    app_version: str | None
    ui_version: str | None

    @property
    def is_child(self) -> bool:
        return self.parent is not None


_FIELD_RE = re.compile(
    r"@Field\s+static\s+final\s+\w+\s+(\w+)\s*=\s*"
    r"(?:\"([^\"]*)\"|'([^']*)')"
)

_DEFINITION_OPEN_RE = re.compile(r"\bdefinition\s*\(")

# Inside a definition block, match `key: "value"`, `key: 'value'`, or `key: IDENT`.
# We keep the identifier form because some apps use `name: APP_NAME` (where
# APP_NAME is a @Field constant we already captured).
_DEFKV_RE = re.compile(
    r"(\w+)\s*:\s*(?:"
    r"\"((?:[^\"\\]|\\.)*)\""
    r"|'((?:[^'\\]|\\.)*)'"
    r"|(\w+))"
)


def _extract_definition_body(text: str) -> str | None:
    """Return the inside of `definition( ... )` with balanced parens.

    Returns None if no `definition(` is found or parens never balance.
    """
    om = _DEFINITION_OPEN_RE.search(text)
    if not om:
        return None
    i = om.end()
    depth = 1
    in_str = False
    str_q = ""
    n = len(text)
    start = i
    while i < n:
        c = text[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == str_q:
                in_str = False
        else:
            if c in ('"', "'"):
                in_str = True
                str_q = c
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    return text[start:i]
        i += 1
    return None


def parse(path: Path) -> GroovyMeta:
    """Parse the given Groovy file. Missing fields come back as None."""
    text = path.read_text(encoding="utf-8", errors="replace")

    fields: dict[str, str] = {}
    for m in _FIELD_RE.finditer(text):
        fields[m.group(1)] = m.group(2)

    name = description = namespace = parent = None
    body = _extract_definition_body(text)
    if body is not None:
        for km in _DEFKV_RE.finditer(body):
            key = km.group(1)
            literal_dq = km.group(2)
            literal_sq = km.group(3)
            ident = km.group(4)
            if literal_dq is not None:
                value = literal_dq
            elif literal_sq is not None:
                value = literal_sq
            else:
                value = fields.get(ident)
            if value is None:
                continue
            # Strip Groovy escapes from string literals
            value = value.replace('\\"', '"').replace("\\\\", "\\")
            if key == "name":
                name = value
            elif key == "description":
                description = value
            elif key == "namespace":
                namespace = value
            elif key == "parent":
                parent = value

    return GroovyMeta(
        path=path,
        name=name,
        description=description,
        namespace=namespace,
        parent=parent,
        app_version=fields.get("APP_VERSION"),
        ui_version=fields.get("UI_VERSION"),
    )

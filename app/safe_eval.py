"""Güvenli formül değerlendirici.

Veri dosyasındaki formüller (ör. "(A*256+B)/4-40") ``eval`` KULLANILMADAN,
kısıtlı bir AST yürüyücüsü ile hesaplanır. Yalnızca sayılar, A-H bayt
değişkenleri, aritmetik/bit işleçleri ve birkaç beyaz listedeki fonksiyon
kabul edilir.
"""
from __future__ import annotations

import ast
import math
import operator as op
from typing import Callable

MAX_LEN = 200
MAX_NODES = 80
VARS = "ABCDEFGH"


class FormulaError(ValueError):
    pass


def _s8(x):
    x = int(x) & 0xFF
    return x - 256 if x > 127 else x


def _s16(x):
    x = int(x) & 0xFFFF
    return x - 65536 if x > 32767 else x


FUNCS = {"abs": abs, "min": min, "max": max, "round": round, "s8": _s8, "s16": _s16,
         "sqrt": math.sqrt, "int": int, "float": float}

BIN = {ast.Add: op.add, ast.Sub: op.sub, ast.Mult: op.mul, ast.Div: op.truediv,
       ast.FloorDiv: op.floordiv, ast.Mod: op.mod, ast.Pow: op.pow,
       ast.LShift: op.lshift, ast.RShift: op.rshift, ast.BitAnd: op.and_,
       ast.BitOr: op.or_, ast.BitXor: op.xor}
UNA = {ast.UAdd: op.pos, ast.USub: op.neg, ast.Invert: op.invert, ast.Not: op.not_}
CMP = {ast.Lt: op.lt, ast.LtE: op.le, ast.Gt: op.gt, ast.GtE: op.ge,
       ast.Eq: op.eq, ast.NotEq: op.ne}


def _check(node, count=[0]):  # noqa: B006 - sayaç doğrulamada sıfırlanır
    pass


def _eval(node, env):
    if isinstance(node, ast.Expression):
        return _eval(node.body, env)
    if isinstance(node, ast.Constant):
        if isinstance(node.value, bool) or not isinstance(node.value, (int, float)):
            raise FormulaError("Geçersiz sabit")
        return node.value
    if isinstance(node, ast.Name):
        if node.id in VARS:
            return env.get(node.id, 0)
        raise FormulaError(f"Bilinmeyen değişken: {node.id}")
    if isinstance(node, ast.BinOp):
        f = BIN.get(type(node.op))
        if f is None:
            raise FormulaError("Geçersiz işleç")
        a, b = _eval(node.left, env), _eval(node.right, env)
        if isinstance(node.op, ast.Pow) and (abs(b) > 16 or abs(a) > 1e6):
            raise FormulaError("Üs çok büyük")
        if isinstance(node.op, (ast.LShift, ast.RShift)) and not (0 <= b <= 64):
            raise FormulaError("Kaydırma çok büyük")
        return f(a, b)
    if isinstance(node, ast.UnaryOp):
        f = UNA.get(type(node.op))
        if f is None:
            raise FormulaError("Geçersiz işleç")
        return f(_eval(node.operand, env))
    if isinstance(node, ast.IfExp):
        return _eval(node.body, env) if _eval(node.test, env) else _eval(node.orelse, env)
    if isinstance(node, ast.Compare):
        left = _eval(node.left, env)
        for o, c in zip(node.ops, node.comparators):
            f = CMP.get(type(o))
            right = _eval(c, env)
            if f is None or not f(left, right):
                if f is None:
                    raise FormulaError("Geçersiz karşılaştırma")
                return False
            left = right
        return True
    if isinstance(node, ast.Call):
        if not isinstance(node.func, ast.Name) or node.func.id not in FUNCS or node.keywords:
            raise FormulaError("Geçersiz fonksiyon çağrısı")
        return FUNCS[node.func.id](*[_eval(a, env) for a in node.args])
    raise FormulaError(f"İzin verilmeyen ifade: {type(node).__name__}")


def compile_formula(text: str) -> Callable[..., float]:
    """Formülü doğrular ve ``f(data: bytes|list) -> float`` döndürür."""
    text = (text or "").strip()
    if not text or len(text) > MAX_LEN:
        raise FormulaError("Formül boş veya çok uzun")
    try:
        tree = ast.parse(text, mode="eval")
    except SyntaxError as e:
        raise FormulaError(f"Sözdizimi hatası: {e.msg}") from None
    if sum(1 for _ in ast.walk(tree)) > MAX_NODES:
        raise FormulaError("Formül çok karmaşık")
    # Ön doğrulama: sahte bir değerlendirme ile tüm düğüm türlerini denetle
    _validate(tree)

    def run(data) -> float:
        env = {VARS[i]: int(b) for i, b in enumerate(list(data)[:len(VARS)])}
        try:
            return float(_eval(tree, env))
        except ZeroDivisionError:
            raise FormulaError("Sıfıra bölme") from None
        except (OverflowError, ValueError, TypeError) as e:
            raise FormulaError(str(e)) from None
    return run


def _validate(tree):
    allowed = (ast.Expression, ast.Constant, ast.Name, ast.BinOp, ast.UnaryOp, ast.IfExp,
               ast.Compare, ast.Call, ast.Load) + tuple(BIN) + tuple(UNA) + tuple(CMP)
    for n in ast.walk(tree):
        if not isinstance(n, allowed):
            raise FormulaError(f"İzin verilmeyen ifade: {type(n).__name__}")
        if isinstance(n, ast.Name) and n.id not in VARS and n.id not in FUNCS:
            raise FormulaError(f"Bilinmeyen değişken: {n.id}")
        if isinstance(n, ast.Constant) and (isinstance(n.value, bool)
                                            or not isinstance(n.value, (int, float))):
            raise FormulaError("Geçersiz sabit")


def evaluate(text: str, data) -> float:
    return compile_formula(text)(data)

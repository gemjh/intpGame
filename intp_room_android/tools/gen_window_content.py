"""
app/engine/window_content.py (웹 버전) -> engine/.../WindowContent.kt 생성기.

148문장과 조건 규칙을 손으로 옮기면 한 글자씩 어긋나기 쉬우므로, 파이썬 데이터를 그대로 읽어
코틀린 소스로 찍어낸다. 웹 버전 콘텐츠가 바뀌면 저장소 루트에서 다시 실행한다:

    python3 intp_room_android/tools/gen_window_content.py
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from app.engine import window_content as wc  # noqa: E402

OUT = ROOT / "intp_room_android/engine/src/main/kotlin/com/example/introom/engine/WindowContent.kt"


def s(v) -> str:
    if v is None:
        return "null"
    return '"' + str(v).replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def slist(items) -> str:
    return "listOf(" + ", ".join(s(x) for x in items) + ")"


def sset(items) -> str:
    return "setOf(" + ", ".join(s(x) for x in sorted(items)) + ")"


def smap(d: dict) -> str:
    return "mapOf(" + ", ".join(f"{s(k)} to {s(v)}" for k, v in d.items()) + ")"


def record_rule(rule: dict) -> str:
    if "flags_min" in rule:
        return f"RecordRule.FlagsMin({slist(rule['flags_min'])}, {rule['n']})"
    if "days" in rule:
        return f"RecordRule.Days({s(rule['days'])}, {rule['n']})"
    (kind, flag), = rule.items()
    return f"RecordRule.Flag({s(kind)}, {s(flag)})"


def main():
    out = []
    w = out.append
    w("package com.example.introom.engine")
    w("")
    w("/*")
    w(" * 창문 관찰 콘텐츠 148문장 + 출현 조건 해석 규칙.")
    w(" *")
    w(" * 자동 생성 파일 — 직접 고치지 말 것. 원본은 웹 버전 app/engine/window_content.py 이고,")
    w(" * intp_room_android/tools/gen_window_content.py 로 다시 만든다 (문장/조건이 웹 버전과 한 글자도 다르지 않게).")
    w(" */")
    w("")
    w("data class Requirement(val type: String, val key: String)")
    w("")
    w("data class WindowObservation(")
    w("    val id: String,")
    w("    val subject: String,")
    w("    val text: String,")
    w("    val requirements: List<Requirement>,")
    w("    val knowledgeMode: String?, // null | \"discover\" | \"apply\"")
    w("    val knowledgeIds: List<String>,")
    w("    val knowledgeLabel: String?,")
    w("    val repeatPolicy: String, // \"normal\" | \"interval\"")
    w("    val effects: List<String>,")
    w("    val followUpIds: List<String>,")
    w("    val weightClass: String,")
    w(")")
    w("")
    w("/** 기록 조건 -> 사실 플래그 판정 규칙 (Window.kt 의 recordOk 참고). */")
    w("sealed class RecordRule {")
    w("    /** kind: any | today | prev_day | yesterday | same_period_other_day */")
    w("    data class Flag(val kind: String, val flag: String) : RecordRule()")
    w("    /** 서로 다른 날 n일 이상 */")
    w("    data class Days(val flag: String, val n: Int) : RecordRule()")
    w("    /** 플래그 n종 이상 */")
    w("    data class FlagsMin(val flags: List<String>, val n: Int) : RecordRule()")
    w("}")
    w("")
    w("/** 지식 조건: all(전부 필요, 빈 목록이면 충족 불가) 또는 any(하나 이상). */")
    w("data class KnowledgeRule(val all: List<String>? = null, val any: List<String>? = null, val blocked: String? = null)")
    w("")
    w(f"val WINDOW_SUBJECTS = {slist(wc.SUBJECTS)}")
    w(f"val WINDOW_SUBJECT_LABELS = {smap(wc.SUBJECT_LABELS)}")
    w(f"const val DOG_NAME = {s(wc.DOG_NAME)}")
    w("")
    w("val TIME_RULES: Map<String, Set<String>> = mapOf(")
    for k, v in wc.TIME_RULES.items():
        w(f"    {s(k)} to {sset(v)},")
    w(")")
    w("")
    w("val KNOWLEDGE_RULES: Map<String, KnowledgeRule> = mapOf(")
    for k, v in wc.KNOWLEDGE_RULES.items():
        args = []
        if "all" in v:
            args.append(f"all = {slist(v['all'])}")
        if "any" in v:
            args.append(f"any = {slist(v['any'])}")
        if "blocked" in v:
            args.append(f"blocked = {s(v['blocked'])}")
        w(f"    {s(k)} to KnowledgeRule({', '.join(args)}),")
    w(")")
    w("")
    w(f"val BLOCKED_PREFIXES = {sset(wc.BLOCKED_PREFIXES)}")
    w("")
    w("val RECORD_RULES: Map<String, RecordRule> = mapOf(")
    for k, v in wc.RECORD_RULES.items():
        w(f"    {s(k)} to {record_rule(v)},")
    w(")")
    w("")
    w("val WEIGHTS: Map<String, Double> = mapOf(" +
      ", ".join(f"{s(k)} to {float(v)}" for k, v in wc.WEIGHTS.items()) + ")")
    w(f"const val SEEN_MULT = {wc.SEEN_MULT}")
    w(f"const val RECENT_MULT = {wc.RECENT_MULT}")
    w(f"const val RECENT_WINDOW = {wc.RECENT_WINDOW}")
    w("")
    w("/** 후보가 하나도 없을 때의 중립 문장 (WIN-05). 대상을 정하지 못했을 때는 NEUTRAL_LINE_ANY. */")
    w("val NEUTRAL_LINES: Map<String, String> = mapOf(")
    for k, v in wc.NEUTRAL_LINES.items():
        if k is not None:
            w(f"    {s(k)} to {s(v)},")
    w(")")
    w(f"const val NEUTRAL_LINE_ANY = {s(wc.NEUTRAL_LINES[None])}")
    w("")
    w("/** 기존 세계 이벤트(밤바다 불빛/낮달/아저씨 귀환/고양이) 관찰이 남기는 사실 플래그. */")
    w("val LEGACY_EFFECTS: Map<String, List<String>> = mapOf(")
    for k, v in wc.LEGACY_EFFECTS.items():
        w(f"    {s(k)} to {slist(v)},")
    w(")")
    w("")
    w(f"/** 검수 시트 148문장 (SEA/SKY/SHOP/DOG/YARD 순서 그대로). 총 {len(wc.OBSERVATIONS)}개. */")
    w("val WINDOW_OBSERVATIONS: Map<String, WindowObservation> = listOf(")
    for o in wc.OBSERVATIONS.values():
        reqs = "listOf(" + ", ".join(f"Requirement({s(r['type'])}, {s(r['key'])})" for r in o["requirements"]) + ")"
        w("    WindowObservation(")
        w(f"        {s(o['id'])}, {s(o['subject'])},")
        w(f"        {s(o['text'])},")
        w(f"        {reqs}, {s(o['knowledgeMode'])}, {slist(o['knowledgeIds'])}, {s(o['knowledgeLabel'])},")
        w(f"        {s(o['repeatPolicy'])}, {slist(o['effects'])}, {slist(o['followUpIds'])}, {s(o['weightClass'])},")
        w("    ),")
    w(").associateBy { it.id }")
    w("")
    OUT.write_text("\n".join(out), encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)} ({len(wc.OBSERVATIONS)} observations)")


if __name__ == "__main__":
    main()

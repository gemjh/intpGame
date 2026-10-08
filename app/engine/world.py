"""
플레이어와 무관하게 돌아가는 세계. (명세서 3장 "세계 상태와 목격")

여기 함수들은 전부 (day, period, seed) 의 순수 함수다 - 플레이어가 봤는지 여부가
세계에서 실제로 일어나는 일에 영향을 주면 안 된다 ("세계는 플레이어를 중심으로 움직이지 않는다").
엔진은 이 '세계의 사실'과 플레이어가 실제로 관찰해서 생긴 Observation 레코드를 항상 분리해서 다룬다.
"""
from __future__ import annotations

import random
from dataclasses import dataclass


def _rng_for(day: int, seed: int, salt: str) -> random.Random:
    # day/seed/salt 가 같으면 항상 같은 값 -> 같은 날 두 번 물어봐도 같은 세계 상태가 나온다 (결정론적).
    return random.Random(f"{seed}:{day}:{salt}")


WEATHERS = ["맑음", "흐림", "비"]


def weather_of(day: int, seed: int) -> str:
    r = _rng_for(day, seed, "weather")
    return r.choices(WEATHERS, weights=[0.55, 0.3, 0.15])[0]


def owner_departs_at_dawn(day: int, seed: int) -> bool:
    """주인이 이 날 새벽에 출항하는지. 날씨가 나쁘면 안 나간다."""
    if weather_of(day, seed) == "비":
        return False
    r = _rng_for(day, seed, "depart")
    return r.random() < 0.6


def owner_returns_at_day(day: int, seed: int) -> bool:
    """출항했다면 그날 낮(day1~day2) 중 귀환한다."""
    return owner_departs_at_dawn(day, seed)


def tank_changed_after_return(day: int, seed: int) -> bool:
    if not owner_returns_at_day(day, seed):
        return False
    r = _rng_for(day, seed, "tank")
    return r.random() < 0.7


def restaurant_status(day: int, period: str, seed: int) -> str:
    """횟집 상태: closed / prep / open."""
    if period in ("dawn", "morning", "day1", "day2"):
        return "closed"
    if period == "sunset":
        return "prep"
    return "open"  # evening, night


def cat_in_yard(day: int, period: str, seed: int) -> bool:
    r = _rng_for(day, seed, f"cat:{period}")
    return r.random() < 0.35


def night_light_visible(day: int, period: str, seed: int) -> bool:
    """밤바다 흰 불빛. 매일 조금씩 다르게 보이되, 이 빌드에서 '정체'는 끝까지 확정하지 않는다."""
    if period != "night":
        return False
    if weather_of(day, seed) == "비":
        return False
    return True


def night_light_variant(day: int, seed: int) -> dict:
    """그날그날 불빛이 보이는 모양(위치/깜빡임)을 살짝 바꿔서, 재관찰할 때마다 다른 묘사가 나오게 한다."""
    r = _rng_for(day, seed, "light_variant")
    return {
        "side": "오른쪽" if r.random() < 0.8 else "왼쪽으로 살짝 더",
        "blinking": r.random() < 0.7,
        "angle_shift": r.random() < 0.25,  # 각도가 크게 달라 보이는 드문 날 (반례 재료)
    }


def daytime_moon_visible(day: int, period: str, seed: int) -> bool:
    if period not in ("day1", "day2"):
        return False
    r = _rng_for(day, seed, "moon")
    return r.random() < 0.3

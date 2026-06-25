#!/usr/bin/env python3
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def normalize(text: str) -> str:
    normalized = re.sub(r"\s+", "", text).lower()
    if re.fullmatch(r"(私|わたし|ワタシ|わたくし)(を)?(作|つく|クラフト|ほしい|欲しい|お願い|頼む).*", normalized):
        normalized = "ツルハシ作って"
    if re.search(r"作|つく|クラフト|ほしい|欲しい|お願い|頼む", normalized):
        normalized = re.sub(r"(昨日|きのう)(の)?(ツルハシ|つるはし|ピッケル|ぴっける|斧|おの|シャベル|しゃべる|スコップ|剣|けん|クワ|くわ)", r"木の\3", normalized)
    return normalized


def parse_goal(text: str) -> dict:
    t = normalize(text)
    if re.search(r"待って|やめて|ストップ|中止|キャンセル|abort|stop", t, re.I):
        return {"type": "abort"}
    if "いや" in t and re.search(r"石の?ピッケル|石の?ツルハシ|stone_pickaxe", t):
        return {"type": "craft_item", "target_item": "minecraft:stone_pickaxe"}
    if re.search(r"おおー|すごい|すげ|ナイス|nice|最高|勝った|クリア", t, re.I):
        return {"type": "celebrate", "style": "youtuber_pose"}
    if re.search(r"やった|いえーい|いぇーい|yeah|yay|woo", t, re.I):
        return {"type": "celebrate", "style": "dance"}
    if re.search(r"暗い|暗すぎ|真っ暗|見えない|明るく|あかり|明かり|灯り", t) and re.search(r"置|おいて|設置|つけ|付け|照ら|明るく|灯|して|お願い|頼む", t):
        return {"type": "place_light", "target_item": "minecraft:torch"}
    if re.search(r"松明|たいまつ|トーチ|torch", t, re.I) and re.search(r"置|おいて|設置|つけ|付け|照ら|灯", t):
        return {"type": "place_light", "target_item": "minecraft:torch"}
    if re.search(r"あかり|明かり|あかりつけるやつ", t) and re.search(r"置|おいて|つけ|して|お願い|頼む", t):
        return {"type": "place_light", "target_item": "minecraft:torch"}
    if re.search(r"エンドラ|エンダードラゴン|エンド.*ドラゴン|ender.?dragon|dragon.*fight|ドラゴン.*倒|討伐", t, re.I):
        return {"type": "prepare_nether", "route": "bucket_portal"}
    if re.search(r"ネザー|ねざー|地獄|ポータル|portal|nether|ゲート", t, re.I) and re.search(r"行|入|準備|用意|作|つく", t):
        if re.search(r"黒曜石|こくようせき|obsidian", t, re.I):
            return {"type": "prepare_nether", "route": "obsidian_frame"}
        if re.search(r"溶岩|ようがん|lava", t, re.I):
            return {"type": "prepare_nether", "route": "lava_cast"}
        return {"type": "prepare_nether", "route": "bucket_portal"}
    if re.search(r"作業台|クラフト台|craftingtable|crafting_table", t, re.I) and re.search(r"近く|そば|置|おい|開|出して|用意|お願い|頼む", t, re.I):
        return {"type": "place_workstation", "station": "minecraft:crafting_table"}
    child = parse_child_friendly(t)
    if child:
        return child
    if re.search(r"作って|作る|つくる|作りたい|作れる|クラフト|生成|用意|欲しい|ほしい|必要|お願い|頼む", t):
        craft = craft_target(t)
        if craft:
            return {"type": "craft_item", "target_item": craft}
    if is_minecraft_food_request(t):
        return {"type": "get_food"}
    if re.search(r"収穫|刈って|刈り取り|刈り取って|作物取って|作物を取って", t):
        return {"type": "context_action", "intent": "harvest"}
    explicit_move = parse_move(t)
    if explicit_move and re.search(r"まっすぐ|真っ直ぐ|後ろ|前|右|左|\d{1,3}(ブロック|段|マス|歩|個|こ|つ)?", t):
        return explicit_move
    if re.search(r"逃げて|逃げたい|離れて|距離取って|距離を取って|下がって|退避|避けて|よけて", t):
        return {"type": "context_action", "intent": "retreat"}
    if re.search(r"守って|防御|ガード|盾構えて|盾を構えて|耐えて", t):
        return {"type": "context_action", "intent": "defend"}
    if is_minecraft_open_request(t):
        return {"type": "context_action", "intent": "open"}
    if re.search(r"戦って|戦いたい|倒して|倒したい|攻撃|やっつけて|狩って|狩りたい|狩猟", t):
        if re.search(r"牛|豚|ブタ|鶏|ニワトリ|羊|ウサギ|鮭|サケ|鱈|タラ|動物|肉", t):
            return {"type": "attack_entity", "entity_group": "food_animal"}
        if re.search(r"敵|モンスター|ゾンビ|スケルトン|クリーパー|蜘蛛|クモ|エンダーマン|hostile|enemy", t, re.I):
            return {"type": "attack_entity", "entity_group": "hostile"}
        return {"type": "context_action", "intent": "attack"}
    if explicit_move:
        return explicit_move
    collect = parse_collect(t)
    if collect and collect.get("type") == "collect_block":
        return collect
    if re.search(r"拾って|拾いたい|拾え|拾う|取って|とって|取る|落ちてる|ドロップ|アイテム回収|回収して|pickup|pick.?up", t, re.I):
        return {"type": "pickup_items"}
    if "アイテム" in t and re.search(r"取|回収", t):
        return {"type": "pickup_items"}
    dig = parse_dig(t)
    if dig:
        return dig
    if collect:
        return collect
    if re.search(r"村|村人|village", t, re.I) and re.search(r"探|見つけ|どこ|向か|行き", t):
        return {"type": "search_structure", "target_group": "village_hint"}
    if re.search(r"構造物|建物|寺院|ピラミッド|廃坑|要塞|砦|沈没船|海底神殿|遺跡|ダンジョン|スポナー|チェスト|structure|temple|mineshaft|stronghold|shipwreck|dungeon", t, re.I) and re.search(r"探|見つけ|どこ|向か|行き|search|find|locate", t, re.I):
        return {"type": "search_structure", "target_group": "structure_hint"}
    if re.search(r"橋|はし|ハシ|ハッシュ|bridge", t, re.I) and re.search(r"架け|かけ|掛け|作|つく|置|渡|伸ば|前|forward|build|place", t, re.I) and not is_tool_request(t):
        return {"type": "build_bridge", "direction": "forward", "distance_blocks": parse_count(t, 4, 1, 12)}
    if re.search(r"探して|探したい|探せ|探索して|見つけて|見つけたい", t):
        return {"type": "ambient_chat", "message": "何を探す？村・食料・素材なら言ってね。"}
    return {"type": "unknown"}


def parse_child_friendly(t: str) -> dict | None:
    wants_build = re.search(r"作|つく|建|ほしい|欲しい|お願い", t)
    if re.search(r"わからない|分からない|わかんない|分かんない|なにすれば|何すれば|何したら|どうしたら|どうすれば|迷った|まよった", t):
        return {"type": "ambient_chat", "message": "まず三つ。木を集める、家を作る、村を探す。どれにする？"}
    if re.search(r"城|お城|町|街|村を作|でっかい|大きい|大きな|巨大|すごい家|豪邸", t) and wants_build:
        return {"type": "ambient_chat", "message": "まず小さい家から作る？そう言ってくれたら作るよ。"}
    if re.search(r"助けて|たすけて|こわい|怖い|やばい|危ない|あぶない|守って|まもって", t) and not re.search(r"守って|防御|ガード|盾構えて|盾を構えて|耐えて|敵|モンスター|ゾンビ|スケルトン|クリーパー|盾", t):
        return {"type": "build_shelter", "style": "safe_spot"}
    if re.search(r"家|おうち|お家|秘密基地|基地|隠れ家|かくれが|部屋|お部屋|小屋", t) and wants_build:
        style = "hideout" if re.search(r"秘密|基地", t) else "cute_house" if re.search(r"きれい|綺麗|かわいい|可愛い|ちゃんと|おしゃれ|いい感じ|立派|建築", t) else "small_house"
        return {"type": "build_structure", "style": style}
    if re.search(r"キラキラ|ぴかぴか|光る|光ってる", t) and re.search(r"探|ほしい|欲しい|見つけ", t):
        return {"type": "ambient_chat", "message": "光るもの？松明・鉱石・村、どれを探す？"}
    return None


def craft_target(t: str) -> str | None:
    checks = [
        (r"ダイヤの?ツルハシ|ダイヤの?ピッケル|diamond_pickaxe", "minecraft:diamond_pickaxe"),
        (r"鉄の?ツルハシ|鉄の?ピッケル|iron_pickaxe", "minecraft:iron_pickaxe"),
        (r"木の?(ツルハシ|つるはし)|木の?(ピッケル|ぴっける)|wooden_pickaxe", "minecraft:wooden_pickaxe"),
        (r"石の?ツルハシ|石の?ピッケル|stone_pickaxe", "minecraft:stone_pickaxe"),
        (r"作業台|クラフト台|crafting_table", "minecraft:crafting_table"),
        (r"松明|たいまつ|トーチ|torch", "minecraft:torch"),
        (r"棒|stick", "minecraft:stick"),
        (r"かまど|竈|furnace", "minecraft:furnace"),
        (r"チェスト|chest", "minecraft:chest"),
        (r"ツルハシ|つるはし|ピッケル|ぴっける|pickaxe", "minecraft:stone_pickaxe"),
        (r"ガラス|glass", "minecraft:glass"),
        (r"盾|シールド|shield", "minecraft:shield"),
        (r"バケツ|bucket", "minecraft:bucket"),
        (r"火打石|打ち金|flint", "minecraft:flint_and_steel"),
        (r"ボート|boat", "minecraft:boat"),
        (r"剣|sword", "minecraft:sword"),
        (r"ベッド|bed", "minecraft:bed"),
        (r"石.*階段|stone.*stairs", "minecraft:stone_stairs_family"),
    ]
    for pattern, item in checks:
        if re.search(pattern, t, re.I):
            return item
    return None


def is_tool_request(t: str) -> bool:
    return bool(re.search(r"ツルハシ|つるはし|ピッケル|ぴっける|pickaxe", t, re.I))


def parse_move(t: str) -> dict | None:
    if not re.search(r"まっすぐ|真っ直ぐ|歩いて|歩きたい|あるいて|歩け|走って|走りたい|はしって|走れ|ダッシュ|進んで|進め|すすんで|すすめ|すすむ|前進|移動して|下がって|下がりたい|ずれて|ずれ|避けて|よけて|抜けて|抜けたい|出て|出たい|泳いで|泳ぎたい|およいで|泳げ|swim|move|walk|run|sprint|go", t, re.I):
        return None
    swim = bool(re.search(r"泳いで|泳ぎたい|およいで|泳げ|swim", t, re.I))
    sprint_jump = bool(re.search(r"走りながらジャンプ|走ってジャンプ|ダッシュジャンプ|ジャンプしながら|sprint.?jump|jump.?sprint", t, re.I))
    sprint = swim or bool(re.search(r"走|ダッシュ|抜け|sprint|run", t, re.I))
    direction = "forward"
    if re.search(r"下が|戻|back", t, re.I):
        direction = "back"
    elif re.search(r"左|left", t, re.I):
        direction = "left"
    elif re.search(r"右|right", t, re.I):
        direction = "right"
    explicit_distance = has_explicit_move_distance(t)
    fallback = 10 if swim else 12 if sprint else 12 if re.search(r"まっすぐ|真っ直ぐ|進|すす", t) else 6
    distance = parse_count(t, fallback, 1, 96)
    goal = {
        "type": "move",
        "direction": direction,
        "distance_blocks": distance,
        "scan_aware": True,
        "adaptive_distance": True,
        "extend_if_clear": not explicit_distance,
        "max_adaptive_distance_blocks": 18 if swim else 24 if sprint else 16,
        "face_move_direction": True,
        "auto_jump": True,
    }
    if swim:
        goal["sprint"] = True
        goal["swim"] = True
    if sprint:
        goal["sprint"] = True
    if sprint_jump:
        goal["sprint"] = True
        goal["jump_while_sprinting"] = True
    return goal


def has_explicit_move_distance(t: str) -> bool:
    if re.search(r"\d{1,3}(ブロック|段|マス|歩|個|こ|つ|本)?", t):
        return True
    for word in ["一", "二", "三", "四", "五", "六", "七", "八", "九", "十"]:
        if any(token in t for token in [word + "ブロック", word + "段", word + "マス", word + "歩"]):
            return True
    return bool(re.search(r"少し|ちょっと|しばらく|長く", t))


def parse_dig(t: str) -> dict | None:
    if not re.search(r"階段掘り|階段ぼり|階段堀り|横穴|横掘り|横ぼり|トンネル|縦掘り|縦ぼり|下掘り|下に掘|掘って|掘りたい|掘れ", t):
        return None
    pattern = "staircase_down"
    if re.search(r"横穴|横掘り|横ぼり|トンネル", t):
        pattern = "tunnel_forward"
    elif re.search(r"縦掘り|縦ぼり", t):
        pattern = "shaft_down_safe"
    return {"type": "dig_pattern", "pattern": pattern, "distance_blocks": parse_count(t, 4, 1, 12)}


def parse_collect(t: str) -> dict | None:
    if not re.search(r"掘って|掘りたい|採掘|取って|取りたい|集めて|集めたい|回収|壊して|壊したい|切って|伐採", t):
        return None
    groups = [
        (r"石炭鉱石|石炭|coal", "coal_ore"),
        (r"土|泥|dirt", "dirt"),
        (r"砂利|gravel", "gravel"),
        (r"砂|sand", "sand"),
        (r"丸石|石|cobble|stone", "cobblestone"),
        (r"木|原木|log", "log"),
    ]
    for pattern, group in groups:
        if re.search(pattern, t, re.I):
            return {"type": "collect_block", "block_group": group, "count": parse_count(t, 1, 1, 32)}
    if re.search(r"掘|採掘|壊", t):
        return {"type": "context_action", "intent": "mine"}
    return {"type": "context_action", "intent": "take"}


def parse_count(t: str, fallback: int, minimum: int, maximum: int) -> int:
    match = re.search(r"(\d{1,3})(ブロック|段|マス|歩|個|こ|つ|本)?", t)
    if match:
        return max(minimum, min(maximum, int(match.group(1))))
    return fallback


def is_minecraft_food_request(t: str) -> bool:
    if not re.search(r"食料|食べ物|ご飯|ごはん|腹減|お腹すい|空腹|食べたい|食べて|飯|めし|パン|ベリー|肉|リンゴ|りんご", t):
        return False
    if re.search(r"ラーメン|寿司|すし|カレー|ピザ|コーヒー|お茶|酒|ビール|レストラン|外食", t):
        return False
    if re.search(r"ベリー|リンゴ|りんご|肉|パン|食料|食べ物|空腹|腹減|お腹すい|ハート|満腹度", t):
        return True
    return bool(re.search(r"ゲーム内|マイクラ|サバイバル|食べられるもの|食べれるもの", t))


def is_minecraft_open_request(t: str) -> bool:
    if not re.search(r"開けて|開いて|開きたい|ドア開け|ゲート開け|open", t, re.I):
        return False
    if re.search(r"ブラウザ|ニュース|アプリ|サイト|url|URL|ページ|メール|予定|カレンダー", t):
        return False
    return bool(re.search(r"ドア|扉|ゲート|フェンスゲート|通路|柵|村|チェスト|かまど|作業台", t))


def main() -> None:
    fixtures = json.loads((ROOT / "examples" / "speech_fixtures.json").read_text())
    for fixture in fixtures:
        expected = fixture.get("expected_goal")
        if not expected:
            continue
        actual = parse_goal(fixture["recognized_text"])
        for key, value in expected.items():
            if actual.get(key) != value:
                raise AssertionError(f"{fixture['id']}.{key}: expected {value!r}, got {actual.get(key)!r} from {actual}")
    print("[fixtures] speech fixtures passed")


if __name__ == "__main__":
    main()

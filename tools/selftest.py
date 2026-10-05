#!/usr/bin/env python3
"""OfflineRef Self-Test: полный прогон слоёв на реальной базе v3.
Запуск: python3 tools/selftest.py [путь_к_kb_base.zip]
Коды выхода: 0 = все критические слои OK, 1 = есть критические провалы."""
import json
import re
import sys
import zipfile

KB_PATH = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/kb_base.zip"
SUF = ["иями","ями","иях","ами","ях","иям","ям","еми","их","ых","ого","его","ому","ему",
       "ом","ем","ов","ев","ий","ый","ой","ая","яя","ое","ее","ые","ие","ую","юю",
       "ить","ать","ять","ти","ка","ки","ку","ке","кой","кам","ками",
       "ние","ния","нию","ции","цию","ный","ного","ным","ных",
       "ения","ение","ении","ениям","атель","итель","а","я","о","е","ы","и","ь","у","ю"]
STOP = set("""что как при для это или если без под над ещё уже можно нужно надо после
перед чтобы который весь сам самый очень где там тут кто его её их них этом
когда потому также более менее другой такой какой стоит сделать делать будет
быть был была были есть иметь сказать""".split())

def norm(t): return t.lower().replace('ё', 'е')

def stemW(w):
    s = w; it = 0
    while it < 3 and len(s) >= 5:
        for x in SUF:
            if s.endswith(x) and len(s) - len(x) >= 4:
                s = s[:-len(x)]; break
        else:
            break
        it += 1
    return s

def hard_split(p, target=550):
    if len(p) <= target * 2: return [p]
    out, i = [], 0
    while i < len(p):
        j = min(i + target, len(p))
        if j < len(p):
            sp = p.rfind(' ', i, j)
            if sp > i + target // 2:
                j = sp
            else:
                sp2 = p.find(' ', j)
                if sp2 > j: j = sp2
        out.append(p[i:j].strip()); i = j
        while i < len(p) and p[i] == ' ': i += 1
    return [x for x in out if x]

def chunk_text(text):
    paras = []
    for p in re.split(r"\n\s*\n", text):
        paras += hard_split(p.strip())
    chunks, sb = [], []
    for clean in paras:
        if not clean: continue
        cur = "\n".join(sb)
        if sb and len(cur) + len(clean) > 550:
            chunks.append(cur)
            last = cur.split("\n")[-1].strip()
            sb = [last] if last and len(last) < 200 else []
        sb.append(clean)
    if sb: chunks.append("\n".join(sb))
    return chunks

def main():
    zf = zipfile.ZipFile(KB_PATH)
    docs = {nm: zf.read(nm).decode("utf-8") for nm in zf.namelist()
            if nm.endswith(".txt") and "база_полная" not in nm}
    chunks = []
    for nm, text in docs.items():
        if "[стр. " in text:
            for sec in text.split("\n\n"):
                m = re.match(r"^\[стр\. (\d+)\]\s*", sec.strip())
                if not m: continue
                body = sec.strip()[m.end():].strip()
                for idx, c in enumerate(chunk_text(body)):
                    chunks.append({"doc": nm, "page": int(m.group(1)), "text": c})
        else:
            for idx, c in enumerate(chunk_text(text)):
                chunks.append({"doc": nm, "page": 0, "text": c})

    def search(query, k=2):
        qn = norm(query)
        words = [w for w in re.sub(r"[^a-zа-я0-9 ]", " ", qn).split()
                 if len(w) >= 3 and w not in STOP]
        if not words: return []
        stems = [stemW(w) for w in words]
        qwords = [w for w in re.sub(r"[^a-zа-я0-9 ]", " ", qn).split() if len(w) >= 2]
        bigrams = set(zip(qwords, qwords[1:]))
        scored = []
        for ch in chunks:
            text = norm(ch["text"])
            tokSet = set(t for t in re.split(r"[^a-zа-я0-9]+", text) if len(t) >= 3)
            score, exact = 0, 0
            for i, w in enumerate(words):
                if w in tokSet: score += 2; exact += 1
                elif any(stemW(t) == stems[i] for t in tokSet): score += 1
            tw = [t for t in re.split(r"[^a-zа-я0-9]+", text) if len(t) >= 2]
            for bg in set(zip(tw, tw[1:])):
                if bg in bigrams: score += 2; exact += 1; break
            if score: scored.append((score, exact, ch))
        scored.sort(key=lambda x: (-x[0], -x[1]))
        return [(s, c) for s, e, c in scored[:k]]

    report = {"kb_docs": len(docs), "kb_chunks": len(chunks), "layers": {}}

    # слой 1: структура базы
    pages = set(c["page"] for c in chunks if c["page"] > 0)
    l1 = (len(docs) >= 15 and len(chunks) >= 500 and len(pages) >= 300)
    report["layers"]["L1_structure"] = {
        "pass": l1, "docs": len(docs), "chunks": len(chunks), "pages": len(pages)}

    # слой 2: поиск (top-2)
    QA = [("подосиновик", "11_справочник_грибника.txt"),
          ("бледная поганка", "11_справочник_грибника.txt"),
          ("парацетамол дозировка", "02_аптечка_лекарства.txt"),
          ("обморок первая помощь", "01_первая_помощь_основы.txt"),
          ("кипячение воды очистка", "03_выживание_огонь_вода_укрытие.txt"),
          ("полярная звезда", "18_навигация_по_светилам.txt"),
          ("перегрев двигателя", "05_авто_поломки.txt"),
          ("запах газа", "07_дом_аварии.txt"),
          ("обморожение", "14_зимнее_выживание.txt"),
          ("узел булинь", "17_узлы_и_верёвки.txt"),
          ("медведь тайга", "13_тайга_выживание.txt"),
          ("огонь без зажигалки", "16_огонь_без_зажигалки.txt"),
          ("аптечка состав", "02_аптечка_лекарства.txt"),
          ("аварийный рюкзак", "08_документы_деньги_набор.txt"),
          ("сел аккумулятор", "06_авто_зима.txt"),
          ("сигнал sos", "03_выживание_огонь_вода_укрытие.txt"),
          ("нос кровотечение", "01_первая_помощь_основы.txt"),
          ("мухомор", "11_справочник_грибника.txt"),
          ("инсульт признаки", "01_первая_помощь_основы.txt"),
          ("подберёзовик", "11_справочник_грибника.txt")]
    hits = 0
    detail = []
    for q, expect in QA:
        res = search(q, 2)
        ok = any(c["doc"] == expect for _, c in res)
        hits += ok
        detail.append({"q": q, "ok": ok, "top": [c["doc"] for _, c in res][:1]})
    l2 = hits >= len(QA) * 0.85
    report["layers"]["L2_search"] = {"pass": l2, "hits": hits, "total": len(QA), "detail": detail}

    # слой 3: RAG-промпт непустой и в бюджете
    rag_ok = 0
    for q, expect in QA:
        res = search(q, 2)
        if not res: continue
        budget = 1300
        total = 0
        for _, ch in res:
            t = ch["text"][:max(0, budget)]
            total += len(t)
            budget -= len(t)
        if 200 < total <= 1500: rag_ok += 1
    l3 = rag_ok >= len(QA) * 0.9
    report["layers"]["L3_rag_prompt"] = {"pass": l3, "ok": rag_ok, "total": len(QA)}

    # слой 4: негатив (уверенные ложные срабатывания score>=4)
    NEG = ["рамен рецепт", "курс доллара", "фильм посоветуй", "английский выучить", "торт рецепт"]
    false_pos = 0
    for q in NEG:
        res = search(q, 1)
        if res and res[0][0] >= 4: false_pos += 1
    l4 = false_pos == 0
    report["layers"]["L4_negative"] = {"pass": l4, "false_positives": false_pos}

    # слой 5: скорость (поиск < 200 мс на вопрос, полный скан)
    import time
    t0 = time.time()
    for q, _ in QA:
        search(q, 2)
    dt = (time.time() - t0) / len(QA) * 1000
    l5 = dt < 200
    report["layers"]["L5_speed"] = {"pass": l5, "ms_per_query": round(dt, 1)}


    # слой 6: каталог видов (гарантированные иллюстрации)
    import urllib.request, os
    SP_QA = [("подосиновик", "подосиновик"), ("бледная поганка", "поганка"),
             ("мухомор красный", "мухомор"), ("опёнок", "оп"),
             ("лисичка", "лисичк"), ("белый гриб", "бел"), ("рыжик", "рыжик"),
             ("сморчок", "сморчк"), ("строчок", "строчк"), ("вешенка", "вешенк")]
    sp_hits = 0
    spec = {}
    try:
        with urllib.request.urlopen(
            "https://raw.githubusercontent.com/TheLastie/Droid-llm-helper/apk/species.json", timeout=20) as r:
            for e in json.load(r):
                spec[e["name"]] = e["atlas_page"]
    except Exception:
        spec = {}
    def fsp(query):
        qw = [stemW(w) for w in re.sub(r"[^a-zа-я0-9 ]", " ", norm(query)).split()
              if len(w) >= 3 and w not in STOP]
        best, bs = None, 0
        for nm, pg in spec.items():
            sw = [w for w in nm.split() if len(w) >= 3]
            sc = sum(2 for a in qw if any(stemW(b) == a or b.startswith(a) or a.startswith(b) for b in sw))
            if sc > bs: bs, best = sc, (nm, pg)
        return best if bs >= 2 else None
    for q, frag in SP_QA:
        r = fsp(q)
        if r and frag in r[0]: sp_hits += 1
    l6 = sp_hits >= len(SP_QA) * 0.8 and len(spec) >= 250
    report["layers"]["L6_species"] = {"pass": l6, "catalog": len(spec), "hits": sp_hits, "total": len(SP_QA)}

    print(json.dumps(report, ensure_ascii=False, indent=1))
    critical = all(report["layers"][k]["pass"]
                   for k in ["L1_structure", "L2_search", "L3_rag_prompt", "L4_negative", "L6_species"])
    print("CRITICAL:", "PASS" if critical else "FAIL")
    return 0 if critical else 1

if __name__ == "__main__":
    sys.exit(main())

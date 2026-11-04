"""차 안 모델 평가 채점 — NoteAssistantEvalTest 와 같은 규칙(할 일 keys 묶음 일치, 후속 일정 날짜 일치).
사용: python3 tools/score_llm_eval.py <eval_llm.jsonl> [app/src/test/resources/eval/notes_eval.json]"""
import json, re, sys
pred = {json.loads(l)["id"]: json.loads(l) for l in open(sys.argv[1]) if l.strip()}
root = json.load(open(sys.argv[2] if len(sys.argv) > 2 else "app/src/test/resources/eval/notes_eval.json"))
norm = lambda s: re.sub(r"[\s\W_·…’‘“”]", "", s.lower())
def hit(p, t): return all(any(norm(k) in norm(p) for k in g.split("|")) for g in t["keys"])
gold = hit_n = predn = correct_pred = fu_ok = fu_n = fail = 0; ms = []
for it in root["items"]:
    p = pred.get(it["id"]); 
    if not p: continue
    if p["source"] == "fail": fail += 1
    ms.append(p["ms"]); gold += len(it["tasks"]); predn += len(p["tasks"])
    hit_n += sum(any(hit(x, t) for x in p["tasks"]) for t in it["tasks"])
    correct_pred += sum(any(hit(x, t) for t in it["tasks"]) for x in p["tasks"])
    fu_n += 1; fu_ok += (p["followUp"] == it["followUp"])
print(f"items {len(pred)} · 실패 {fail} · 평균 {sum(ms)/len(ms)/1000:.1f}초 (최대 {max(ms)/1000:.1f}초)")
print(f"할 일 {hit_n}/{gold} · 뽑은 {predn}개 중 맞음 {correct_pred} · 후속 일정 {fu_ok}/{fu_n}")

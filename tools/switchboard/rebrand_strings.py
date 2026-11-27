import re, sys
path = sys.argv[1]
dry = len(sys.argv) > 2 and sys.argv[2] == "--dry"
s = open(path, encoding="utf-8").read()

# Strings about another device (which may run official Signal), other people's apps, or Signal the organization/service stay as they are.
KEEP = re.compile(r"primary device|on your phone|on that device|on the device|install Signal|needs to update Signal|latest versions? of Signal|Signal Support|pretending to be Signal|nonprofit|donation|Stripe|spam|registration code|MobileCoin|Signal PIN|Signal account|Signal Secure Backup|on Signal|to Signal|Signal user|Signal contact|Signal message|Signal call|Signal group|Signal Stor|key transparency|auto-verify|automatically verify|SMS messag", re.I)

RULES = [
    (r"\bSignal (needs|requires|has limited|has access|uses Wi-Fi|is updating|is unlocked|failed to update|was in the background|is safely secured|can only access)", r"Switchboard \1"),
    (r"\b(allow|Allow|Unlock|unlock|Lock|Open|open|Restart|restart|reinstall|Update|update) Signal\b", r"\1 Switchboard"),
    (r"\b(version|versions) of Signal\b", r"\1 of Switchboard"),
    (r"\bSignal version:", "Switchboard version:"),
    (r"\bin Signal > Settings", "in Switchboard > Settings"),
    (r"\bthe Signal app on this device", "the Switchboard app on this device"),
    (r"\bA new version of Signal\b", "A new version of Switchboard"),
]

changed = 0
def fix(m):
    global changed
    open_tag, body, close_tag = m.group(1), m.group(2), m.group(3)
    if KEEP.search(body):
        return m.group(0)
    new = body
    for pat, rep in RULES:
        new = re.sub(pat, rep, new)
    if new != body:
        changed += 1
        if dry:
            print("-", body[:160]); print("+", new[:160])
    return open_tag + new + close_tag

out = re.sub(r"(<(?:string|item)\b[^>]*>)(.*?)(</(?:string|item)>)", fix, s, flags=re.S)
if not dry:
    open(path, "w", encoding="utf-8").write(out)
print("changed", changed, file=sys.stderr)

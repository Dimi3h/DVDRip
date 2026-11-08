#!/usr/bin/env python3
"""Lists the localizable Dutch source strings used in the Swift sources (one per line, as
Localizable.strings keys). Interpolations are printed as {expr} so their type can be checked.
Usage: extract_strings.py [--check en.lproj/Localizable.strings]"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
CALLS = r'(?:Text|Button|Label|Section|Toggle|Picker|TextField|SecureField|LabeledContent|navigationTitle|Link|ContentUnavailableView|confirmationDialog|help|CommandMenu|Window|tabItem|ProgressView|String\(localized:|PageHeader\(title:|subtitle:|title:|prompt: Text)'
PAT = re.compile(CALLS + r'\s*\(?\s*"((?:[^"\\]|\\.)*)"')

def keys():
    out = {}
    for f in sorted((ROOT / "Sources").rglob("*.swift")):
        for m in PAT.finditer(f.read_text()):
            k = m.group(1)
            if not re.search(r'[A-Za-zÀ-ÿ]', k):
                continue
            out.setdefault(k, f.name)
    return out

if __name__ == "__main__":
    ks = keys()
    if len(sys.argv) > 2 and sys.argv[1] == "--check":
        text = pathlib.Path(sys.argv[2]).read_text()
        defined = set(re.findall(r'^"((?:[^"\\]|\\.)*)"\s*=', text, re.M))
        missing = [k for k in ks if re.sub(r'\\\((?:[^()]|\([^()]*\))*\)', '%', k) not in
                   {re.sub(r'%(lld|@|lf|d)', '%', d) for d in defined}]
        for k in missing:
            print("MISSING:", k, "(" + ks[k] + ")")
        sys.exit(1 if missing else 0)
    for k, f in ks.items():
        print(k)

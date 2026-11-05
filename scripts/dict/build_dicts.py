#!/usr/bin/env python3
"""
Builds the data files of the search dictionary packs (dict-zh, dict-ja).

Sources (download them first):
  Unihan.zip        https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
  JMdict_e.gz       http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz
  kanjidic2.xml.gz  http://www.edrdg.org/kanjidic/kanjidic2.xml.gz

Usage: scripts/dict/build_dicts.py <dir with the sources>

Output format (UTF-8, one entry per line, fields separated by tabs):
  line 1:  ulauncher-dict <TAB> <format version> <TAB> <language>
  zh:      C <TAB> <han character> <TAB> <reading> [<TAB> <reading> ...]   (toneless pinyin)
  ja:      W <TAB> <word> <TAB> <reading> [<TAB> <reading> ...]            (hiragana)
           K <TAB> <kanji> <TAB> <reading> [<TAB> <reading> ...]           (hiragana)
"""
import gzip
import os
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET
import zipfile

FORMAT_VERSION = 1
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def write(lang, lines):
    path = os.path.join(ROOT, f"dict-{lang}", "src", "main", "assets", "dict.txt")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"ulauncher-dict\t{FORMAT_VERSION}\t{lang}\n")
        for line in lines:
            f.write(line + "\n")
    print(f"{path}: {len(lines)} entries, {os.path.getsize(path)} bytes")


# ---------------------------------------------------------------- Chinese

def toneless(pinyin):
    """lüè -> ['lue', 'lve'] (both spellings of ü are common)"""
    s = unicodedata.normalize("NFD", pinyin.lower())
    s = "".join(c for c in s if unicodedata.category(c) != "Mn" or c == "̈")
    s = unicodedata.normalize("NFC", s)
    if "ü" in s:
        return [s.replace("ü", "u"), s.replace("ü", "v")]
    return [s]


def build_zh(src):
    readings = {}
    with zipfile.ZipFile(os.path.join(src, "Unihan.zip")) as z:
        with z.open("Unihan_Readings.txt") as f:
            for raw in f:
                line = raw.decode("utf-8").rstrip("\n")
                if line.startswith("#") or not line:
                    continue
                code, field, value = line.split("\t", 2)
                if field not in ("kMandarin", "kXHC1983", "kTGHZ2013"):
                    continue
                char = chr(int(code[2:], 16))
                values = []
                for item in value.split():
                    # kXHC1983 / kTGHZ2013: "0295.050:lè" or "1377.010,1377.020:yuè"
                    values.append(item.split(":")[-1])
                # the file is sorted by field, so kMandarin (the most common reading) comes first
                lst = readings.setdefault(char, [])
                for v in values:
                    for t in toneless(v):
                        if re.fullmatch(r"[a-z]+", t) and t not in lst:
                            lst.append(t)
    # only characters with several readings: the others are handled by ICU
    lines = [f"C\t{c}\t" + "\t".join(r) for c, r in sorted(readings.items()) if len(r) > 1]
    write("zh", lines)


# ---------------------------------------------------------------- Japanese

COMMON = {"news1", "ichi1", "spec1", "spec2", "gai1"}


def kata_to_hira(s):
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s)


def is_kana(s):
    return all("ぁ" <= c <= "ヿ" or c == "ー" for c in s)


def build_ja(src):
    words = {}
    with gzip.open(os.path.join(src, "JMdict_e.gz")) as f:
        for _, entry in ET.iterparse(f, events=("end",)):
            if entry.tag != "entry":
                continue
            kanji = []
            for k in entry.findall("k_ele"):
                keb = k.findtext("keb")
                pri = {p.text for p in k.findall("ke_pri")}
                if keb and pri & COMMON:
                    kanji.append(keb)
            if kanji:
                for r in entry.findall("r_ele"):
                    if r.find("re_nokanji") is not None:
                        continue
                    reb = kata_to_hira(r.findtext("reb") or "")
                    if not reb or not is_kana(reb):
                        continue
                    restr = {x.text for x in r.findall("re_restr")}
                    for keb in kanji:
                        if restr and keb not in restr:
                            continue
                        lst = words.setdefault(keb, [])
                        if reb not in lst and len(lst) < 3:
                            lst.append(reb)
            entry.clear()

    kanji_readings = {}
    with gzip.open(os.path.join(src, "kanjidic2.xml.gz")) as f:
        for _, ch in ET.iterparse(f, events=("end",)):
            if ch.tag != "character":
                continue
            literal = ch.findtext("literal")
            grade = ch.findtext("misc/grade")
            if literal and grade:  # jōyō and jinmeiyō kanji
                lst = []
                for r in ch.findall("reading_meaning/rmgroup/reading"):
                    if r.get("r_type") not in ("ja_on", "ja_kun"):
                        continue
                    v = kata_to_hira(r.text.split(".")[0].replace("-", ""))
                    if v and is_kana(v) and v not in lst:
                        lst.append(v)
                if lst:
                    kanji_readings[literal] = lst[:6]
            ch.clear()

    lines = [f"W\t{w}\t" + "\t".join(r) for w, r in sorted(words.items())]
    lines += [f"K\t{k}\t" + "\t".join(r) for k, r in sorted(kanji_readings.items())]
    write("ja", lines)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    build_zh(sys.argv[1])
    build_ja(sys.argv[1])

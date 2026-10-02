#!/usr/bin/env python3
"""Affiche les échecs et la sortie standard des tests JUnit (build/test-results)."""
import glob, re, sys, html
show_out = '--out' in sys.argv
for f in sorted(glob.glob('' + __import__("os").path.dirname(__import__("os").path.abspath(__file__)) + '/../../core/build/test-results/test/*.xml')):
    s = open(f).read()
    m = re.search(r'tests="(\d+)".*?failures="(\d+)"', s)
    print(f.split('/')[-1], m.group(0) if m else '')
    for m in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<failure message="([^"]*)"', s):
        print('  FAIL', m.group(1), '::', html.unescape(m.group(2))[:400])
    if show_out:
        o = re.search(r'<system-out><!\[CDATA\[(.*?)\]\]>', s, re.S)
        if o: print(o.group(1)[-4000:])

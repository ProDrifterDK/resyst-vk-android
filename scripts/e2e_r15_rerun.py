#!/usr/bin/env python3
"""✦ Resyst VK — run the r14 or r14b E2E on the 0.15.0 build with its output under build/e2e-r15/<r14|r14b>/, so the
earlier lanes' own evidence (build/e2e-r14/, build/e2e-r14b/) is not overwritten. Everything after the first argument
goes to the script unchanged.

Usage: scripts/e2e_r15_rerun.py r14  --serial SERIAL --label after [--only …]
       scripts/e2e_r15_rerun.py r14b --serial SERIAL [--only …]
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
ROOT = os.path.abspath(os.path.join(HERE, '..'))


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else ''
    import e2e_r14
    # the typing language comes from the store (Spanish), so host() never needs Android's keyboard picker, whose route
    # fails on the Android 17 test phone once an earlier English pass left the system subtype in English (both scripts
    # build their stores with e2e_r14.store)
    plain = e2e_r14.store

    def store(theme='lab', **extra):
        v = plain(theme, **extra)
        v.setdefault('phone.lang', 'ES')
        return v
    e2e_r14.store = store
    if which == 'r14':
        m = e2e_r14
        m.BASE = os.path.join(ROOT, 'build', 'e2e-r15', 'r14')
    elif which == 'r14b':
        import e2e_r14b as m
        m.BASE = os.path.join(ROOT, 'build', 'e2e-r15', 'r14b')
        m.OUT = m.BASE
    else:
        sys.exit('usage: e2e_r15_rerun.py r14|r14b <script args>')
    os.makedirs(m.BASE, exist_ok=True)
    sys.argv = [m.__file__] + sys.argv[2:]
    m.main()


if __name__ == '__main__':
    main()

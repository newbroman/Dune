#!/usr/bin/env python3
"""Exports Firefox's bookmarks from this computer as bookmarks.html, for Dune's Bookmarks › import file.

With Firefox Sync on, the phone's bookmarks are here too (the "Mobile Bookmarks" folder).

  python3 firefox_bookmarks.py [OUT]        default OUT: ~/Downloads/firefox-bookmarks.html

Reads every Firefox profile it finds (normal, snap and flatpak installs) from a copy of places.sqlite,
so Firefox can stay open. Prints what it wrote.
"""
import glob, html, os, shutil, sqlite3, sys, tempfile

HOME = os.path.expanduser("~")
PROFILES = [f"{HOME}/.mozilla/firefox", f"{HOME}/snap/firefox/common/.mozilla/firefox",
            f"{HOME}/.var/app/org.mozilla.firefox/.mozilla/firefox"]
ROOTS = {"menu________": "Bookmarks Menu", "toolbar_____": "Bookmarks Toolbar",
         "unfiled_____": "Other Bookmarks", "mobile______": "Mobile Bookmarks"}


def read(db):
    tmp = tempfile.mkdtemp()
    try:
        for ext in ("", "-wal"):
            if os.path.exists(db + ext):
                shutil.copy(db + ext, os.path.join(tmp, "places.sqlite" + ext))
        c = sqlite3.connect(os.path.join(tmp, "places.sqlite"))
        rows = c.execute("""SELECT b.id, b.parent, b.type, b.title, b.guid, b.dateAdded, p.url
                            FROM moz_bookmarks b LEFT JOIN moz_places p ON p.id = b.fk ORDER BY b.parent, b.position""").fetchall()
        c.close()
        return rows
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def write(rows, out):
    kids = {}
    for r in rows:
        kids.setdefault(r[1], []).append(r)
    n = 0
    lines = ["<!DOCTYPE NETSCAPE-Bookmark-file-1>", '<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">',
             "<TITLE>Bookmarks</TITLE>", "<H1>Bookmarks</H1>", "<DL><p>"]

    def walk(pid, depth):
        nonlocal n
        pad = "    " * depth
        for _id, _parent, typ, title, guid, added, url in kids.get(pid, []):
            if typ == 2:                      # folder
                if guid == "tags________":
                    continue
                name = ROOTS.get(guid, title or "")
                lines.append(f"{pad}<DT><H3>{html.escape(name)}</H3>")
                lines.append(f"{pad}<DL><p>")
                walk(_id, depth + 1)
                lines.append(f"{pad}</DL><p>")
            elif typ == 1 and url and url.startswith(("http://", "https://")):
                lines.append(f'{pad}<DT><A HREF="{html.escape(url)}" ADD_DATE="{(added or 0) // 1000000}">'
                             f"{html.escape(title or url)}</A>")
                n += 1

    root = next((r[0] for r in rows if r[4] == "root________"), 1)
    walk(root, 1)
    lines.append("</DL><p>")
    with open(out, "w", encoding="utf8") as f:
        f.write("\n".join(lines) + "\n")
    return n


def main():
    out = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Downloads/firefox-bookmarks.html")
    dbs = [p for d in PROFILES for p in glob.glob(f"{d}/*/places.sqlite")]
    if not dbs:
        sys.exit("No Firefox profile found under " + ", ".join(PROFILES))
    # the profile with the most bookmarks is the one in use
    best = max(((db, read(db)) for db in dbs), key=lambda x: sum(r[2] == 1 for r in x[1]))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    n = write(best[1], out)
    print(f"{n} bookmarks from {best[0]}\n→ {out}")


if __name__ == "__main__":
    main()

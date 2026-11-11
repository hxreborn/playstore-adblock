#!/usr/bin/env python3
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "settings" / "contributors"
SNAPSHOT = OUT / "contributors.json"
AVATAR_URLS = OUT / ".avatar-urls.json"
AVATAR_DIR = OUT / "avatars"
API = "https://api.github.com"
AVATAR_SIZE = 96
STATS_ATTEMPTS = 5


def repo_slug():
    slug = os.environ.get("GITHUB_REPOSITORY")
    if slug:
        return slug
    remote = subprocess.run(
        ["git", "-C", str(ROOT), "remote", "get-url", "origin"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    match = re.search(r"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$", remote)
    if not match:
        raise SystemExit(f"cannot read owner/repo from remote {remote}")
    return match.group(1)


def request(url):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "update-contributors"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30)


def get_json(path):
    with request(f"{API}/{path}") as response:
        return json.load(response)


def get_stats(slug):
    delay = 2
    for attempt in range(STATS_ATTEMPTS):
        with request(f"{API}/repos/{slug}/stats/contributors") as response:
            if response.status == 200:
                return json.load(response)
        if attempt < STATS_ATTEMPTS - 1:
            time.sleep(delay)
            delay *= 2
    raise SystemExit(f"stats/contributors still computing after {STATS_ATTEMPTS} attempts")


def is_bot(user):
    login = user["login"]
    return user.get("type") == "Bot" or login.endswith("-bot") or login.endswith("[bot]")


def avatar_download_url(avatar_url):
    separator = "&" if "?" in avatar_url else "?"
    return f"{avatar_url}{separator}s={AVATAR_SIZE}"


def entry(user, added, removed):
    return {
        "login": user["login"],
        "added": added,
        "removed": removed,
        "url": user["html_url"],
        "avatar_url": user["avatar_url"],
    }


def collect(slug):
    owner = get_json(f"repos/{slug}")["owner"]["login"]
    people = {}
    for stat in get_stats(slug):
        author = stat["author"]
        if author is None or is_bot(author):
            continue
        added = sum(week["a"] for week in stat["weeks"])
        removed = sum(week["d"] for week in stat["weeks"])
        people[author["login"]] = entry(author, added, removed)
    ordered = sorted(
        people.values(),
        key=lambda p: (p["login"] != owner, -p["added"], p["login"].lower()),
    )
    for person in ordered:
        if person["login"] == owner:
            person["owner"] = True
    return ordered


def encode_webp(image_bytes):
    try:
        from PIL import Image
    except ImportError:
        cwebp = shutil.which("cwebp")
        if cwebp is None:
            raise SystemExit("install Pillow or cwebp to encode avatars")
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "avatar"
            target = Path(tmp) / "avatar.webp"
            source.write_bytes(image_bytes)
            subprocess.run(
                [cwebp, "-quiet", "-lossless", "-resize", str(AVATAR_SIZE), str(AVATAR_SIZE), str(source), "-o", str(target)],
                check=True,
            )
            return target.read_bytes()
    buffer = io.BytesIO()
    image = Image.open(io.BytesIO(image_bytes)).convert("RGBA")
    if image.width != AVATAR_SIZE:
        image = image.resize((AVATAR_SIZE, AVATAR_SIZE), Image.LANCZOS)
    image.save(buffer, "WEBP", lossless=True)
    return buffer.getvalue()


def fetch_avatars(people, known_urls):
    fresh = {}
    for person in people:
        login = person["login"]
        target = AVATAR_DIR / f"{login}.webp"
        if known_urls.get(login) == person["avatar_url"] and target.exists():
            continue
        with request(avatar_download_url(person["avatar_url"])) as response:
            fresh[login] = encode_webp(response.read())
    return fresh


def render(people):
    rows = []
    for person in people:
        row = {
            "login": person["login"],
            "added": person["added"],
            "removed": person["removed"],
            "url": person["url"],
            "avatar": f"avatars/{person['login']}.webp",
        }
        if person.get("owner"):
            row["owner"] = True
        rows.append(row)
    return json.dumps(rows, indent=2, ensure_ascii=False) + "\n"


def write_if_changed(path, content):
    data = content.encode() if isinstance(content, str) else content
    if path.exists() and path.read_bytes() == data:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def main():
    slug = repo_slug()
    people = collect(slug)
    if not people:
        raise SystemExit("refusing to write an empty contributor list")
    known_urls = json.loads(AVATAR_URLS.read_text()) if AVATAR_URLS.exists() else {}
    fresh = fetch_avatars(people, known_urls)

    for login, data in fresh.items():
        write_if_changed(AVATAR_DIR / f"{login}.webp", data)
    listed = {person["login"] for person in people}
    if AVATAR_DIR.exists():
        for avatar in AVATAR_DIR.glob("*.webp"):
            if avatar.stem not in listed:
                avatar.unlink()
    avatar_urls = {person["login"]: person["avatar_url"] for person in people}
    write_if_changed(AVATAR_URLS, json.dumps(avatar_urls, indent=2, sort_keys=True) + "\n")
    write_if_changed(SNAPSHOT, render(people))
    print(f"{len(people)} contributors, {len(fresh)} avatars downloaded")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.URLError as error:
        sys.exit(f"network error: {error}")

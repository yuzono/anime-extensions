import gzip
import html
import json
import shutil
import sys
from pathlib import Path

import index_pb2

REMOTE_REPO: Path = Path.cwd()
LOCAL_REPO: Path = REMOTE_REPO.parent.joinpath(sys.argv[2])

# --- Store metadata baked into index.pb — fill in before first publish -----
INDEX_NAME = "Yūzōnō"
BADGE_LABEL = "Yū"
# SHA-256 fingerprint of the APK signing cert: lowercase hex, no separators.
#   keytool -list -v -keystore signingkey.jks | grep 'SHA256:'
SIGNING_KEY = "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"
CONTACT_WEBSITE = "https://yuzono.github.io"
CONTACT_DISCORD = "https://discord.gg/85MZhUX688"

APK_BASE_URL = "https://github.com/yuzono/anime-repo/raw/repo/apk"
ICON_BASE_URL = "https://github.com/yuzono/anime-repo/raw/repo/icon"
# -----------------------------------------------------------------------------

CONTENT_WARNING_BY_NSFW = {
    0: index_pb2.CONTENT_WARNING_SAFE,
    1: index_pb2.CONTENT_WARNING_NSFW,
}

to_delete: list[str] = json.loads(sys.argv[1])

for module in to_delete:
    apk_name = f"aniyomi-{module}-v*.*.apk"
    icon_name = f"eu.kanade.tachiyomi.animeextension.{module}.png"
    for file in REMOTE_REPO.joinpath("apk").glob(apk_name):
        print(file.name)
        file.unlink(missing_ok=True)
    for file in REMOTE_REPO.joinpath("icon").glob(icon_name):
        print(file.name)
        file.unlink(missing_ok=True)

shutil.copytree(src=LOCAL_REPO.joinpath("apk"), dst=REMOTE_REPO.joinpath("apk"), dirs_exist_ok = True)
shutil.copytree(src=LOCAL_REPO.joinpath("icon"), dst=REMOTE_REPO.joinpath("icon"), dirs_exist_ok = True)

with REMOTE_REPO.joinpath("index.json").open(encoding="utf-8") as remote_index_file:
    remote_index = json.load(remote_index_file)

with LOCAL_REPO.joinpath("index.min.json").open(encoding="utf-8") as local_index_file:
    local_index = json.load(local_index_file)

index = [
    item for item in remote_index
    if not any(item["pkg"].endswith(f".{module}") for module in to_delete)
]
index.extend(local_index)
index.sort(key=lambda x: x["pkg"])

with REMOTE_REPO.joinpath("index.json").open("w", encoding="utf-8") as index_file:
    json.dump(index, index_file, ensure_ascii=False, indent=2)

# --- index.pb ---------------------------------------------------------------
# Derived from the merged index; serialized BEFORE versionId is stripped below.
# Lossy by design: this schema has no extension-level lang and no per-source
# versionId — index.json remains the full-fidelity format. The old pb is never
# read back: APK/icon URLs are derived from pkg + the version-stamped
# filename, and old APKs persist in repo/apk, so unchanged entries always
# resolve to still-valid URLs.
index_proto = index_pb2.Index(
    name=INDEX_NAME,
    badgeLabel=BADGE_LABEL,
    signingKey=SIGNING_KEY,
    extensionList=index_pb2.ExtensionList(),  # selects the oneof case
)
contact = index_proto.contact
contact.website = CONTACT_WEBSITE
if CONTACT_DISCORD:
    contact.discord = CONTACT_DISCORD

for item in index:
    ext = index_proto.extensionList.extensions.add()
    ext.name = item["name"]
    ext.packageName = item["pkg"]
    ext.resources.apkUrl = f"{APK_BASE_URL}/{item['apk']}"
    ext.resources.iconUrl = f"{ICON_BASE_URL}/{item['pkg']}.png"
    ext.versionCode = item["code"]
    ext.versionName = item["version"]
    ext.contentWarning = CONTENT_WARNING_BY_NSFW.get(
        item["nsfw"], index_pb2.CONTENT_WARNING_UNSPECIFIED
    )
    # isTorrent: Inspector output carries no torrent flag; left unset.
    # extensionLib: not tracked by this pipeline; left unset.
    for source in item["sources"]:
        src = ext.sources.add()
        src.id = int(source["id"])  # Inspector emits ids as decimal strings
        src.name = source["name"]
        src.language = source["lang"]
        src.homeUrl = source["baseUrl"]
        # mirrorUrls / message: not present in Inspector output

with REMOTE_REPO.joinpath("index.pb").open("wb") as index_pb_file:
    # mtime=0 + deterministic serialization -> byte-stable output for
    # unchanged data, so no-op runs produce no spurious git diffs
    index_pb_file.write(
        gzip.compress(index_proto.SerializeToString(deterministic=True), mtime=0)
    )
# -----------------------------------------------------------------------------

for item in index:
    for source in item["sources"]:
        source.pop("versionId", None)

with REMOTE_REPO.joinpath("index.min.json").open("w", encoding="utf-8") as index_min_file:
    json.dump(index, index_min_file, ensure_ascii=False, separators=(",", ":"))

with REMOTE_REPO.joinpath("index.html").open("w", encoding="utf-8") as index_html_file:
    index_html_file.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
    for entry in index:
        apk_escaped = 'apk/' + html.escape(entry["apk"])
        name_escaped = html.escape(entry["name"])
        index_html_file.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    index_html_file.write('</pre>\n</body>\n</html>\n')

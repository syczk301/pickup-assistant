"""Inspect a Beta, then promote the exact APK after GitHub environment approval."""
import argparse, hashlib, io, json, os, pathlib, re, subprocess, tempfile, urllib.request, zipfile, base64

REPO = "syczk301/pickup-assistant"
SIGNER = "f04b2f154f599cd60f9ccd3583763154092c7445ef4d8ec9cad1e4bd29da2c65"
ROOT = pathlib.Path(__file__).resolve().parent

def api(path, method="GET", data=None, raw=None, content_type=None):
    url = path if path.startswith("https://uploads.github.com/") else "https://api.github.com/repos/" + REPO + "/" + path
    headers = {"User-Agent": "PickupRelease", "Accept": "application/vnd.github+json", "Authorization": "Bearer " + os.environ["GITHUB_TOKEN"], "X-GitHub-Api-Version": "2022-11-28"}
    if data is not None:
        raw = json.dumps(data).encode(); headers["Content-Type"] = "application/json"
    if content_type: headers["Content-Type"] = content_type
    with urllib.request.urlopen(urllib.request.Request(url, data=raw, headers=headers, method=method), timeout=120) as r:
        body = r.read(); return json.loads(body) if body else None

def download(asset, tag):
    url = asset["browser_download_url"]
    if not url.startswith("https://github.com/" + REPO + "/releases/download/" + tag + "/"):
        raise ValueError("Unexpected asset URL")
    if asset["size"] > 200 * 1024 * 1024: raise ValueError("Asset too large")
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "PickupRelease"}), timeout=120) as r: body = r.read(200 * 1024 * 1024 + 1)
    if len(body) != asset["size"]: raise ValueError("Asset size mismatch")
    return body

def sdk_files():
    sdk = pathlib.Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", str(ROOT.parent / ".tools/sdk"))))
    dirs = sorted((sdk / "build-tools").glob("*"), reverse=True) + [sdk / "android-15"]
    signer = next((p / "lib/apksigner.jar" for p in dirs if (p / "lib/apksigner.jar").exists()), None)
    aapt = next((p / ("aapt.exe" if os.name == "nt" else "aapt") for p in dirs if (p / ("aapt.exe" if os.name == "nt" else "aapt")).exists()), None)
    if signer is None or aapt is None: raise ValueError("Install Android SDK Build Tools 35.0.0")
    return signer, aapt

def verify_apk(meta, apk):
    apk_name = "pickup-assistant-" + meta["versionName"] + ".apk"
    signer, aapt = sdk_files()
    with tempfile.TemporaryDirectory() as tmp:
        path = pathlib.Path(tmp) / apk_name; path.write_bytes(apk)
        cert = subprocess.check_output(["java", "-jar", str(signer), "verify", "--print-certs", str(path)], text=True)
        hashes = re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", cert)
        if hashes != [SIGNER]: raise ValueError("Release signing certificate mismatch")
        badging = subprocess.check_output([str(aapt), "dump", "badging", str(path)], text=True, encoding="utf-8")
        pkg = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
        minimum = re.search(r"sdkVersion:'(\d+)'", badging)
        if not pkg or pkg.groups() != (meta["packageName"], str(meta["versionCode"]), meta["versionName"]) or not minimum or int(minimum[1]) != meta["minSdk"]: raise ValueError("APK manifest mismatch")

def inspect(tag):
    match = re.fullmatch(r"v(\d+\.\d+\.\d+)-beta\.(\d+)", tag)
    if not match: raise ValueError("Select a versioned Beta tag")
    release = api("releases/tags/" + tag)
    if release["draft"] or not release["prerelease"]: raise ValueError("Candidate must be a published prerelease")
    assets = {a["name"]: a for a in release["assets"]}
    meta = json.loads(download(assets["update-beta.json"], tag))
    if meta.get("schemaVersion") != 1 or meta.get("channel") != "beta" or meta.get("packageName") != "com.local.pickup": raise ValueError("Invalid Beta metadata")
    if meta["versionName"] != match[1] or not isinstance(meta["versionCode"], int) or meta["versionCode"] <= 20: raise ValueError("Invalid version")
    apk_name = "pickup-assistant-" + meta["versionName"] + ".apk"
    if meta["apkUrl"] != assets[apk_name]["browser_download_url"]: raise ValueError("APK URL mismatch")
    apk = download(assets[apk_name], tag)
    if len(apk) != meta["sizeBytes"] or hashlib.sha256(apk).hexdigest() != meta["sha256"]: raise ValueError("APK digest mismatch")
    verify_apk(meta, apk)
    source_name = "pickup-assistant-source-" + meta["versionName"] + ".zip"
    source = download(assets[source_name], tag)
    with zipfile.ZipFile(io.BytesIO(source)) as z:
        for name in z.namelist():
            if name.startswith("/") or ".." in pathlib.PurePosixPath(name).parts or name.endswith((".jks", ".keystore", ".apk", ".java")): raise ValueError("Unsafe source archive")
    head = api("git/ref/heads/main")["object"]["sha"]
    return dict(tag=tag, releaseId=release["id"], sourceCommit=release["target_commitish"], mainCommit=head, metadata=meta, sourceSha256=hashlib.sha256(source).hexdigest(), sourceName=source_name, apkName=apk_name)

def promote(candidate):
    # Called only by the job guarded by the production environment.
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RELEASE_ENVIRONMENT") != "production": raise ValueError("Promotion must run in the approval-gated production job")
    current = inspect(candidate["tag"])
    if current != candidate: raise ValueError("Candidate or branch changed after validation; inspect again")
    head = current["mainCommit"]
    stable_file = api("contents/update.json?ref=" + head)
    stable = json.loads(base64.b64decode(stable_file["content"]))
    if stable["versionCode"] >= current["metadata"]["versionCode"]: raise ValueError("Stable version must advance")
    beta_file = api("contents/update-beta.json?ref=" + head)
    if json.loads(base64.b64decode(beta_file["content"])) != current["metadata"]: raise ValueError("Candidate is not current Beta")
    tag = "v" + current["metadata"]["versionName"]
    meta = dict(current["metadata"], channel="stable", apkUrl="https://github.com/" + REPO + "/releases/download/" + tag + "/" + current["apkName"])
    releases = api("releases?per_page=100")
    release = next((r for r in releases if r["tag_name"] == tag), None)
    if release and (release["prerelease"] or release["target_commitish"] != current["sourceCommit"]): raise ValueError("Existing stable release differs from reviewed candidate")
    if release is None:
        release = api("releases", "POST", dict(tag_name=tag, target_commitish=current["sourceCommit"], name="拾件簿 " + meta["versionName"], body=meta["releaseNotes"], draft=True, prerelease=False))
    beta = api("releases/tags/" + current["tag"]); assets = {a["name"]: a for a in beta["assets"]}
    source = download(assets[current["sourceName"]], current["tag"])
    archive = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as src, zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist(): dst.writestr(info, json.dumps(meta, ensure_ascii=False, indent=2).encode() if info.filename == "update.json" else src.read(info.filename))
    payloads = [(current["apkName"], download(assets[current["apkName"]], current["tag"]), "application/vnd.android.package-archive"), (current["sourceName"], archive.getvalue(), "application/zip"), ("update.json", (json.dumps(meta, ensure_ascii=False, indent=2) + "\n").encode(), "application/json")]
    existing = {a["name"]: a for a in release["assets"]}
    upload = release["upload_url"].split("{")[0]
    import urllib.parse
    if urllib.parse.urlparse(upload).hostname != "uploads.github.com": raise ValueError("Unexpected upload host")
    for name, body, content_type in payloads:
        if name in existing:
            if download(existing[name], tag) != body: raise ValueError("Draft asset differs; inspect draft before retry")
        elif release["draft"]: api(upload + "?name=" + urllib.parse.quote(name), "POST", raw=body, content_type=content_type)
        else: raise ValueError("Published release missing approved asset")
    if api("git/ref/heads/main")["object"]["sha"] != head: raise ValueError("Main changed; retry validation")
    # Publish verified assets first, then advertise the version to existing clients.
    api("releases/" + str(release["id"]), "PATCH", dict(draft=False, prerelease=False, make_latest="true"))
    api("contents/update.json", "PUT", dict(message="Promote reviewed " + current["tag"] + " to stable", content=base64.b64encode(payloads[-1][1]).decode(), sha=stable_file["sha"], branch="main"))
    print("Promoted reviewed APK without rebuilding: " + meta["apkUrl"])

def publish_beta(tag):
    import urllib.parse
    match = re.fullmatch(r"v(\d+\.\d+\.\d+)-beta\.(\d+)", tag)
    if not match: raise ValueError("Use vX.Y.Z-beta.N")
    version = match[1]
    apk = ROOT.parent / "deliverables" / ("pickup-assistant-" + version + ".apk")
    import xml.etree.ElementTree as ET
    manifest = ET.parse(ROOT / "AndroidManifest.xml").getroot()
    ns = "{http://schemas.android.com/apk/res/android}"
    if manifest.get(ns + "versionName") != version: raise ValueError("Build version does not match tag")
    code = int(manifest.get(ns + "versionCode"))
    all_releases = api("releases?per_page=100")
    if any(r["tag_name"] == tag for r in all_releases): raise ValueError("Tag already exists; inspect existing release")
    stable_response = api("contents/update.json?ref=main")
    stable_bytes = base64.b64decode(stable_response["content"])
    stable = json.loads(stable_bytes)
    beta_response = api("contents/update-beta.json?ref=main") if any(r["prerelease"] for r in all_releases) else None
    previous_code = json.loads(base64.b64decode(beta_response["content"]))["versionCode"] if beta_response else 20
    if code <= max(20, stable["versionCode"], previous_code): raise ValueError("versionCode must advance across both channels")
    body = apk.read_bytes()
    notes_path = ROOT.parent / "deliverables" / "beta-release-notes.txt"
    if not notes_path.exists(): raise ValueError("Write deliverables/beta-release-notes.txt before publishing")
    notes = notes_path.read_text(encoding="utf-8").strip()
    meta = dict(schemaVersion=1, packageName="com.local.pickup", channel="beta", versionCode=code, versionName=version, minSdk=26, apkUrl="https://github.com/" + REPO + "/releases/download/" + tag + "/" + apk.name, sizeBytes=len(body), sha256=hashlib.sha256(body).hexdigest(), releaseNotes=notes)
    verify_apk(meta, body)
    metadata = (json.dumps(meta, ensure_ascii=False, indent=2) + "\n").encode()
    files = {}
    for path in ROOT.rglob("*"):
        if not path.is_file(): continue
        rel = path.relative_to(ROOT)
        if rel.parts[0] in ("build", ".gradle", "__pycache__") or "__pycache__" in rel.parts or path.suffix in (".jks", ".keystore", ".apk", ".idsig", ".pyc", ".java"): continue
        if path.suffix == ".md" and rel.as_posix() != "README.md": continue
        name = rel.as_posix() if rel.as_posix() in ("README.md", "LICENSE", ".gitignore") else "app/" + rel.as_posix()
        files[name] = path.read_bytes()
    files["README.md"] = files["README.md"].replace(b"replica\\", b"app\\")
    files["update.json"] = stable_bytes; files["update-beta.json"] = metadata
    workflow = ROOT.parent / ".github/workflows/promote-stable.yml"
    files[".github/workflows/promote-stable.yml"] = workflow.read_bytes()
    archive = ROOT.parent / "deliverables" / ("pickup-assistant-source-" + version + "-beta." + match[2] + ".zip")
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for name, content in files.items(): z.writestr(name, content)
    head = api("git/ref/heads/main")["object"]["sha"]
    base_tree = api("git/commits/" + head)["tree"]["sha"]
    old_tree = api("git/trees/" + base_tree + "?recursive=1")["tree"]
    entries = []
    for name, content in files.items():
        digest = hashlib.sha1(b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest()
        if any(x["path"] == name and x["sha"] == digest for x in old_tree): continue
        blob = api("git/blobs", "POST", dict(content=base64.b64encode(content).decode(), encoding="base64"))
        entries.append(dict(path=name, mode="100644", type="blob", sha=blob["sha"]))
    for x in old_tree:
        if x["type"] == "blob" and x["path"].startswith("app/") and x["path"] not in files: entries.append(dict(path=x["path"], mode="100644", type="blob", sha=None))
    tree = api("git/trees", "POST", dict(base_tree=base_tree, tree=entries))
    commit = api("git/commits", "POST", dict(message="Publish test channel " + tag, tree=tree["sha"], parents=[head]))["sha"]
    release = api("releases", "POST", dict(tag_name=tag, target_commitish=commit, name="拾件簿 " + version + " Beta " + match[2], body=notes, draft=True, prerelease=True, make_latest="false"))
    upload = release["upload_url"].split("{")[0]
    if urllib.parse.urlparse(upload).hostname != "uploads.github.com": raise ValueError("Invalid upload host")
    source_name = "pickup-assistant-source-" + version + ".zip"
    for name, content, kind in [(apk.name, body, "application/vnd.android.package-archive"), (source_name, archive.read_bytes(), "application/zip"), ("update-beta.json", metadata, "application/json")]: api(upload + "?name=" + urllib.parse.quote(name), "POST", raw=content, content_type=kind)
    if api("git/ref/heads/main")["object"]["sha"] != head: raise ValueError("Main changed during publishing")
    api("git/refs/heads/main", "PATCH", dict(sha=commit, force=False))
    api("releases/" + str(release["id"]), "PATCH", dict(draft=False, prerelease=True, make_latest="false"))
    (ROOT.parent / "update-beta.json").write_bytes(metadata)
    (ROOT.parent / "deliverables/update-beta.json").write_bytes(metadata)
    print("Published Beta only: https://github.com/" + REPO + "/releases/tag/" + tag)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("command", choices=["inspect", "promote", "beta"]); parser.add_argument("value"); args = parser.parse_args()
    if args.command == "inspect": print(json.dumps(inspect(args.value), ensure_ascii=False, indent=2))
    elif args.command == "beta": publish_beta(args.value)
    else: promote(json.loads(pathlib.Path(args.value).read_text(encoding="utf-8")))

"""Capture the New folder and verify its count, badges and order on Android."""

from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.strokedot.bluevlc"
FIXTURE = Path("application/cast-tests/src/androidTest/assets/cast-fixture.mp4")
OUTPUT = Path("new-downloads-screenshots")


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True).strip()


def wait_for_ui(predicate):
    for _ in range(20):
        adb("shell", "uiautomator", "dump", "/sdcard/new-downloads-ui.xml")
        xml = adb("shell", "cat", "/sdcard/new-downloads-ui.xml")
        nodes = list(ET.fromstring(xml).iter("node"))
        match = predicate(nodes)
        if match is not None:
            return match
        time.sleep(1)
    raise AssertionError(f"Expected screen did not appear. Last view tree: {xml}")


def tap(node):
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def screenshot(name):
    with (OUTPUT / name).open("wb") as output:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], check=True, stdout=output)


def seed_video(filename):
    target = f"/sdcard/Download/{filename}"
    adb("push", str(FIXTURE), target)
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "--receiver-include-background", "-d", f"file://{target.replace('/sdcard/', '/storage/emulated/0/')}")
    for _ in range(20):
        rows = adb("shell", "content", "query", "--uri", "content://media/external/video/media",
                   "--projection", "_display_name")
        if f"_display_name={filename}" in rows:
            return
        time.sleep(1)
    raise AssertionError(f"MediaStore did not index {filename}")


def configure_app():
    # Set only the test device's first-run preferences so the signed APK opens directly.
    adb("root")
    adb("wait-for-device")
    adb("shell", "am", "force-stop", PACKAGE)
    package_dir = f"/data/user/0/{PACKAGE}"
    prefs_dir = f"{package_dir}/shared_prefs"
    prefs_path = f"{prefs_dir}/{PACKAGE}_preferences.xml"
    owner = adb("shell", "stat", "-c", "%u:%g", package_dir)
    with tempfile.TemporaryDirectory() as tmp:
        prefs = Path(tmp) / "prefs.xml"
        prefs.write_text(
            '<map><boolean name="app_onboarding_done" value="true"/>'
            '<string name="app_theme">2</string>'
            '<int name="ml_scan" value="0"/>'
            '<boolean name="browse_network" value="false"/></map>'
        )
        adb("push", str(prefs), "/data/local/tmp/vlc-prefs.xml")
    adb("shell", f"mkdir -p {prefs_dir} && cp /data/local/tmp/vlc-prefs.xml {prefs_path}"
        f" && chown -R {owner} {prefs_dir} && restorecon -RF {prefs_dir}")
    adb("shell", "cmd", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")


def folder_ready(nodes):
    folder = next((node for node in nodes if node.get("text") == "New"), None)
    # The browser's standard description uses an ImageSpan for the file icon.
    count_visible = any(
        node.get("text", "").startswith("2 *§*") or "2 media files" in node.get("content-desc", "")
        for node in nodes
    )
    return folder if count_visible else None


def recent_videos_ready(nodes):
    titles = [node.get("text") for node in nodes if node.get("resource-id", "").endswith(":id/title")]
    demo_titles = [title for title in titles if title in {"Latest.mp4", "Earlier.mp4", "Latest", "Earlier"}]
    badges = sum(node.get("text") == "NEW" for node in nodes)
    if len(demo_titles) == 2 and badges == 2:
        assert demo_titles[0].startswith("Latest"), demo_titles
        return True
    return None


def main():
    OUTPUT.mkdir(exist_ok=True)
    adb("install", "-r", "-g", sys.argv[1])
    configure_app()
    adb("shell", "mkdir", "-p", "/sdcard/Download")
    seed_video("Earlier.mp4")
    time.sleep(1.1)
    seed_video("Latest.mp4")
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/org.videolan.vlc.StartActivity",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER")
    tap(wait_for_ui(lambda nodes: next(
        (node for node in nodes if node.get("resource-id", "").endswith(":id/nav_directories")), None
    )))
    folder = wait_for_ui(folder_ready)
    screenshot("VLC-Plus-Browse.png")
    tap(folder)
    wait_for_ui(recent_videos_ready)
    screenshot("VLC-Plus-New.png")


if __name__ == "__main__":
    main()

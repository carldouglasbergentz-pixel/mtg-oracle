"""Stage a Forge build of a worktree in the release layout mtg-oracle builds against.

    python stage_for_mtg_oracle.py <forge worktree> <target dir>

The worktree must have run `mvn -pl forge-gui-desktop -am package -DskipTests`.
The target gets what forge-installer's app bundle would hold and the app reads:
the desktop jar-with-dependencies, res/ without the parts the app never copies,
and res/cardsfolder/cardsfolder.zip zipped as the installer zips it.
build.txt records the commit, so the app's build can say which Forge it holds.
"""
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

SKIPPED = {"adventure", "music", "sound", "skins", "cardsfolder"}


def main(worktree: Path, target: Path) -> None:
    jars = list((worktree / "forge-gui-desktop" / "target").glob("forge-gui-desktop-*-jar-with-dependencies.jar"))
    if len(jars) != 1:
        sys.exit(f"expected one desktop jar-with-dependencies in {worktree}, found {len(jars)}")
    commit = subprocess.run(["git", "-C", str(worktree), "log", "-1", "--format=%H %cI"],
                            capture_output=True, text=True, check=True).stdout.strip()
    dirty = subprocess.run(["git", "-C", str(worktree), "status", "--porcelain", "--untracked-files=no"],
                           capture_output=True, text=True, check=True).stdout.strip()
    if dirty:
        sys.exit(f"{worktree} has uncommitted changes; stage a committed state only")

    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    shutil.copy2(jars[0], target / jars[0].name)

    res = worktree / "forge-gui" / "res"
    for entry in res.iterdir():
        if entry.name in SKIPPED:
            continue
        if entry.is_dir():
            shutil.copytree(entry, target / "res" / entry.name)
        else:
            (target / "res").mkdir(exist_ok=True)
            shutil.copy2(entry, target / "res" / entry.name)

    cards = res / "cardsfolder"
    zip_path = target / "res" / "cardsfolder" / "cardsfolder.zip"
    zip_path.parent.mkdir(parents=True)
    count = 0
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        for card in sorted(cards.rglob("*")):
            if card.is_file():
                archive.write(card, card.relative_to(cards).as_posix())
                count += 1

    (target / "build.txt").write_text(f"{jars[0].name}\n{commit}\n", encoding="utf-8")
    print(f"staged {jars[0].name} at {commit} into {target}: {count} card scripts")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(Path(sys.argv[1]), Path(sys.argv[2]))

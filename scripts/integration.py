#!/usr/bin/env python3
"""Exercise the installed local plugin in a throwaway consumer project."""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(project, *args, succeeds=True, diagnostic=None):
    wrapper = "kotlin.bat" if __import__("os").name == "nt" else "./kotlin"
    result = subprocess.run([wrapper, *args], cwd=project, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
    if (result.returncode == 0) != succeeds:
        raise AssertionError(f"Unexpected exit {result.returncode}: {' '.join(args)}\n{result.stdout}")
    if diagnostic and diagnostic not in result.stdout:
        raise AssertionError(f"Missing diagnostic {diagnostic!r}:\n{result.stdout}")
    print(f"PASS: {' '.join(args)} ({'success' if succeeds else 'expected failure'})", flush=True)
    return result.stdout


def prepare(project, plugin):
    for name in ("kotlin", "kotlin.bat", "project.yaml"):
        shutil.copy2(ROOT / name, project / name)
    shutil.copytree(ROOT / "plugins", project / "plugins")
    shutil.copytree(ROOT / "templates", project / "templates")
    shutil.copytree(ROOT / "example" / "src", project / "example" / "src")
    shutil.copy2(ROOT / "example" / "module.yaml", project / "example" / "module.yaml")


(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="dokka-integration-", dir=ROOT / "build") as temp:
    project = Path(temp)
    prepare(project, "dokka")
    run(project, "do", "dokkaHtml", "-m", "example")
    indexes = list((project / "build").glob("**/html/index.html"))
    assert len(indexes) == 1, f"Expected one documentation site, got {indexes}"
    html = "\n".join(p.read_text() for p in indexes[0].parent.rglob("*.html"))
    assert "Greeter" in html and "Returns a friendly greeting" in html
    assert "implementationDetail" not in html, "Default public-only visibility was ignored"
    module = project / "example/module.yaml"
    module.write_text(module.read_text() + "\nplugins:\n  dokka:\n    reportUndocumented: true\n    failOnWarning: true\n")
    source = project / "example/src/Greeter.kt"
    source.write_text(source.read_text() + "\npublic fun undocumentedFunction(): String = \"missing KDoc\"\n")
    run(project, "do", "dokkaHtml", "-m", "example", succeeds=False,
        diagnostic="Dokka failed with exit code")
print("Dokka integration passed: HTML/KDoc rendering, public-only visibility, documentation-warning failure.")

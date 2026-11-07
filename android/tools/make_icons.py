"""Draw the Akkhara app icon (white អ on a blue rounded square) and save Android PNGs.

    .venv/bin/python android/tools/make_icons.py

Uses headless Chrome (like akkhara/reporting/architecture.py) because only a browser
engine shapes Khmer letters correctly. Writes:
    res/drawable-nodpi/ic_launcher_foreground.png   adaptive-icon foreground (432 px = 108 dp @ xxxhdpi)
    res/drawable-nodpi/logo_mark.png                 logo shown inside the app (full square)
"""
import subprocess
from pathlib import Path

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
RES = Path(__file__).resolve().parents[1] / "app/src/main/res"
KHMER = "Khmer Sangam MN, Khmer MN, Noto Sans Khmer, sans-serif"  # no inner quotes: it goes into style='...'


def render(html: str, size: int, out: Path) -> None:
    tmp = out.with_suffix(".html")
    tmp.write_text(f"<!doctype html><html><head><meta charset='utf-8'><style>html,body{{margin:0;padding:0;"
                   f"background:transparent}}</style></head><body>{html}</body></html>", encoding="utf-8")
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--default-background-color=00000000",
                    f"--window-size={size},{size}", f"--screenshot={out}", tmp.as_uri()], check=True, capture_output=True)
    tmp.unlink()


def letter(size: int, scale: float) -> str:
    # អ, slightly above centre (Khmer letters have a tall top), with a soft shadow
    return (f"<div style='position:absolute;inset:0;display:flex;align-items:center;justify-content:center;"
            f"font-family:{KHMER};font-weight:700;color:#fff;font-size:{int(size * scale)}px;"
            f"padding-top:{int(size * 0.07)}px;text-shadow:0 {size // 90}px {size // 30}px rgba(0,0,0,.18)'>អ</div>")


def main() -> None:
    out = RES / "drawable-nodpi"
    out.mkdir(parents=True, exist_ok=True)
    # 1) adaptive foreground: transparent, letter inside the 66/108 safe zone
    s = 432
    render(f"<div style='position:relative;width:{s}px;height:{s}px'>{letter(s, 0.42)}</div>", s,
           out / "ic_launcher_foreground.png")
    # 2) logo mark: full blue rounded square with the letter (used in the app header)
    s = 256
    render(f"<div style='position:relative;width:{s}px;height:{s}px;border-radius:{int(s * .24)}px;"
           f"background:linear-gradient(145deg,#3B8BEB,#1F5FB8)'>{letter(s, 0.62)}</div>", s, out / "logo_mark.png")
    print("icons →", out)


if __name__ == "__main__":
    main()

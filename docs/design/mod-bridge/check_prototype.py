"""Interaction-only checks; no Android, plugin or real-origin persistence claims.

Requires an existing Python/Playwright/Chromium environment. Example:
    python check_prototype.py --chromium /usr/bin/chromium --output /tmp/deck-proof

Uses set_content instead of navigation. No browser policy is disabled. Browser
storage is unavailable on the opaque page; its graceful fallback is tested.
"""
from pathlib import Path
import argparse
import json
import shutil
import tempfile
from playwright.sync_api import sync_playwright


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--chromium', default=shutil.which('chromium'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    html = (root / 'prototype.html').read_text(encoding='utf-8')
    out = args.output or Path(tempfile.mkdtemp(prefix='chengshu-deck-proof-'))
    out.mkdir(parents=True, exist_ok=True)
    results, errors, external = [], [], []

    def check(value, message='assertion failed'):
        assert value, message

    def record(name, fn):
        fn()
        results.append({'name': name, 'result': 'PASS'})

    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=args.chromium, headless=True)
        version = browser.version
        page = browser.new_page(viewport={'width': 1440, 'height': 1120}, device_scale_factor=1)
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.on('request', lambda r: external.append(r.url) if r.url.startswith(('http://', 'https://')) else None)
        page.set_content(html, wait_until='load')
        record('Initial valid recipe', lambda: check(page.locator('#run').is_enabled()))
        page.screenshot(path=str(out / 'desktop-workbench.png'), full_page=True)
        page.locator('#run').click()
        page.wait_for_function("!document.querySelector('#run').disabled", timeout=10000)
        record('Default simulated result, no real delivery claim', lambda: check(page.locator('.result').count() == 1 and '不是实际交接回执' in page.locator('.result').inner_text()))
        page.locator('[data-preset=notes]').click()
        record('Photo OCR example route', lambda: check('识字' in page.locator('#board').inner_text() and page.locator('#run').is_enabled()))
        page.locator('[data-card=input]').click()
        page.locator('[data-pick-id=voice]').click()
        record('Incompatible source blocks running', lambda: check(page.locator('#run').is_disabled() and '声音' in page.locator('#compatibility').inner_text()))
        page.locator('#repair').click()
        record('Visible repair retains usable chain', lambda: check(page.locator('#run').is_enabled() and '转成文字' in page.locator('#board').inner_text()))
        page.locator('#undo').click()
        record('Undo repair restores incompatible state', lambda: check(page.locator('#run').is_disabled()))
        page.locator('[data-preset=split]').click()
        page.locator('#fail-toggle').check()
        page.locator('#run').click()
        page.wait_for_function("document.querySelectorAll('.result').length===2 && !document.querySelector('#run').disabled", timeout=10000)
        first = page.locator('.result').nth(0).inner_text()
        record('One output fails without removing the successful output', lambda: check(page.locator('.result.failed').count() == 1))
        page.screenshot(path=str(out / 'partial-success.png'), full_page=True)
        page.locator('[data-retry]').click()
        page.wait_for_function("!document.querySelector('#run').disabled", timeout=5000)
        record('Retry one output preserves other result', lambda: check(page.locator('.result.failed').count() == 0 and first == page.locator('.result').nth(0).inner_text()))
        with page.expect_download() as download:
            page.locator('[data-download]').first.click()
        record('Actual sample text export', lambda: check(download.value.suggested_filename.endswith('.txt')))
        page.locator('#fail-toggle').uncheck()
        page.locator('#new-recipe').click()
        record('Zero intermediate step is valid', lambda: check(page.locator('.card.action').count() == 0 and page.locator('#run').is_enabled()))
        page.locator('#add-step').click()
        page.locator('[data-pick-id=clean]').click()
        record('Incompatible action explains and does not insert', lambda: check(page.locator('#dialog').is_visible() and page.locator('.card.action').count() == 0))
        page.locator('[data-pick-id=translate]').click()
        record('External capability disclosure before insertion', lambda: check(page.locator('#approve-demo').is_visible() and page.locator('.card.action').count() == 0))
        page.locator('#approve-demo').click()
        record('Explicit demo-only insertion', lambda: check(page.locator('.card.action').count() == 1))
        page.locator('#save').click()
        page.locator('#recipe-name').fill('我的 <测试> 配方')
        page.locator('#confirm-save').click()
        record('Session recipe in pocket escapes user title', lambda: check(page.locator('#pocket-grid').inner_text().count('我的 <测试> 配方') == 1 and page.locator('#pocket-grid 测试').count() == 0))
        record('Blocked storage is clearly reported', lambda: check(page.locator('#storage-warning').is_visible()))
        page.locator('[data-pocket-delete]').first.click()
        page.locator('[data-view=workshop]').click()
        page.screenshot(path=str(out / 'desktop-workshop.png'), full_page=True)
        page.locator('[data-market="2"]').click()
        record('Community provenance and external destination disclosure', lambda: check('虚构' in page.locator('#dialog-body').inner_text() and '外部翻译' in page.locator('#dialog-body').inner_text()))
        page.locator('#trial-market').click()
        record('Community recipe remix opens editable table', lambda: check(page.locator('.card.action').count() == 2 and page.locator('#table-view').is_visible()))
        page.locator('#run').click()
        page.locator('#cancel-run').click()
        page.wait_for_timeout(1700)
        record('Cancel prevents late results', lambda: check('停止' in page.locator('#run-panel').inner_text() and page.locator('.result').count() == 0))
        page.locator('#quiet-toggle').check()
        record('Explicit reduced motion setting', lambda: check(page.evaluate("document.body.classList.contains('quiet')")))
        page.locator('[data-preset=read]').click()
        page.locator('[data-card=input]').click()
        page.keyboard.press('Escape')
        record('Keyboard Escape dismisses modal', lambda: check(not page.locator('#dialog').is_visible()))
        mobile = browser.new_page(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, device_scale_factor=1)
        mobile.on('pageerror', lambda e: errors.append(str(e)))
        mobile.on('request', lambda r: external.append(r.url) if r.url.startswith(('http://', 'https://')) else None)
        mobile.set_content(html, wait_until='load')
        mobile.screenshot(path=str(out / 'mobile-workbench.png'), full_page=True)
        record('390px no page horizontal overflow', lambda: check(mobile.evaluate('document.documentElement.scrollWidth <= window.innerWidth')))
        mobile.locator('[data-preset=voice]').click()
        mobile.locator('[data-move="1"][data-dir="-1"]').click()
        record('Click-based reorder, mismatch clearly visible', lambda: check(mobile.locator('#run').is_disabled()))
        mobile.locator('#undo').click()
        mobile.locator('#save').click()
        mobile.locator('#confirm-save').click()
        mobile.screenshot(path=str(out / 'mobile-pocket.png'), full_page=True)
        mobile.set_viewport_size({'width': 320, 'height': 720})
        mobile.locator('[data-view=table]').click()
        record('320px no page horizontal overflow', lambda: check(mobile.evaluate('document.documentElement.scrollWidth <= window.innerWidth')))
        mobile.emulate_media(reduced_motion='reduce')
        record('System reduced motion disables card animation', lambda: check(mobile.locator('.card').first.evaluate('el=>getComputedStyle(el).animationName') == 'none'))
        record('No page JavaScript errors', lambda: check(not errors, str(errors)))
        record('No external network requests', lambda: check(not external, str(external)))
        browser.close()
    report = {'browser': version, 'method': 'set_content; real-origin persistence NOT_RUN', 'checks': results, 'errors': errors, 'externalRequests': external}
    (out / 'validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'passed': len(results), 'output': str(out)}, ensure_ascii=False))


if __name__ == '__main__':
    main()

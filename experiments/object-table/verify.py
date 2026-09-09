"""Interaction checks for the isolated card-table experiment.
Requires Python + playwright and a Chromium executable (CHROMIUM_BIN or /usr/bin/chromium).
Renders local HTML via set_content: this runner disallows file/localhost navigation.
Storage-denial fallback is tested; real browser restart persistence is NOT tested here.
"""
from pathlib import Path
from playwright.sync_api import sync_playwright
import os, json, base64, io, zipfile, xml.etree.ElementTree as ET
ROOT=Path(__file__).parent
HTML=(ROOT/'index.html').read_text()
RESULT=[]
def check(name, condition):
    assert condition,name
    RESULT.append({'test':name,'result':'PASS'})
def kinds(page): return page.evaluate('ObjectTable.getState().cards.map(c=>c.kind)')
def drag(page,a,b):
    aa=page.locator(a).first.bounding_box();bb=page.locator(b).first.bounding_box()
    page.mouse.move(aa['x']+aa['width']*.50,aa['y']+aa['height']*.48)
    page.mouse.down();page.mouse.move(bb['x']+bb['width']*.45,bb['y']+bb['height']*.45,steps=24)
    page.mouse.up();page.wait_for_timeout(160)
with sync_playwright() as p:
    b=p.chromium.launch(executable_path=os.getenv('CHROMIUM_BIN','/usr/bin/chromium'),args=['--no-sandbox'])
    ctx=b.new_context(viewport={'width':1440,'height':940},accept_downloads=True)
    page=ctx.new_page();errors=[];remote=[]
    page.on('pageerror',lambda e:errors.append(str(e)))
    page.on('request',lambda r: remote.append(r.url) if r.url.startswith(('http:','https:')) else None)
    page.set_content(HTML);page.wait_for_timeout(200)
    check('initial playable table, not pre-generated outputs',kinds(page)==['html','image'])
    drag(page,'[data-tool=bind]','[data-kind=html]')
    check('incompatible drop preserves material',kinds(page)==['html','image'])
    drag(page,'[data-tool=clean]','[data-kind=html]')
    check('real pointer drag cleans HTML and preserves original',kinds(page)==['html','image','text'])
    text=page.evaluate("ObjectTable.getState().cards.find(c=>c.kind==='text').text")
    check('actual extracted text excludes sample navigation/advertising','河水' in text and '推广内容' not in text and '返回顶部' not in text)
    drag(page,'[data-tool=bind]','[data-kind=text]')
    check('binding creates a book with the input text',page.evaluate("ObjectTable.getState().cards.find(c=>c.kind==='book').chapters[0].text")==text)
    drag(page,'[data-tool=palette]','[data-kind=image]')
    palette=page.evaluate("ObjectTable.getState().cards.find(c=>c.kind==='palette').colors")
    check('image pixels yield actual dominant colors',len(palette)==5 and '#d7bb8b' in palette)
    drag(page,'[data-kind=palette]','[data-kind=book]')
    check('palette plus book creates a new cover without consuming either',kinds(page).count('book')==2 and len(kinds(page))==6)
    latest=page.evaluate("ObjectTable.getState().cards.filter(c=>c.kind==='book').at(-1).id")
    page.evaluate('(id)=>ObjectTable.inspect(id)',latest)
    with page.expect_download() as download:
        page.locator('#take-file').click()
    path=ROOT/'verified-output.epub';download.value.save_as(path)
    z=zipfile.ZipFile(path)
    check('downloaded EPUB has valid CRC and stored mimetype first',z.testzip() is None and z.namelist()[0]=='mimetype' and z.getinfo('mimetype').compress_type==0 and z.read('mimetype')==b'application/epub+zip')
    for n in z.namelist():
        if n.endswith(('.xhtml','.xml','.opf','.ncx')):ET.fromstring(z.read(n))
    check('all EPUB XML parses and cover uses sampled color',palette[0] in z.read('OEBPS/cover.xhtml').decode() and '河水' in z.read('OEBPS/ch0.xhtml').decode())
    page.locator('.close').click();page.keyboard.press('Control+z')
    check('undo removes only last generated item',len(kinds(page))==5)
    page.keyboard.press('Control+Shift+z');check('redo restores generated cover',len(kinds(page))==6)
    first_book=page.evaluate("ObjectTable.getState().cards.find(c=>c.kind==='book').id")
    page.evaluate('(id)=>ObjectTable.inspect(id)',first_book)
    page.locator('#keep-recipe').click();page.locator('#recipe-name').fill('真的再做一次');page.locator('#save-recipe').click()
    check('saved recipe contains real operators',page.evaluate("ObjectTable.getState().recipes[0].ops")==['clean','bind'])
    # Armed recipe: the same pointer path executes on a new input, no hard-coded demo title.
    page.evaluate("ObjectTable.importFile(new File(['<article><h1>另一篇文章</h1><p>这是全新的正文，星星落在屋顶。</p></article>'],'another.html',{type:'text/html'}))")
    new_id=page.evaluate("ObjectTable.getState().cards.at(-1).id")
    page.locator(f'[data-id="{new_id}"]').click()
    check('recipe runs on new content',page.evaluate("ObjectTable.getState().cards.at(-1).title")=='另一篇文章' and '星星' in page.evaluate("ObjectTable.getState().cards.at(-1).chapters[0].text"))
    check('unknown code cannot be installed as recipe',not page.evaluate("ObjectTable.validRecipe({schema:1,name:'bad',input:'html',ops:['eval']})"))
    check('known but incompatible recipe is rejected',not page.evaluate("ObjectTable.validRecipe({schema:1,name:'bad',input:'image',ops:['bind']})"))
    check('storage denial is visibly surfaced','未保存' in page.locator('#save-status').inner_text())
    check('no external requests or page exceptions',not remote and not errors)
    mobile=b.new_page(viewport={'width':393,'height':852},is_mobile=True,has_touch=True)
    mobile.set_content(HTML);mobile.wait_for_timeout(170)
    bounds=mobile.locator('.hand .card').evaluate_all('(els)=>els.map(e=>({x:e.getBoundingClientRect().left,right:e.getBoundingClientRect().right}))')
    check('phone hand stays inside viewport',all(x['x']>=0 and x['right']<=393 for x in bounds))
    # Genuine browser touch events; not direct calls to the conversion function.
    cdp=mobile.context.new_cdp_session(mobile)
    a=mobile.locator('[data-tool=clean]').bounding_box();bb=mobile.locator('[data-kind=html]').bounding_box()
    x=a['x']+a['width']/2;y=a['y']+a['height']/2;tx=bb['x']+bb['width']*.4;ty=bb['y']+bb['height']*.4
    cdp.send('Input.dispatchTouchEvent',{'type':'touchStart','touchPoints':[{'x':x,'y':y}]})
    for i in range(1,20):cdp.send('Input.dispatchTouchEvent',{'type':'touchMove','touchPoints':[{'x':x+(tx-x)*i/19,'y':y+(ty-y)*i/19}]})
    cdp.send('Input.dispatchTouchEvent',{'type':'touchEnd','touchPoints':[]});mobile.wait_for_timeout(200)
    check('phone touch drag creates actual text',kinds(mobile).count('text')==1)
    mobile.locator('[data-kind=text]').tap();mobile.locator('[data-tool=bind]').tap();mobile.wait_for_timeout(180)
    check('tap alternative works without dragging',kinds(mobile).count('book')==1)
    mobile.screenshot(path=str(ROOT/'mobile-after.png'))
    # Cancelled pointer leaves no floating ghost or phantom output.
    aa=page.locator('[data-tool=clean]').bounding_box();page.mouse.move(aa['x']+aa['width']/2,aa['y']+aa['height']/2);page.mouse.down();page.mouse.move(700,530,steps=4)
    page.locator('[data-tool=clean]').dispatch_event('pointercancel',{'pointerId':1});page.mouse.up()
    check('pointer cancellation cleans up ghost',page.locator('.lifted').count()==0)
    reduced=b.new_page(viewport={'width':320,'height':740},reduced_motion='reduce')
    reduced.set_content(HTML);reduced.locator('[data-kind=html]').click();reduced.locator('[data-tool=clean]').click();reduced.wait_for_timeout(150)
    check('reduced motion retains conversion',kinds(reduced).count('text')==1)
    check('no horizontal page overflow at 320px',reduced.evaluate('document.documentElement.scrollWidth<=innerWidth'))
    b.close()
(ROOT/'verification.json').write_text(json.dumps({'checks':RESULT,'limitations':['HTML rendered with Playwright set_content because file and localhost navigation are blocked in this test environment.','Actual restart persistence not tested; storage-denial behavior tested.','EPUB ZIP/XML/content validated; EPUBCheck and real reading apps not run.','Chromium desktop/touch emulation tested; no physical phone validation.']},ensure_ascii=False,indent=2))
print(json.dumps({'passed':len(RESULT),'checks':RESULT},ensure_ascii=False,indent=2))

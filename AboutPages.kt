package org.nirobo.browser

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The hidden internal pages, reachable by typing about:<name> in the address bar.
 *
 * Each page is a self-contained HTML document built here and loaded with
 * loadDataWithBaseURL, so it works with no network at all. Anything that needs
 * the phone or the network goes through [Bridge], which is the only JavaScript
 * interface exposed to these pages.
 */
private val ENGINE_LOCK = Any()

internal object AboutPages {

    /** Every page the address bar understands. */
    val NAMES = listOf(
        "blank", "home", "internet", "web", "phone", "bookmarks", "history",
        "downloads", "settings", "shield", "news", "version", "nirobo",
        "update", "charge", "battery", "sandbox", "calc", "mail"
    )

    fun isAbout(url: String): Boolean = url.startsWith("about:")

    fun name(url: String): String =
        url.removePrefix("about:").trim().lowercase().ifEmpty { "blank" }

    /** The shared look: Nirobo's deep green, light and dark. */
    private fun css(dark: Boolean): String {
        val bg = if (dark) "#0B1512" else "#F6FAF7"
        val card = if (dark) "#132420" else "#FFFFFF"
        val ink = if (dark) "#E8F1EC" else "#0E3B2C"
        val dim = if (dark) "#8FA79C" else "#5C7A6C"
        val line = if (dark) "#1E3A32" else "#DCE7E1"
        val accent = if (dark) "#3FBF8A" else "#0B5D3B"
        return """
        *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
        html,body{margin:0;padding:0;background:$bg;color:$ink;
          font-family:-apple-system,'Roboto','Segoe UI',system-ui,sans-serif;
          font-size:15px;line-height:1.55}
        body{padding:18px 16px 40px;max-width:760px;margin:0 auto}
        h1{font-size:22px;margin:0 0 4px;letter-spacing:-.3px}
        h2{font-size:15px;margin:26px 0 10px;text-transform:uppercase;
          letter-spacing:.8px;color:$dim;font-weight:600}
        p{margin:8px 0;color:$dim}
        a{color:$accent;text-decoration:none}
        .sub{color:$dim;font-size:13px;margin:0 0 18px}
        .card{background:$card;border:1px solid $line;border-radius:14px;
          padding:14px 16px;margin:10px 0}
        .row{display:flex;justify-content:space-between;gap:12px;padding:9px 0;
          border-bottom:1px solid $line;font-size:14px}
        .row:last-child{border-bottom:0}
        .row .k{color:$dim;flex:0 0 auto}
        .row .v{text-align:right;word-break:break-word;font-variant-numeric:tabular-nums}
        .big{font-size:34px;font-weight:700;letter-spacing:-1px;
          font-variant-numeric:tabular-nums}
        .unit{font-size:14px;color:$dim;font-weight:400;margin-left:4px}
        .grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
        .tile{background:$card;border:1px solid $line;border-radius:14px;padding:14px}
        .tile .lbl{font-size:12px;color:$dim;text-transform:uppercase;letter-spacing:.6px}
        .tile .num{font-size:24px;font-weight:700;margin-top:4px;
          font-variant-numeric:tabular-nums}
        button{font:inherit;font-weight:600;border:0;border-radius:11px;
          padding:12px 18px;background:$accent;color:#fff;cursor:pointer}
        button:active{opacity:.8}
        button.ghost{background:transparent;color:$accent;border:1px solid $line}
        button:disabled{opacity:.45}
        .bar{height:8px;border-radius:99px;background:$line;overflow:hidden;margin:10px 0}
        .bar>i{display:block;height:100%;width:0;background:$accent;
          transition:width .25s ease}
        .ok{color:#1E9E63}.warn{color:#C98A16}.bad{color:#C4453B}
        .pill{display:inline-block;font-size:11px;font-weight:700;padding:3px 9px;
          border-radius:99px;letter-spacing:.4px;text-transform:uppercase}
        .pill.ok{background:rgba(30,158,99,.14)}
        .pill.warn{background:rgba(201,138,22,.16)}
        .pill.bad{background:rgba(196,69,59,.14)}
        .item{padding:11px 0;border-bottom:1px solid $line}
        .item:last-child{border-bottom:0}
        .item .t{font-size:14px;font-weight:600;margin-bottom:2px;
          overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .item .u{font-size:12px;color:$dim;overflow:hidden;
          text-overflow:ellipsis;white-space:nowrap}
        .empty{text-align:center;color:$dim;padding:40px 10px;font-size:14px}
        .sw{position:relative;width:46px;height:27px;border-radius:99px;
          background:$line;transition:background .18s;flex:0 0 auto;cursor:pointer}
        .sw.on{background:$accent}
        .sw>i{position:absolute;top:3px;left:3px;width:21px;height:21px;
          border-radius:50%;background:#fff;transition:left .18s}
        .sw.on>i{left:22px}
        .mono{font-family:ui-monospace,'Roboto Mono',monospace;font-size:12px;
          word-break:break-all}
        .hint{font-size:12px;color:$dim;margin-top:6px}
        """
    }

    private fun page(title: String, dark: Boolean, body: String, script: String = ""): String {
        val bg = if (dark) "#0B1512" else "#F6FAF7"
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
        <meta name="color-scheme" content="${if (dark) "dark" else "light"}">
        <title>$title</title><style>${css(dark)}</style></head>
        <body>$body<script>function encq(s){return encodeURIComponent(s).replace(/'/g,'%27').replace(/\(/g,'%28').replace(/\)/g,'%29')}$script</script></body></html>"""
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    // =====================================================================
    //  about:blank
    // =====================================================================

    private fun blank(dark: Boolean): String {
        val bg = if (dark) "#0B1512" else "#FFFFFF"
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>about:blank</title>
        <style>html,body{margin:0;padding:0;height:100%;background:$bg}</style>
        </head><body></body></html>"""
    }

    // =====================================================================
    //  about:internet  —  speed test
    // =====================================================================

    private fun internet(dark: Boolean): String {
        val body = """
        <h1>Internet speed</h1>
        <p class="sub">Measures this phone's real download and upload speed.</p>

        <div class="grid">
          <div class="tile"><div class="lbl">Download</div>
            <div class="num" id="dn">—</div></div>
          <div class="tile"><div class="lbl">Upload</div>
            <div class="num" id="up">—</div></div>
        </div>

        <div class="card">
          <div class="row"><span class="k">Latency</span><span class="v" id="ping">—</span></div>
          <div class="row"><span class="k">Jitter</span><span class="v" id="jit">—</span></div>
          <div class="row"><span class="k">Server</span><span class="v" id="srv">Cloudflare</span></div>
          <div class="row"><span class="k">Connection</span><span class="v" id="conn">—</span></div>
        </div>

        <div class="bar"><i id="prog"></i></div>
        <p class="hint" id="status">Tap Start to begin. Uses about 25 MB of data.</p>
        <p><button id="go" onclick="start()">Start test</button></p>

        <h2>What the numbers mean</h2>
        <div class="card">
          <div class="row"><span class="k">Under 1 Mbps</span><span class="v bad">Very slow</span></div>
          <div class="row"><span class="k">1 – 5 Mbps</span><span class="v warn">Basic browsing</span></div>
          <div class="row"><span class="k">5 – 25 Mbps</span><span class="v ok">Good</span></div>
          <div class="row"><span class="k">Over 25 Mbps</span><span class="v ok">Fast</span></div>
        </div>
        """
        val script = """
        var busy=false;
        function set(id,v){document.getElementById(id).textContent=v}
        function prog(p){document.getElementById('prog').style.width=p+'%'}
        function status(s){document.getElementById('status').textContent=s}
        function start(){
          if(busy)return; busy=true;
          document.getElementById('go').disabled=true;
          set('dn','—');set('up','—');set('ping','—');set('jit','—');prog(0);
          status('Measuring latency, then download and upload…');
          setTimeout(function(){
            var raw;
            try{ raw=Bridge.speedTest() }catch(e){ status('Test failed: '+e); busy=false;
              document.getElementById('go').disabled=false; return }
            busy=false;
            document.getElementById('go').disabled=false;
            var r; try{r=JSON.parse(raw)}catch(e){status('Test failed: '+raw);return}
            if(r.error){status('Test failed: '+r.error);return}
            set('dn',r.down.toFixed(1)+' Mbps');
            set('up',r.up.toFixed(1)+' Mbps');
            set('ping',r.ping+' ms');
            set('jit',r.jitter+' ms');
            set('conn',r.conn||'—');
            prog(100);
            status('Done. '+r.bytes+' MB transferred.');
          },60);
        }
        try{ set('conn',Bridge.connection()) }catch(e){}
        """
        return page("Internet speed", dark, body, script)
    }

    // =====================================================================
    //  about:web  —  SEO and site health check
    // =====================================================================

    private fun web(dark: Boolean): String {
        val body = """
        <h1>Website check</h1>
        <p class="sub">SEO, speed, security and mobile-friendliness for any address.</p>

        <div class="card">
          <input id="u" placeholder="example.com" autocapitalize="none"
            autocorrect="off" spellcheck="false"
            style="width:100%;font:inherit;padding:11px 13px;border-radius:10px;
            border:1px solid #DCE7E1;background:transparent;color:inherit;margin-bottom:10px">
          <button id="go" onclick="check()">Check site</button>
        </div>

        <div id="out"></div>
        """
        val script = """
        function esc(s){return String(s).replace(/[&<>"]/g,function(c){
          return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
        function check(){
          var raw=document.getElementById('u').value.trim();
          if(!raw){return}
          var out=document.getElementById('out');
          out.innerHTML='<div class="empty">Checking '+esc(raw)+'…</div>';
          document.getElementById('go').disabled=true;
          setTimeout(function(){
            var json;
            try{ json=Bridge.checkSite(raw) }catch(e){
              document.getElementById('go').disabled=false;
              out.innerHTML='<div class="empty">Could not run the check.</div>';return }
            document.getElementById('go').disabled=false;
            var r; try{r=JSON.parse(json)}catch(e){
              out.innerHTML='<div class="empty">Could not read the result.</div>';return}
            if(r.error){out.innerHTML='<div class="empty">'+esc(r.error)+'</div>';return}
            render(r);
          },60);
        }
        function row(k,v,cls){return '<div class="row"><span class="k">'+k+
          '</span><span class="v '+(cls||'')+'">'+v+'</span></div>'}
        function render(r){
          var h='';
          h+='<div class="card"><div class="row"><span class="k">Address</span>'+
             '<span class="v mono">'+esc(r.finalUrl)+'</span></div>'+
             row('Status',r.status+(r.status==200?' <span class="pill ok">OK</span>':
               ' <span class="pill bad">Problem</span>'))+
             row('Response time',r.ms+' ms',r.ms<600?'ok':(r.ms<1500?'warn':'bad'))+
             row('Page size',(r.bytes/1024).toFixed(0)+' KB',
               r.bytes<500000?'ok':(r.bytes<2000000?'warn':'bad'))+
             row('HTTPS',r.https?'<span class="pill ok">Yes</span>':
               '<span class="pill bad">No</span>')+
             row('Redirects',r.redirects)+'</div>';

          h+='<h2>SEO</h2><div class="card">';
          h+=row('Title',r.title?esc(r.title)+' <span class="hint">('+r.title.length+')</span>':
            '<span class="bad">missing</span>',r.title?(r.title.length>=30&&r.title.length<=60?'ok':'warn'):'bad');
          h+=row('Description',r.desc?esc(r.desc.substring(0,90))+(r.desc.length>90?'…':'')+
            ' <span class="hint">('+r.desc.length+')</span>':'<span class="bad">missing</span>',
            r.desc?(r.desc.length>=70&&r.desc.length<=160?'ok':'warn'):'bad');
          h+=row('H1 headings',r.h1+(r.h1==1?' <span class="pill ok">Good</span>':
            (r.h1==0?' <span class="pill bad">None</span>':' <span class="pill warn">Too many</span>')),
            r.h1==1?'ok':'warn');
          h+=row('Canonical',r.canonical?'<span class="pill ok">Set</span>':
            '<span class="pill warn">Missing</span>',r.canonical?'ok':'warn');
          h+=row('Language',r.lang?esc(r.lang):'<span class="warn">not set</span>',r.lang?'ok':'warn');
          h+=row('robots.txt',r.robots?'<span class="pill ok">Found</span>':
            '<span class="pill warn">Missing</span>',r.robots?'ok':'warn');
          h+=row('sitemap.xml',r.sitemap?'<span class="pill ok">Found</span>':
            '<span class="pill warn">Missing</span>',r.sitemap?'ok':'warn');
          h+='</div>';

          h+='<h2>Social &amp; mobile</h2><div class="card">';
          h+=row('og:title',r.ogTitle?'<span class="pill ok">Set</span>':
            '<span class="pill warn">Missing</span>',r.ogTitle?'ok':'warn');
          h+=row('og:image',r.ogImage?'<span class="pill ok">Set</span>':
            '<span class="pill warn">Missing</span>',r.ogImage?'ok':'warn');
          h+=row('Viewport',r.viewport?'<span class="pill ok">Set</span>':
            '<span class="pill bad">Missing</span>',r.viewport?'ok':'bad');
          h+=row('Images without alt',r.imgNoAlt+(r.imgNoAlt==0?' <span class="pill ok">All good</span>':
            ' <span class="pill warn">Fix</span>'),r.imgNoAlt==0?'ok':'warn');
          h+='</div>';

          h+='<h2>Security headers</h2><div class="card">';
          h+=row('Strict-Transport-Security',r.hsts?'<span class="pill ok">Set</span>':
            '<span class="pill warn">Missing</span>',r.hsts?'ok':'warn');
          h+=row('X-Content-Type-Options',r.nosniff?'<span class="pill ok">Set</span>':
            '<span class="pill warn">Missing</span>',r.nosniff?'ok':'warn');
          h+=row('X-Frame-Options',r.frame?'<span class="pill ok">Set</span>':
            '<span class="pill warn">Missing</span>',r.frame?'ok':'warn');
          h+=row('Content-Security-Policy',r.csp?'<span class="pill ok">Set</span>':
            '<span class="pill warn">Missing</span>',r.csp?'ok':'warn');
          h+='</div>';

          h+='<h2>Score</h2><div class="card"><div class="big">'+r.score+
             '<span class="unit">/ 100</span></div><div class="bar"><i style="width:'+
             r.score+'%"></i></div><p class="hint">'+esc(r.verdict)+'</p></div>';
          document.getElementById('out').innerHTML=h;
        }
        """
        return page("Website check", dark, body, script)
    }

    // =====================================================================
    //  about:phone  —  battery and device
    // =====================================================================

    private fun phone(dark: Boolean): String {
        val body = """
        <h1>This phone</h1>
        <p class="sub">Battery, charging and hardware, read live from the device.</p>

        <div class="card" style="text-align:center">
          <div class="big" id="pct">—<span class="unit">%</span></div>
          <div class="bar" style="height:12px"><i id="fill"></i></div>
          <div id="state" class="hint">Reading…</div>
        </div>

        <div class="grid">
          <div class="tile"><div class="lbl">Charging</div>
            <div class="num" id="chg" style="font-size:17px">—</div></div>
          <div class="tile"><div class="lbl">Temperature</div>
            <div class="num" id="temp" style="font-size:17px">—</div></div>
          <div class="tile"><div class="lbl">Voltage</div>
            <div class="num" id="volt" style="font-size:17px">—</div></div>
          <div class="tile"><div class="lbl">Health</div>
            <div class="num" id="health" style="font-size:17px">—</div></div>
        </div>

        <h2>Battery</h2>
        <div class="card" id="batt"></div>

        <h2>Device</h2>
        <div class="card" id="dev"></div>

        <h2>Storage</h2>
        <div class="card" id="disk"></div>

        <p class="hint">Battery figures come from Android's own battery service. Some
        phones report fewer values than others — a dash means the phone does not
        expose that number.</p>
        """
        val script = """
        function row(k,v){return '<div class="row"><span class="k">'+k+
          '</span><span class="v">'+v+'</span></div>'}
        function refresh(){
          (function(json){
            var r; try{r=JSON.parse(json)}catch(e){return}
            document.getElementById('pct').innerHTML=r.level+'<span class="unit">%</span>';
            document.getElementById('fill').style.width=r.level+'%';
            document.getElementById('fill').style.background=
              r.level<=15?'#C4453B':(r.level<=35?'#C98A16':'#0B5D3B');
            document.getElementById('state').textContent=r.stateText;
            document.getElementById('chg').textContent=r.charging?'Yes':'No';
            document.getElementById('temp').textContent=r.tempC?r.tempC.toFixed(1)+' °C':'—';
            document.getElementById('volt').textContent=r.voltV?r.voltV.toFixed(2)+' V':'—';
            document.getElementById('health').textContent=r.healthText||'—';

            var b='';
            b+=row('Status',r.stateText);
            b+=row('Plugged in',r.plugText);
            b+=row('Technology',r.tech||'—');
            b+=row('Capacity',r.chargeCounter?r.chargeCounter+' mAh':'—');
            b+=row('Current now',r.currentNow?r.currentNow+' mA':'—');
            b+=row('Power',r.watts?r.watts.toFixed(2)+' W':'—');
            b+=row('Time to full',r.timeToFull||'—');
            b+=row('Time remaining',r.timeLeft||'—');
            document.getElementById('batt').innerHTML=b;

            var d='';
            d+=row('Model',r.model);
            d+=row('Manufacturer',r.manufacturer);
            d+=row('Android',r.android);
            d+=row('SDK',r.sdk);
            d+=row('CPU',r.cpu);
            d+=row('Cores',r.cores);
            d+=row('Screen',r.screen);
            d+=row('Density',r.density);
            d+=row('RAM',r.ram);
            document.getElementById('dev').innerHTML=d;

            var s='';
            s+=row('Total',r.diskTotal);
            s+=row('Free',r.diskFree);
            s+=row('Used',r.diskUsed);
            document.getElementById('disk').innerHTML=s;
          })(Bridge.phoneInfo());
        }
        refresh();
        setInterval(refresh,5000);
        """
        return page("This phone", dark, body, script)
    }

    // =====================================================================
    //  about:bookmarks / history / downloads
    // =====================================================================

    private fun bookmarks(dark: Boolean): String {
        val body = """
        <h1>Bookmarks</h1>
        <p class="sub">Saved pages. Tap to open, or remove with the ✕.</p>
        <div class="card" id="list"></div>
        """
        val script = """
        function esc(s){return String(s).replace(/[&<>"]/g,function(c){
          return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
        function draw(){
          (function(json){
            var a; try{a=JSON.parse(json)}catch(e){a=[]}
            var el=document.getElementById('list');
            if(!a.length){el.innerHTML='<div class="empty">No bookmarks yet.<br>'+
              'Open a page and tap ☆ in the menu.</div>';return}
            var h='';
            for(var i=0;i<a.length;i++){
              h+='<div class="item" style="display:flex;gap:10px;align-items:center">'+
                 '<div style="flex:1;min-width:0" onclick="open_(\''+
                 encq(a[i].u)+'\')">'+
                 '<div class="t">'+esc(a[i].t)+'</div>'+
                 '<div class="u">'+esc(a[i].u)+'</div></div>'+
                 '<div style="padding:8px;color:#C4453B;font-size:18px" onclick="del(\''+
                 encq(a[i].u)+'\')">✕</div></div>';
            }
            el.innerHTML=h;
          })(Bridge.bookmarks());
        }
        function open_(u){Bridge.open(decodeURIComponent(u))}
        function del(u){Bridge.removeBookmark(decodeURIComponent(u));draw()}
        draw();
        """
        return page("Bookmarks", dark, body, script)
    }

    private fun history(dark: Boolean): String {
        val body = """
        <h1>History</h1>
        <p class="sub">Pages you visited in the normal browser.</p>
        <p><button class="ghost" onclick="clearAll()">Clear all history</button></p>
        <div class="card" id="list"></div>
        """
        val script = """
        function esc(s){return String(s).replace(/[&<>"]/g,function(c){
          return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
        function when(ts){
          var d=new Date(ts),n=new Date(),diff=(n-d)/1000;
          if(diff<60)return 'just now';
          if(diff<3600)return Math.floor(diff/60)+' min ago';
          if(diff<86400)return Math.floor(diff/3600)+' h ago';
          if(diff<604800)return Math.floor(diff/86400)+' d ago';
          return d.toLocaleDateString();
        }
        function draw(){
          (function(json){
            var a; try{a=JSON.parse(json)}catch(e){a=[]}
            var el=document.getElementById('list');
            if(!a.length){el.innerHTML='<div class="empty">Nothing here yet.</div>';return}
            var h='';
            for(var i=0;i<a.length;i++){
              h+='<div class="item" onclick="open_(\''+encq(a[i].u)+'\')">'+
                 '<div class="t">'+esc(a[i].t)+'</div>'+
                 '<div class="u">'+esc(a[i].u)+' · '+when(a[i].ts)+'</div></div>';
            }
            el.innerHTML=h;
          })(Bridge.history());
        }
        function open_(u){Bridge.open(decodeURIComponent(u))}
        function clearAll(){Bridge.clearHistory();draw()}
        draw();
        """
        return page("History", dark, body, script)
    }

    private fun downloads(dark: Boolean): String {
        val body = """
        <style>
        .pbar{height:6px;border-radius:3px;background:rgba(127,127,127,.25);overflow:hidden;margin:8px 0}
        .pbar i{display:block;height:100%;width:0;background:#1E9E63}
        .in{width:100%;font:inherit;padding:12px;border-radius:11px;border:1px solid rgba(127,127,127,.4);background:transparent;color:inherit;box-sizing:border-box}
        </style>
        <h1>Downloads</h1>
        <p class="sub">Files this browser has downloaded.</p>
        <div class="card">
          <input class="in" id="dl-url" type="url" placeholder="Paste a link to download (https)" autocapitalize="off" autocomplete="off">
          <div style="height:10px"></div>
          <button onclick="startDl()">Download</button>
          <p class="hint" id="dl-msg"></p>
        </div>
        <p><button class="ghost" onclick="clearAll()">Clear list</button></p>
        <div class="card" id="list"></div>
        <p class="hint">Files are saved to your phone's Downloads folder. PDF files open in Nirobo's own offline reader.</p>
        """
        val script = """
        function g(i){return document.getElementById(i)}
        function fmt(b){
          if(!b||b<0)return '';
          var u=['B','KB','MB','GB'],i=0;
          while(b>=1024&&i<3){b/=1024;i++}
          return (i?b.toFixed(1):Math.round(b))+' '+u[i]}
        function icon(n){
          var e=(n.split('.').pop()||'').toLowerCase();
          if(e==='pdf')return '\uD83D\uDCD5';
          if(['jpg','jpeg','png','gif','webp','bmp'].indexOf(e)>=0)return '\uD83D\uDDBC';
          if(['mp4','mkv','webm','3gp','avi','mov'].indexOf(e)>=0)return '\uD83C\uDFAC';
          if(['mp3','m4a','wav','ogg','aac','flac'].indexOf(e)>=0)return '\uD83C\uDFB5';
          if(['zip','rar','7z','tar','gz'].indexOf(e)>=0)return '\uD83D\uDDDC';
          if(['doc','docx','txt','odt','rtf'].indexOf(e)>=0)return '\uD83D\uDCDD';
          if(['xls','xlsx','csv','ods'].indexOf(e)>=0)return '\uD83D\uDCCA';
          return '\uD83D\uDCC1'}
        function pct(d){return d.total>0?Math.min(100,Math.round(d.got*100/d.total)):0}
        function statusText(d){
          if(d.s==='done')return 'Done'+(d.total>0?' - '+fmt(d.total):'');
          if(d.s==='failed')return 'Failed. Check your connection, then tap Retry.';
          if(d.s==='gone')return 'The file was removed';
          var t=(d.s==='waiting')?'Waiting...':((d.s==='paused')?'Paused':'Downloading');
          if(d.total>0)t+=' '+pct(d)+'% - '+fmt(d.got)+' of '+fmt(d.total);
          else if(d.got>0)t+=' - '+fmt(d.got);
          return t}
        function mkBtn(label,ghost,fn){
          var b=document.createElement('button');
          b.textContent=label;if(ghost)b.className='ghost';
          b.style.padding='8px 16px';b.style.marginRight='8px';b.style.marginTop='6px';
          b.onclick=fn;return b}
        var lastKey='';
        function draw(){
          var a;try{a=JSON.parse(Bridge.downloadsLive())}catch(e){a=[]}
          var key=a.map(function(d){return d.id+d.s}).join('|');
          var el=g('list');
          if(key===lastKey&&a.length){
            for(var i=0;i<a.length;i++){
              var st=g('st-'+a[i].id),pb=g('pb-'+a[i].id);
              if(st)st.textContent=statusText(a[i]);
              if(pb)pb.style.width=pct(a[i])+'%'}
            return}
          lastKey=key;
          el.innerHTML='';
          if(!a.length){
            var em=document.createElement('div');em.className='empty';
            em.textContent='No downloads yet.';el.appendChild(em);return}
          for(var i=0;i<a.length;i++){(function(d){
            var row=document.createElement('div');row.className='item';
            var top=document.createElement('div');
            top.style.display='flex';top.style.gap='12px';top.style.alignItems='center';
            var ic=document.createElement('div');ic.style.fontSize='26px';ic.textContent=icon(d.n);
            var tt=document.createElement('div');tt.style.flex='1';tt.style.minWidth='0';
            var nm=document.createElement('div');nm.className='t';nm.style.wordBreak='break-all';nm.textContent=d.n;
            var st=document.createElement('div');st.className='u';st.id='st-'+d.id;st.textContent=statusText(d);
            tt.appendChild(nm);tt.appendChild(st);top.appendChild(ic);top.appendChild(tt);row.appendChild(top);
            if(d.s==='running'||d.s==='waiting'||d.s==='paused'){
              var bar=document.createElement('div');bar.className='pbar';
              var fill=document.createElement('i');fill.id='pb-'+d.id;fill.style.width=pct(d)+'%';
              bar.appendChild(fill);row.appendChild(bar)}
            var bx=document.createElement('div');
            if(d.s==='done'){
              bx.appendChild(mkBtn('Open',false,function(){Bridge.openDownload(d.id)}));
              bx.appendChild(mkBtn('Delete',true,function(){del(d.id)}))}
            else if(d.s==='failed'){
              bx.appendChild(mkBtn('Retry',false,function(){Bridge.retryDownload(d.id);again()}));
              bx.appendChild(mkBtn('Delete',true,function(){del(d.id)}))}
            else if(d.s==='gone'){
              bx.appendChild(mkBtn('Remove from list',true,function(){del(d.id)}))}
            else{
              bx.appendChild(mkBtn('Cancel',true,function(){del(d.id)}))}
            row.appendChild(bx);el.appendChild(row)})(a[i])}}
        function again(){lastKey='';setTimeout(draw,700)}
        function del(id){Bridge.deleteDownload(id);again()}
        function startDl(){
          var r=Bridge.downloadLink(g('dl-url').value);
          var m=g('dl-msg');
          if(r==='ok'){m.textContent='Started. You can watch it below.';g('dl-url').value='';again()}
          else m.textContent=r}
        function clearAll(){Bridge.clearDownloads();again()}
        draw();setInterval(draw,1500);
        """
        return page("Downloads", dark, body, script)
    }

    // =====================================================================
    //  about:settings
    // =====================================================================

    private fun settings(dark: Boolean): String {
        val body = """
        <style>
        .sh{font-size:13px;opacity:.7;margin:20px 6px 8px;font-weight:600}
        .sg{background:rgba(127,127,127,.13);border-radius:18px;overflow:hidden;margin:0 0 6px}
        .si{display:flex;align-items:center;gap:12px;padding:15px 18px;border-top:1px solid rgba(127,127,127,.18)}
        .sg .si:first-child{border-top:0}
        .si .t{flex:1;min-width:0}
        .si .t b{display:block;font-weight:500;font-size:16px}
        .si .t span{display:block;font-size:13px;opacity:.7;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
        .si .ch{opacity:.45;font-size:22px}
        .view{display:none}
        .view.on{display:block}
        .back{display:flex;align-items:center;gap:12px;font-size:21px;margin:2px 0 16px}
        .dot{width:20px;height:20px;border-radius:50%;border:2px solid currentColor;opacity:.55;position:relative;flex:none}
        .dot.on{opacity:1}
        .dot.on:after{content:'';position:absolute;left:3px;top:3px;right:3px;bottom:3px;border-radius:50%;background:currentColor}
        .lg{width:34px;height:34px;border-radius:9px;background:rgba(127,127,127,.2);display:flex;align-items:center;justify-content:center;font-weight:700;overflow:hidden;flex:none}
        .lg img{width:24px;height:24px;object-fit:contain}
        .field{width:100%;font:inherit;padding:12px 14px;border-radius:12px;border:1px solid rgba(127,127,127,.4);background:transparent;color:inherit;box-sizing:border-box}
        .note{font-size:13px;opacity:.7;margin:10px 6px}
        </style>

        <div class="view on" id="v-home">
          <h1>Settings</h1>
          <div class="sh">Basics</div>
          <div class="sg">
            <div class="si" onclick="show('engine')"><div class="t"><b>Search engine</b><span id="s-engine">—</span></div><div class="ch">›</div></div>
            <div class="si" onclick="show('bar')"><div class="t"><b>Address bar</b><span id="s-bar">—</span></div><div class="ch">›</div></div>
            <div class="si" onclick="show('priv')"><div class="t"><b>Privacy and security</b><span id="s-priv">—</span></div><div class="ch">›</div></div>
            <div class="si" onclick="location.href='about:mail'"><div class="t"><b>Email and passwords</b><span>Your email sites and autofill</span></div><div class="ch">›</div></div>
            <div class="si" onclick="show('safe')"><div class="t"><b>Safety check</b><span id="s-safe">Check, clean up and test links</span></div><div class="ch">›</div></div>
            <div class="si" onclick="show('def')"><div class="t"><b>Default browser</b><span>Make Nirobo open your links</span></div><div class="ch">›</div></div>
          </div>
          <div class="sh">Advanced</div>
          <div class="sg">
            <div class="si" onclick="show('home2')"><div class="t"><b>Homepage</b><span id="s-home">—</span></div><div class="ch">›</div></div>
            <div class="si" onclick="show('notif')"><div class="t"><b>Notifications</b><span>Downloads and battery reminders</span></div><div class="ch">›</div></div>
            <div class="si" onclick="show('look')"><div class="t"><b>Appearance</b><span id="s-app">—</span></div><div class="ch">›</div></div>
            <div class="si" onclick="show('acc')"><div class="t"><b>Accessibility</b><span id="s-acc">—</span></div><div class="ch">›</div></div>
            <div class="si" onclick="show('site')"><div class="t"><b>Site settings</b><span>Shield for each site</span></div><div class="ch">›</div></div>
            <div class="si" onclick="show('dl')"><div class="t"><b>Downloads</b><span>List and saved news</span></div><div class="ch">›</div></div>
            <div class="si" onclick="show('about')"><div class="t"><b>About Nirobo Browser</b><span id="s-ver">—</span></div><div class="ch">›</div></div>
          </div>
        </div>

        <div class="view" id="v-engine">
          <div class="back" onclick="show('home')">←  Search engine</div>
          <div class="sg">
            <div class="si" data-g="engine" data-v="nirobo" onclick="setEngine('nirobo')"><div class="dot"></div><div class="t"><b>Nirobo</b><span>Your own Bangladeshi search</span></div></div>
            <div class="si" data-g="engine" data-v="google" onclick="setEngine('google')"><div class="dot"></div><div class="t"><b>Google</b></div></div>
            <div class="si" data-g="engine" data-v="ddg" onclick="setEngine('ddg')"><div class="dot"></div><div class="t"><b>DuckDuckGo</b></div></div>
            <div class="si" data-g="engine" data-v="bing" onclick="setEngine('bing')"><div class="dot"></div><div class="t"><b>Bing</b></div></div>
          </div>
          <div class="sh">Your own engines</div>
          <div class="sg" id="custom-engines"></div>
          <div class="sh">Add a search engine</div>
          <div style="padding:0 2px">
            <input class="field" id="ce-name" placeholder="Name, for example Yandex" autocapitalize="words" autocomplete="off" maxlength="30">
            <div style="height:10px"></div>
            <input class="field" id="ce-url" placeholder="https://yandex.com/search/?text=%s" type="url" autocapitalize="off" autocomplete="off">
            <div class="note">Put <b>%s</b> where the search words go. Open the engine in the browser, search for the word test, then copy the address and change the word test to %s.</div>
            <button onclick="addEng()">Add engine</button>
            <div class="note" id="ce-msg"></div>
          </div>
        </div>

        <div class="view" id="v-bar">
          <div class="back" onclick="show('home')">←  Address bar</div>
          <div class="sg">
            <div class="si" data-g="bar" data-v="top" onclick="setBar('top')"><div class="dot"></div><div class="t"><b>Top</b></div></div>
            <div class="si" data-g="bar" data-v="bottom" onclick="setBar('bottom')"><div class="dot"></div><div class="t"><b>Bottom</b><span>Easier to reach with one thumb</span></div></div>
          </div>
        </div>

        <div class="view" id="v-priv">
          <div class="back" onclick="show('home')">←  Privacy and security</div>
          <div class="sg">
            <div class="si"><div class="t"><b>Ad &amp; tracker shield</b><span>Blocks ads and trackers</span></div><div class="sw" id="sw-shield" onclick="tog('shield')"><i></i></div></div>
            <div class="si"><div class="t"><b>Block cookie pop-ups</b></div><div class="sw" id="sw-cookie" onclick="tog('cookie')"><i></i></div></div>
            <div class="si"><div class="t"><b>Block screen mirroring &amp; screenshots</b><span>Casting and recording show black</span></div><div class="sw" id="sw-secure" onclick="tog('secure')"><i></i></div></div>
            <div class="si"><div class="t"><b>Allow sign-in cookies (third-party)</b><span>Turn on if a work or school email will not stay signed in</span></div><div class="sw" id="sw-third" onclick="tog('third')"><i></i></div></div>
          </div>
          <div class="sg">
            <div class="si" onclick="if(confirm('Clear cookies, cache and history?'))clr('all')"><div class="t"><b>Clear browsing data</b><span>Cookies, cache and history</span></div></div>
          </div>
          <p class="note">Tip: long-press a link and choose Open in sandbox for links you do not trust.</p>
        </div>

        <div class="view" id="v-safe">
          <div class="back" onclick="show('home')">←  Safety check</div>
          <div class="sg">
            <div class="si"><div class="t"><b>Run safety check</b><span id="sc-sum">Tap Check to test your settings</span></div><button onclick="runCheck()">Check</button></div>
          </div>
          <div class="sg" id="sc-list"></div>

          <div class="sh">Clean up</div>
          <div class="sg">
            <div class="si"><div class="t"><b>Clear history</b></div><button class="ghost" onclick="cleanIt('history')">Clear</button></div>
            <div class="si"><div class="t"><b>Clear cookies</b><span>You will be signed out of websites</span></div><button class="ghost" onclick="cleanIt('cookies')">Clear</button></div>
            <div class="si"><div class="t"><b>Clear cache</b><span>Saved pictures and files of websites</span></div><button class="ghost" onclick="cleanIt('cache')">Clear</button></div>
            <div class="si"><div class="t"><b>Clear site data</b><span>Website storage and location permissions</span></div><button class="ghost" onclick="cleanIt('site')">Clear</button></div>
            <div class="si"><div class="t"><b>Clear everything</b><span>All of the above, and your history</span></div><button class="ghost" onclick="if(confirm('Clear everything?'))cleanIt('all')">Clear</button></div>
          </div>

          <div class="sh">Test a link</div>
          <div style="padding:0 2px">
            <input class="field" id="lk" placeholder="Paste a link here. It will not be opened." type="url" autocapitalize="off" autocomplete="off">
            <div style="height:10px"></div>
            <button onclick="checkLink()">Test link</button>
            <div class="sg" id="lk-out" style="margin-top:12px"></div>
            <div class="note">A quick check only. It cannot promise that a link is safe.</div>
          </div>
        </div>

        <div class="view" id="v-def">
          <div class="back" onclick="show('home')">←  Default browser</div>
          <div class="sg"><div class="si" onclick="sysOpen('default')"><div class="t"><b>Open Android settings</b><span>Choose Nirobo Browser as the browser app</span></div><div class="ch">›</div></div></div>
        </div>

        <div class="view" id="v-home2">
          <div class="back" onclick="show('home')">←  Homepage</div>
          <input class="field" id="home-in" type="url" autocapitalize="off" autocomplete="off">
          <p class="note">Opens for every new tab and for the home button. Leave it empty to go back to Nirobo.</p>
          <button class="ghost" onclick="saveHome()">Save</button>
        </div>

        <div class="view" id="v-notif">
          <div class="back" onclick="show('home')">←  Notifications</div>
          <div class="sg"><div class="si" onclick="sysOpen('notifications')"><div class="t"><b>Open Android notification settings</b><span>Download progress and battery reminders</span></div><div class="ch">›</div></div></div>
        </div>

        <div class="view" id="v-look">
          <div class="back" onclick="show('home')">←  Appearance</div>
          <div class="sg">
            <div class="si"><div class="t"><b>Theme</b></div>
              <select id="theme" onchange="setTheme(this.value)" style="font:inherit;padding:6px 8px;border-radius:8px;border:1px solid rgba(127,127,127,.4);background:transparent;color:inherit">
                <option value="0">Follow phone</option><option value="1">Light</option><option value="2">Dark</option>
              </select></div>
            <div class="si"><div class="t"><b>Reader text size</b></div>
              <input id="rsize" type="range" min="14" max="26" step="1" oninput="setReader(this.value)" style="width:120px"></div>
          </div>
        </div>

        <div class="view" id="v-acc">
          <div class="back" onclick="show('home')">←  Accessibility</div>
          <div class="sg">
            <div class="si"><div class="t"><b>Text size</b><span id="zlabel">100%</span></div></div>
            <div class="si"><input id="zoom" type="range" min="50" max="200" step="10" oninput="setZoom(this.value)" onchange="load()" style="width:100%"></div>
          </div>
          <p class="note">Makes the text bigger or smaller on every web page.</p>
        </div>

        <div class="view" id="v-site">
          <div class="back" onclick="show('home')">←  Site settings</div>
          <div class="sg"><div class="si" onclick="location.href='about:shield'"><div class="t"><b>Ad shield for each site</b><span>See and change the sites you set</span></div><div class="ch">›</div></div></div>
        </div>

        <div class="view" id="v-dl">
          <div class="back" onclick="show('home')">←  Downloads</div>
          <div class="sg">
            <div class="si" onclick="location.href='about:downloads'"><div class="t"><b>Open downloads list</b></div><div class="ch">›</div></div>
            <div class="si"><div class="t"><b>Downloads list</b></div><button class="ghost" onclick="clr('downloads')">Clear</button></div>
            <div class="si"><div class="t"><b>History</b></div><button class="ghost" onclick="clr('history')">Clear</button></div>
            <div class="si"><div class="t"><b>Bookmarks</b><span id="bcount">—</span></div></div>
          </div>
          <div class="sh">Saved news</div>
          <div class="sg">
            <div class="si"><div class="t"><b>Last saved</b><span id="news">—</span></div></div>
            <div class="si"><div class="t"><b>Save it again now</b></div><button class="ghost" onclick="saveNews()">Save</button></div>
          </div>
        </div>

        <div class="view" id="v-about">
          <div class="back" onclick="show('home')">←  About Nirobo Browser</div>
          <div class="sg">
            <div class="si"><div class="t"><b>Version</b><span id="ver">—</span></div></div>
            <div class="si"><div class="t"><b>Updates</b><span id="upd">—</span></div></div>
            <div class="si"><div class="t"><b>Last checked</b><span id="updlast">—</span></div></div>
            <div class="si"><div class="t"><b>Check for updates now</b></div><button class="ghost" onclick="checkUpd()">Check</button></div>
            <div class="si"><div class="t"><b>Check when the app opens</b></div><div class="sw" id="sw-autoupdate" onclick="tog('autoupdate')"><i></i></div></div>
            <div class="si" onclick="location.href='about:version'"><div class="t"><b>Internal pages</b><span>about:version</span></div><div class="ch">›</div></div>
          </div>
        </div>
        """
        val script = """
        function g(i){return document.getElementById(i)}
        function show(id){
          var v=document.querySelectorAll('.view');
          for(var i=0;i<v.length;i++)v[i].classList.remove('on');
          g('v-'+id).classList.add('on');window.scrollTo(0,0)}
        function sw(id,on){var e=g('sw-'+id);if(!e)return;if(on)e.classList.add('on');else e.classList.remove('on')}
        function engineOf(u){u=u||'';
          if(u.indexOf('google.')>=0)return 'google';
          if(u.indexOf('duckduckgo')>=0)return 'ddg';
          if(u.indexOf('bing.com')>=0)return 'bing';
          return 'nirobo'}
        var NAMES={nirobo:'Nirobo',google:'Google',ddg:'DuckDuckGo',bing:'Bing'};
        function dots(group,val){
          var e=document.querySelectorAll('[data-g="'+group+'"]');
          for(var i=0;i<e.length;i++){
            var d=e[i].querySelector('.dot');
            if(e[i].getAttribute('data-v')===String(val))d.classList.add('on');else d.classList.remove('on')}}
        function load(){
          var S; try{S=JSON.parse(Bridge.settings())}catch(e){return}
          var CE=[]; try{CE=JSON.parse(Bridge.engines())}catch(e){}
          var eng=engineOf(S.searchUrl);
          var ename=NAMES[eng];
          for(var ci=0;ci<CE.length;ci++){if(CE[ci].url===S.searchUrl){eng='c:'+CE[ci].id;ename=CE[ci].name}}
          g('s-engine').textContent=ename;
          drawEngines(CE);
          g('s-bar').textContent=S.barBottom?'Bottom':'Top';
          g('s-priv').textContent=(S.shield?'Shield on':'Shield off')+(S.secure?' · screen locked':'');
          g('s-home').textContent=String(S.homeUrl).replace('https://','');
          g('s-app').textContent=['Follow phone','Light','Dark'][S.theme]||'';
          g('s-acc').textContent=S.textZoom+'% text';
          g('s-ver').textContent='Version '+S.version;
          dots('engine',eng);dots('bar',S.barBottom?'bottom':'top');
          sw('shield',S.shield);sw('cookie',S.cookie);sw('secure',S.secure);sw('autoupdate',S.autoupdate);sw('third',S.third);
          g('theme').value=S.theme;g('rsize').value=S.readerSize;
          g('zoom').value=S.textZoom;g('zlabel').textContent=S.textZoom+'%';
          if(document.activeElement!==g('home-in'))g('home-in').value=S.homeUrl;
          g('news').textContent=S.newsSavedAt||'never';
          g('bcount').textContent=S.bookmarks+' saved';
          g('ver').textContent=S.version;
          var u=null; try{u=JSON.parse(Bridge.updateState())}catch(e){}
          if(u){g('upd').textContent=u.status;g('updlast').textContent=u.last}
        }
        function setEngine(k){Bridge.set('engine',k);load()}
        function logo(e){
          var d=document.createElement('div');d.className='lg';
          var first=(e.name||'?').charAt(0).toUpperCase();
          if(e.icon){var im=document.createElement('img');im.src=e.icon;
            im.onerror=function(){d.textContent=first};d.appendChild(im)}
          else d.textContent=first;
          return d}
        function drawEngines(CE){
          var box=g('custom-engines');box.innerHTML='';
          if(!CE.length){var em=document.createElement('div');em.className='si';
            var et=document.createElement('div');et.className='t';
            var eb=document.createElement('span');eb.textContent='No engine added yet';
            et.appendChild(eb);em.appendChild(et);box.appendChild(em);return}
          for(var i=0;i<CE.length;i++){(function(e){
            var row=document.createElement('div');row.className='si';
            row.setAttribute('data-g','engine');row.setAttribute('data-v','c:'+e.id);
            var dot=document.createElement('div');dot.className='dot';row.appendChild(dot);
            row.appendChild(logo(e));
            var t=document.createElement('div');t.className='t';
            var b=document.createElement('b');b.textContent=e.name;
            var sp=document.createElement('span');
            try{sp.textContent=new URL(e.url.replace('%s','x')).host}catch(x){sp.textContent=''}
            t.appendChild(b);t.appendChild(sp);row.appendChild(t);
            var del=document.createElement('div');del.className='ch';del.textContent='\u2715';
            del.style.padding='0 4px';
            del.onclick=function(ev){ev.stopPropagation();
              if(confirm('Remove '+e.name+'?')){Bridge.removeEngine(e.id);load()}};
            row.appendChild(del);
            row.onclick=function(){Bridge.useEngine(e.id);load()};
            box.appendChild(row)})(CE[i])}}
        function addEng(){
          var r=Bridge.addEngine(g('ce-name').value,g('ce-url').value);
          var m=g('ce-msg');
          if(r==='ok'){m.textContent='Added. Getting the logo...';
            g('ce-name').value='';g('ce-url').value='';
            load();setTimeout(load,2500);setTimeout(load,7000)}
          else m.textContent=r}
        function setBar(k){Bridge.set('bar',k);load()}
        function tog(k){Bridge.toggle(k);load()}
        function setTheme(v){Bridge.set('theme',v);load()}
        function setReader(v){Bridge.set('readerSize',v)}
        function setZoom(v){g('zlabel').textContent=v+'%';Bridge.set('textZoom',v)}
        function saveHome(){Bridge.set('homepage',g('home-in').value);load()}
        function clr(k){Bridge.clear(k);load()}
        function cleanIt(k){Bridge.clear(k);g('sc-sum').textContent='Done.'}
        function srow(box,key,val,cls,btn,fn){
          var r=document.createElement('div');r.className='si';
          var t=document.createElement('div');t.className='t';
          var b=document.createElement('b');b.textContent=key;
          var s=document.createElement('span');s.textContent=val;
          t.appendChild(b);t.appendChild(s);r.appendChild(t);
          if(cls){var p=document.createElement('span');p.className='pill '+cls;
            p.textContent=(cls==='ok')?'OK':(cls==='bad'?'Risk':'Check');r.appendChild(p)}
          if(btn){var bt=document.createElement('button');bt.className='ghost';
            bt.textContent=btn;bt.onclick=fn;r.appendChild(bt)}
          box.appendChild(r)}
        function runCheck(){
          var S;try{S=JSON.parse(Bridge.settings())}catch(e){return}
          var box=g('sc-list');box.innerHTML='';
          var items=[
            ['Ad and tracker shield',S.shield,'On','Off - ads and trackers can load','shield'],
            ['Hide cookie banners',S.cookie,'On','Off','cookie'],
            ['Third-party cookies',!S.third,'Blocked','Allowed - trackers can follow you','third'],
            ['Screen lock',S.secure,'On - screenshots are blocked','Off - screenshots are allowed','secure']];
          var good=0,total=items.length+2;
          for(var i=0;i<items.length;i++){(function(it){
            if(it[1]){good++;srow(box,it[0],it[2],'ok')}
            else srow(box,it[0],it[3],'warn','Fix',function(){Bridge.toggle(it[4]);runCheck()})})(items[i])}
          good++;srow(box,'Only secure pages','Nirobo opens https pages only','ok');
          var n=S.blockedHosts||0;
          if(n>0){good++;srow(box,'Block list',n+' ad and tracker sites are blocked','ok')}
          else srow(box,'Block list','The list is empty or still loading','warn');
          g('sc-sum').textContent=good+' of '+total+' checks passed'}
        function checkLink(){
          var out=g('lk-out');out.innerHTML='';
          var raw=g('lk').value.trim();if(!raw)return;
          var u;
          try{u=new URL(/^[a-z]+:/i.test(raw)?raw:'https://'+raw)}
          catch(e){srow(out,'Link','This is not a valid link','bad');return}
          var h=u.hostname.toLowerCase();var parts=h.split('.');
          var issues=0;
          srow(out,'Goes to',h,'');
          if(u.protocol==='https:')srow(out,'Secure (https)','Yes','ok');
          else{issues++;srow(out,'Secure (https)','No - others can see what you send','bad')}
          if(h.replace(/[0-9.]/g,'')===''||h.indexOf(':')>=0){issues++;srow(out,'Address','A number, not a name','warn')}
          if(u.username||u.password){issues++;srow(out,'Hidden part','Has a login part before the real site','bad')}
          if(h.indexOf('xn--')>=0){issues++;srow(out,'Letters','Uses look-alike letters','warn')}
          if(parts.length>4){issues++;srow(out,'Name','Very many parts in the name','warn')}
          if((h.match(/-/g)||[]).length>=3){issues++;srow(out,'Name','Many dashes in the name','warn')}
          var short=['bit.ly','tinyurl.com','t.co','goo.gl','ow.ly','is.gd','cutt.ly','rb.gy','shorturl.at'];
          if(short.indexOf(h)>=0){issues++;srow(out,'Short link','Hides the real website','warn')}
          var bad=['zip','mov','tk','ml','ga','cf','gq','click'];
          if(bad.indexOf(parts[parts.length-1])>=0){issues++;srow(out,'Ending','Often used by scam sites','warn')}
          if(raw.length>200){issues++;srow(out,'Length','Very long link','warn')}
          if(Bridge.isBlocked(h)){issues++;srow(out,'Block list','This site is in the ad and tracker list','warn')}
          srow(out,'Result',issues===0?'Nothing unusual found':(issues<3?'Be careful':'Looks risky'),issues===0?'ok':(issues<3?'warn':'bad'))}
        function sysOpen(w){Bridge.openSystem(w)}
        function checkUpd(){g('upd').textContent='Checking…';Bridge.checkUpdate();setTimeout(load,2500);setTimeout(load,7000)}
        function saveNews(){Bridge.saveNews();setTimeout(load,2500)}
        load();
        """
        return page("Settings", dark, body, script)
    }

    // =====================================================================
    //  about:shield  —  per-site switches
    // =====================================================================

    private fun shield(dark: Boolean): String {
        val body = """
        <h1>Ad shield</h1>
        <p class="sub">Turn blocking on or off for each site you visit.</p>
        <div class="card">
          <div class="row"><span class="k">Blocking everywhere</span>
            <span class="v"><div class="sw" id="sw-master" onclick="master()"><i></i></div></span></div>
          <div class="row"><span class="k">Block cookie pop-ups</span>
            <span class="v"><div class="sw" id="sw-cookie" onclick="cookie()"><i></i></div></span></div>
        </div>
        <h2>Sites you have changed</h2>
        <div class="card" id="list"></div>
        <p class="hint">A site with no switch of its own follows the master setting.
        Change one from the shield button in the address bar while you are on that site.</p>
        """
        val script = """
        function esc(s){return String(s).replace(/[&<>"]/g,function(c){
          return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
        function sw(id,on){var e=document.getElementById(id);
          if(on)e.classList.add('on');else e.classList.remove('on')}
        function draw(){
          (function(json){
            var s; try{s=JSON.parse(json)}catch(e){return}
            sw('sw-master',s.master); sw('sw-cookie',s.cookie);
            var el=document.getElementById('list');
            if(!s.sites.length){el.innerHTML='<div class="empty">No per-site changes yet.</div>';return}
            var h='';
            for(var i=0;i<s.sites.length;i++){
              var x=s.sites[i];
              h+='<div class="row"><span class="k mono">'+esc(x.host)+'</span>'+
                 '<span class="v"><div class="sw '+(x.on?'on':'')+
                 '" onclick="site(\''+encq(x.host)+'\')"><i></i></div></span></div>';
            }
            el.innerHTML=h;
          })(Bridge.shieldState());
        }
        function master(){Bridge.toggle('shield');draw()}
        function cookie(){Bridge.toggle('cookie');draw()}
        function site(h){Bridge.toggleSite(decodeURIComponent(h));draw()}
        draw();
        """
        return page("Ad shield", dark, body, script)
    }

    // =====================================================================
    //  about:news  —  the saved NewsHub copy
    // =====================================================================

    private fun news(dark: Boolean): String {
        val body = """
        <h1>Saved news</h1>
        <p class="sub" id="sub">The copy Nirobo keeps on this phone.</p>
        <div id="out"><div class="empty">Loading…</div></div>
        """
        val script = """
        function esc(s){return String(s).replace(/[&<>"]/g,function(c){
          return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
        function ago(ts){
          if(!ts)return 'never';
          var d=new Date(ts),diff=(new Date()-d)/1000;
          if(diff<60)return 'just now';
          if(diff<3600)return Math.floor(diff/60)+' min ago';
          if(diff<86400)return Math.floor(diff/3600)+' h ago';
          return Math.floor(diff/86400)+' d ago';
        }
        (function(json){
          var r; try{r=JSON.parse(json)}catch(e){
            document.getElementById('out').innerHTML='<div class="empty">No saved copy.</div>';return}
          if(!r.items||!r.items.length){
            document.getElementById('out').innerHTML=
              '<div class="empty">No saved copy yet.<br>Connect to the internet once and it saves itself.</div>';
            return;
          }
          document.getElementById('sub').textContent=
            r.items.length+' stories · saved '+ago(r.savedAt);
          var h='<div class="card">';
          for(var i=0;i<r.items.length;i++){
            var x=r.items[i];
            h+='<div class="item" onclick="open_(\''+encq(x.u)+'\')">'+
               '<div class="t">'+esc(x.t)+'</div>'+
               '<div class="u">'+esc(x.s||'')+(x.d?' · '+esc(x.d.substring(0,80)):'')+'</div></div>';
          }
          h+='</div>';
          document.getElementById('out').innerHTML=h;
        })(Bridge.savedNews());
        function open_(u){Bridge.open(decodeURIComponent(u))}
        """
        return page("Saved news", dark, body, script)
    }

    // =====================================================================
    //  about:version / about:nirobo
    // =====================================================================

    private fun version(dark: Boolean): String {
        val body = """
        <h1>Nirobo Browser</h1>
        <p class="sub">Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})</p>

        <div class="card">
          <div class="row"><span class="k">Engine</span><span class="v">Android System WebView</span></div>
          <div class="row"><span class="k">Search</span><span class="v">Nirobo</span></div>
          <div class="row"><span class="k">Home</span><span class="v mono">nirobo.pages.dev</span></div>
          <div class="row"><span class="k">Android</span><span class="v">${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})</span></div>
        </div>

        <h2>Internal pages</h2>
        <div class="card">
          <div class="row"><span class="k"><a href="about:blank">about:blank</a></span><span class="v">A truly blank page</span></div>
          <div class="row"><span class="k"><a href="about:internet">about:internet</a></span><span class="v">Speed test</span></div>
          <div class="row"><span class="k"><a href="about:web">about:web</a></span><span class="v">Website check</span></div>
          <div class="row"><span class="k"><a href="about:phone">about:phone</a></span><span class="v">Battery &amp; device</span></div>
          <div class="row"><span class="k"><a href="about:bookmarks">about:bookmarks</a></span><span class="v">Saved pages</span></div>
          <div class="row"><span class="k"><a href="about:history">about:history</a></span><span class="v">Visited pages</span></div>
          <div class="row"><span class="k"><a href="about:downloads">about:downloads</a></span><span class="v">Downloaded files</span></div>
          <div class="row"><span class="k"><a href="about:shield">about:shield</a></span><span class="v">Ad shield per site</span></div>
          <div class="row"><span class="k"><a href="about:news">about:news</a></span><span class="v">Saved NewsHub</span></div>
          <div class="row"><span class="k"><a href="about:settings">about:settings</a></span><span class="v">Settings</span></div>
          <div class="row"><span class="k"><a href="about:update">about:update</a></span><span class="v">Check for a new version</span></div>
          <div class="row"><span class="k">about:charge</span><span class="v">Same as about:phone</span></div>
          <div class="row"><span class="k">about:sandbox</span><span class="v">Opens the locked sandbox window</span></div>
        </div>

        <p class="hint">Type any of these in the address bar. They work with no internet.</p>
        """
        return page("About Nirobo Browser", dark, body)
    }

    // =====================================================================
    //  about:update  —  hidden: check for a new version
    // =====================================================================

    private fun update(dark: Boolean): String {
        val body = """
        <h1>Updates</h1>
        <p class="sub">Nirobo Browser updates itself from its own GitHub releases.</p>
        <div class="card">
          <div class="row"><span class="k">Installed</span><span class="v" id="inst">—</span></div>
          <div class="row"><span class="k">Newest build</span><span class="v" id="latest">—</span></div>
          <div class="row"><span class="k">Last checked</span><span class="v" id="last">—</span></div>
          <div class="row"><span class="k">Result</span><span class="v" id="status">—</span></div>
          <div class="row"><span class="k">Check now</span>
            <span class="v"><button class="ghost" onclick="check()">Check</button></span></div>
          <div class="row"><span class="k">Check when the app opens</span>
            <span class="v"><div class="sw" id="sw-auto" onclick="tog()"><i></i></div></span></div>
        </div>
        <p class="hint" id="repo"></p>
        <p class="hint">When a newer build exists, a box asks if you want it. Tap Update now and Android installs it over this app. Your bookmarks and settings stay.</p>
        """
        val script = """
        function load(){
          var s; try{s=JSON.parse(Bridge.updateState())}catch(e){return}
          document.getElementById('inst').textContent=s.installed;
          document.getElementById('latest').textContent=s.latest||'—';
          document.getElementById('last').textContent=s.last;
          document.getElementById('status').textContent=s.status;
          document.getElementById('repo').textContent='Source: github.com/'+s.repo;
          var e=document.getElementById('sw-auto');
          if(s.auto)e.classList.add('on');else e.classList.remove('on');
        }
        function check(){
          document.getElementById('status').textContent='Checking…';
          Bridge.checkUpdate();
          setTimeout(load,2500);setTimeout(load,7000);
        }
        function tog(){Bridge.toggle('autoupdate');load()}
        load();
        """
        return page("Updates", dark, body, script)
    }

    // =====================================================================
    //  build
    // =====================================================================

    private fun calc(dark: Boolean): String {
        val body = """
        <style>
        .tabs{display:flex;gap:8px;margin:14px 0}
        .tabs button{flex:1;padding:10px 4px;font-size:14px}
        .in{width:100%;font:inherit;padding:12px;border-radius:11px;border:1px solid rgba(127,127,127,.4);background:transparent;color:inherit}
        .two{display:grid;grid-template-columns:1fr 1fr;gap:10px}
        .lb{font-size:12px;opacity:.7;margin:10px 0 4px}
        .kp{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-top:12px}
        .kp button{padding:16px 0;font-size:20px}
        h3{font-size:17px;margin:0 0 4px}
        </style>
        <h1>Calculator</h1>
        <p class="sub">Price, weight and money, made easy.</p>
        <div class="tabs">
          <button id="tb1" onclick="tab(1)">Price</button>
          <button id="tb2" class="ghost" onclick="tab(2)">Numbers</button>
          <button id="tb3" class="ghost" onclick="tab(3)">Compare</button>
        </div>

        <div id="t1">
          <div class="card">
            <h3>Product price</h3>
            <div class="two">
              <div><div class="lb">Price (৳)</div><input class="in" id="p-price" type="number" inputmode="decimal" oninput="calc1()"></div>
              <div><div class="lb">per</div><select class="in u" id="p-per" data-def="kg" onchange="calc1()"></select></div>
            </div>
            <div class="two">
              <div><div class="lb">I want</div><input class="in" id="p-qty" type="number" inputmode="decimal" oninput="calc1()"></div>
              <div><div class="lb">unit</div><select class="in u" id="p-unit" data-def="g" onchange="calc1()"></select></div>
            </div>
            <div class="two">
              <div><div class="lb">Discount % (optional)</div><input class="in" id="p-disc" type="number" inputmode="decimal" oninput="calc1()"></div>
              <div><div class="lb">VAT % (optional)</div><input class="in" id="p-vat" type="number" inputmode="decimal" oninput="calc1()"></div>
            </div>
            <div id="p-out" style="margin-top:12px"></div>
          </div>
          <div class="card">
            <h3>How much can I buy?</h3>
            <div class="lb">My money (৳)</div>
            <input class="in" id="b-money" type="number" inputmode="decimal" oninput="calc1()">
            <div id="b-out" style="margin-top:12px"></div>
            <div class="hint">It uses the price you wrote above.</div>
          </div>
        </div>

        <div id="t2" style="display:none">
          <div class="card">
            <div id="k-ex" style="min-height:24px;text-align:right;opacity:.7;word-break:break-all"></div>
            <div id="k-res" class="big" style="text-align:right;word-break:break-all">0</div>
            <div class="kp" id="kp"></div>
          </div>
        </div>

        <div id="t3" style="display:none">
          <div class="card">
            <h3>Which one is cheaper?</h3>
            <div class="lb"><b>Product A</b></div>
            <div class="two">
              <div><input class="in" id="a-price" type="number" inputmode="decimal" placeholder="Price (৳)" oninput="calc3()"></div>
              <div></div>
            </div>
            <div class="two" style="margin-top:8px">
              <div><input class="in" id="a-qty" type="number" inputmode="decimal" placeholder="Amount" oninput="calc3()"></div>
              <div><select class="in u" id="a-unit" data-def="kg" onchange="calc3()"></select></div>
            </div>
            <div class="lb"><b>Product B</b></div>
            <div class="two">
              <div><input class="in" id="b-price" type="number" inputmode="decimal" placeholder="Price (৳)" oninput="calc3()"></div>
              <div></div>
            </div>
            <div class="two" style="margin-top:8px">
              <div><input class="in" id="b-qty" type="number" inputmode="decimal" placeholder="Amount" oninput="calc3()"></div>
              <div><select class="in u" id="b-unit" data-def="kg" onchange="calc3()"></select></div>
            </div>
            <div id="c-out" style="margin-top:12px"></div>
          </div>
        </div>
        """
        val script = """
        function g(i){return document.getElementById(i)}
        var U={kg:['w',1],g:['w',0.001],lb:['w',0.45359237],oz:['w',0.028349523125],pc:['c',1],dz:['c',12]};
        var LBL={kg:'kg',g:'g',lb:'lb',oz:'oz',pc:'piece',dz:'dozen'};
        function fillUnits(){
          var s=document.querySelectorAll('select.u');
          for(var i=0;i<s.length;i++){
            for(var k in U){var o=document.createElement('option');o.value=k;o.textContent=LBL[k];s[i].appendChild(o)}
            s[i].value=s[i].getAttribute('data-def')}}
        function num(id){var v=parseFloat(g(id).value);return isNaN(v)?0:v}
        function money(v){return '৳ '+(Math.round(v*100)/100).toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:2})}
        function qf(v){return parseFloat(v.toPrecision(6)).toLocaleString('en-US',{maximumFractionDigits:4})}
        function rows(el,list){
          el.innerHTML='';
          for(var i=0;i<list.length;i++){
            var r=document.createElement('div');r.className='row';
            var k=document.createElement('span');k.className='k';k.textContent=list[i][0];
            var v=document.createElement('span');v.className='v';v.textContent=list[i][1];
            if(list[i][2])v.style.fontWeight='700';
            r.appendChild(k);r.appendChild(v);el.appendChild(r)}}
        function note(el,t){el.innerHTML='';var p=document.createElement('p');p.textContent=t;el.appendChild(p)}
        function tab(n){
          for(var i=1;i<=3;i++){
            g('t'+i).style.display=(i===n)?'block':'none';
            g('tb'+i).className=(i===n)?'':'ghost'}}

        function calc1(){
          var price=num('p-price'),qty=num('p-qty'),disc=num('p-disc'),vat=num('p-vat'),budget=num('b-money');
          var pu=U[g('p-per').value],qu=U[g('p-unit').value],out=g('p-out'),bo=g('b-out');
          if(pu[0]!==qu[0]){note(out,'Use the same kind: weight with weight, or pieces with pieces.');note(bo,'');return}
          if(!(price>0)){note(out,'Write the price first.');note(bo,'');return}
          var perBase=price/pu[1];
          if(qty>0){
            var base=perBase*qty*qu[1];
            var after=base*(1-disc/100);
            var total=after*(1+vat/100);
            var list=[['Price',money(base)]];
            if(disc>0)list.push(['Discount '+disc+'%','- '+money(base-after)]);
            if(vat>0)list.push(['VAT '+vat+'%','+ '+money(total-after)]);
            list.push(['You pay',money(total),1]);
            rows(out,list)
          }else note(out,'Write how much you want to buy.');
          if(budget>0){
            var bq=budget/perBase;
            var l2=[['You can buy',qf(bq/qu[1])+' '+LBL[g('p-unit').value]]];
            if(pu[0]==='w'){l2.push(['In kg',qf(bq)+' kg']);l2.push(['In grams',qf(bq*1000)+' g'])}
            rows(bo,l2)
          }else note(bo,'Write your money to see how much you can buy.')}

        function calc3(){
          var pa=num('a-price'),qa=num('a-qty'),pb=num('b-price'),qb=num('b-qty'),out=g('c-out');
          var ua=U[g('a-unit').value],ub=U[g('b-unit').value];
          if(ua[0]!==ub[0]){note(out,'Use the same kind of unit for both products.');return}
          if(!(pa>0&&qa>0&&pb>0&&qb>0)){note(out,'Write the price and the amount for both.');return}
          var A=pa/(qa*ua[1]),B=pb/(qb*ub[1]);
          var per=ua[0]==='w'?'kg':'piece';
          var list=[['Product A',money(A)+' per '+per],['Product B',money(B)+' per '+per]];
          if(A===B){list.push(['Result','Same price',1])}
          else{
            var win=A<B?'A':'B';
            var pct=Math.abs(A-B)/Math.max(A,B)*100;
            list.push(['Cheaper','Product '+win+' ('+qf(pct)+'% less)',1])}
          rows(out,list)}

        var ex='';
        function evalExpr(s){
          s=s.replace(/×/g,'*').replace(/÷/g,'/').replace(/−/g,'-');
          var i=0;
          function peek(){return s.charAt(i)}
          function pct(v){while(peek()==='%'){v=v/100;i++}return v}
          function num_(){
            var st=i;
            while(/[0-9.]/.test(peek()))i++;
            if(st===i)throw 0;
            var v=parseFloat(s.substring(st,i));
            if(isNaN(v))throw 0;
            return pct(v)}
          function factor(){
            if(peek()==='-'){i++;return -factor()}
            if(peek()==='+'){i++;return factor()}
            if(peek()==='('){i++;var v=expr();if(peek()!==')')throw 0;i++;return pct(v)}
            return num_()}
          function term(){
            var v=factor();
            while(peek()==='*'||peek()==='/'){
              var o=peek();i++;var r=factor();
              if(o==='*')v*=r;else{if(r===0)throw 0;v/=r}}
            return v}
          function expr(){
            var v=term();
            while(peek()==='+'||peek()==='-'){
              var o=peek();i++;
              var st=i;var r=term();
              if(/^[0-9.]+%+${'$'}/.test(s.substring(st,i)))r=v*r;
              v=(o==='+')?v+r:v-r}
            return v}
          var v=expr();
          if(i!==s.length)throw 0;
          return v}
        function safe(s){if(!s)return null;try{var v=evalExpr(s);return isFinite(v)?v:null}catch(e){return null}}
        function fmtNum(v){return parseFloat(v.toPrecision(12)).toLocaleString('en-US',{maximumFractionDigits:10})}
        function draw(){
          g('k-ex').textContent=ex||' ';
          var r=safe(ex);
          g('k-res').textContent=(ex==='')?'0':(r===null?'...':fmtNum(r))}
        function key(k){
          if(k==='C')ex='';
          else if(k==='⌫')ex=ex.slice(0,-1);
          else if(k==='='){var r=safe(ex);if(r!==null){var t=String(parseFloat(r.toPrecision(12)));ex=(t.indexOf('e')>=0)?'':t}}
          else if(ex.length<60)ex+=k;
          draw()}
        function buildKeys(){
          var K=[['C','ghost'],['⌫','ghost'],['%','ghost'],['÷',''],
                 ['7','ghost'],['8','ghost'],['9','ghost'],['×',''],
                 ['4','ghost'],['5','ghost'],['6','ghost'],['−',''],
                 ['1','ghost'],['2','ghost'],['3','ghost'],['+',''],
                 ['(','ghost'],['0','ghost'],[')','ghost'],['.','ghost'],
                 ['=','']];
          var box=g('kp');
          for(var i=0;i<K.length;i++){(function(k){
            var b=document.createElement('button');b.textContent=k[0];b.className=k[1];
            if(k[0]==='=')b.style.gridColumn='span 4';
            b.onclick=function(){key(k[0])};
            box.appendChild(b)})(K[i])}}
        fillUnits();buildKeys();draw();calc1();calc3();
        """
        return page("Calculator", dark, body, script)
    }

    private fun mail(dark: Boolean): String {
        val body = """
        <style>
        h3{font-size:17px;margin:22px 4px 8px}
        .row2{display:flex;align-items:center;gap:12px;padding:13px 4px;border-top:1px solid rgba(127,127,127,.18)}
        .row2:first-child{border-top:0}
        .row2 .t{flex:1;min-width:0}
        .row2 .t b{display:block;font-weight:500}
        .row2 .t span{display:block;font-size:13px;opacity:.7;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .lg{width:38px;height:38px;border-radius:10px;display:flex;align-items:center;justify-content:center;font-weight:700;color:#fff;overflow:hidden;flex:none}
        .lg img{width:26px;height:26px;object-fit:contain}
        .in{width:100%;font:inherit;padding:12px;border-radius:11px;border:1px solid rgba(127,127,127,.4);background:transparent;color:inherit;box-sizing:border-box}
        </style>
        <h1>Email and passwords</h1>
        <p class="sub">Open your email in one tap.</p>
        <div class="card" id="mail-list"></div>

        <h3>Add your own email</h3>
        <div class="card">
          <input class="in" id="m-name" placeholder="Name, for example My company mail" maxlength="30" autocomplete="off">
          <div style="height:10px"></div>
          <input class="in" id="m-url" type="url" placeholder="https://mail.yourdomain.com" autocapitalize="off" autocomplete="off">
          <p class="hint">It works with any webmail: your own domain, cPanel, Roundcube, Zoho and more.</p>
          <button onclick="addMail()">Add email</button>
          <p class="hint" id="m-msg"></p>
        </div>

        <h3>Stay signed in</h3>
        <div class="card">
          <p>Your email stays signed in on this phone until you sign out on the website or clear cookies.</p>
          <div class="row2">
            <div class="t"><b>Third-party cookies</b><span id="tp-state">...</span></div>
            <button class="ghost" id="tp-btn" onclick="toggleThird()">...</button>
          </div>
          <p class="hint">Some sign-in pages (Google, Microsoft) need these. If sign-in fails or keeps looping, tap Allow. After you are in, you can tap Block again.</p>
        </div>

        <h3>Passwords and autofill</h3>
        <div class="card">
          <p>Nirobo uses your phone's password manager (Google, Samsung, Bitwarden and others). Saved passwords fill in on https pages only. Autofill is switched off in private windows and in the sandbox.</p>
          <div class="row2">
            <div class="t"><b>Autofill</b><span id="af-state">...</span></div>
          </div>
          <p><button onclick="Bridge.openSystem('autofill')">Choose password manager</button></p>
          <p><button class="ghost" onclick="Bridge.open('https://passwords.google.com')">Open Google Password Manager</button></p>
          <p class="hint">If the button does not show your password manager, open Android Settings and search for Autofill service.</p>
        </div>
        """
        val script = """
        function g(i){return document.getElementById(i)}
        var P=[['Gmail','https://mail.google.com','#D93025','G'],
               ['Outlook','https://outlook.live.com/mail/','#0A64C8','O'],
               ['Yahoo Mail','https://mail.yahoo.com','#6001D2','Y'],
               ['iCloud Mail','https://www.icloud.com/mail','#3A8BEA','i'],
               ['Proton Mail','https://mail.proton.me','#6D4AFF','P'],
               ['Zoho Mail','https://mail.zoho.com','#C8202F','Z']];
        function hostOf(u){try{return new URL(u).host}catch(e){return ''}}
        function badge(color,letter,icon){
          var d=document.createElement('div');d.className='lg';
          if(icon){
            d.style.background='rgba(127,127,127,.2)';
            var im=document.createElement('img');im.src=icon;
            im.onerror=function(){d.textContent=letter};d.appendChild(im)}
          else{d.style.background=color||'rgba(127,127,127,.4)';d.textContent=letter}
          return d}
        function row(box,name,sub,color,letter,icon,openFn,delFn){
          var r=document.createElement('div');r.className='row2';
          r.appendChild(badge(color,letter,icon));
          var t=document.createElement('div');t.className='t';
          var b=document.createElement('b');b.textContent=name;
          var s=document.createElement('span');s.textContent=sub;
          t.appendChild(b);t.appendChild(s);r.appendChild(t);
          if(delFn){
            var x=document.createElement('div');x.textContent='\u2715';
            x.style.padding='6px 10px';x.style.opacity='.6';
            x.onclick=function(ev){ev.stopPropagation();delFn()};r.appendChild(x)}
          r.onclick=openFn;
          box.appendChild(r)}
        function drawMail(){
          var box=g('mail-list');box.innerHTML='';
          for(var i=0;i<P.length;i++){(function(p){
            row(box,p[0],hostOf(p[1]),p[2],p[3],'',function(){Bridge.open(p[1])},null)})(P[i])}
          var C=[];try{C=JSON.parse(Bridge.mailSites())}catch(e){}
          for(var j=0;j<C.length;j++){(function(c){
            row(box,c.name,hostOf(c.url),'',(c.name||'?').charAt(0).toUpperCase(),c.icon,
              function(){Bridge.open(c.url)},
              function(){if(confirm('Remove '+c.name+'?')){Bridge.removeMail(c.id);drawMail()}})})(C[j])}}
        function addMail(){
          var r=Bridge.addMail(g('m-name').value,g('m-url').value);
          var m=g('m-msg');
          if(r==='ok'){m.textContent='Added. Getting the logo...';
            g('m-name').value='';g('m-url').value='';
            drawMail();setTimeout(drawMail,2500);setTimeout(drawMail,7000)}
          else m.textContent=r}
        function drawState(){
          var S;try{S=JSON.parse(Bridge.settings())}catch(e){return}
          g('tp-state').textContent=S.third?'Allowed':'Blocked';
          g('tp-btn').textContent=S.third?'Block':'Allow';
          var a=Bridge.autofillState();
          g('af-state').textContent=(a==='on')?'On':((a==='off')?'Off - choose a password manager below':'Not supported on this phone')}
        function toggleThird(){Bridge.toggle('third');drawState()}
        drawMail();drawState();
        """
        return page("Email and passwords", dark, body, script)
    }

    fun build(url: String, dark: Boolean): String = when (name(url)) {
        "blank" -> blank(dark)
        "internet" -> internet(dark)
        "web" -> web(dark)
        "phone", "charge", "battery" -> phone(dark)
        "update" -> update(dark)
        "bookmarks" -> bookmarks(dark)
        "history" -> history(dark)
        "downloads" -> downloads(dark)
        "settings" -> settings(dark)
        "shield" -> shield(dark)
        "news" -> news(dark)
        "calc" -> calc(dark)
        "mail" -> mail(dark)
        "version", "nirobo" -> version(dark)
        else -> version(dark)
    }
}

/**
 * The only JavaScript interface the internal pages get.
 *
 * Every method is called from a page that this app generated itself, so there is
 * no untrusted input here — but each one still validates what it is given.
 */
internal class Bridge(
    private val act: BrowserActivity,
    private val store: Store
) {

    private val pool = Executors.newFixedThreadPool(2)

    // ---------- navigation ----------
    @JavascriptInterface
    fun open(url: String) {
        act.runOnUiThread { act.openFromPage(url) }
    }

    // ---------- lists ----------

    @JavascriptInterface
    fun bookmarks(): String = store.bookmarks().toString()

    @JavascriptInterface
    fun history(): String = store.history().toString()

    @JavascriptInterface
    fun downloads(): String = store.downloads().toString()

    @JavascriptInterface
    fun removeBookmark(url: String) {
        store.removeBookmark(url)
    }

    @JavascriptInterface
    fun clearHistory() {
        store.clearHistory()
    }

    @JavascriptInterface
    fun clearDownloads() {
        store.clearDownloads()
    }

    // ---------- settings ----------

    @JavascriptInterface
    fun settings(): String {
        val o = JSONObject()
        o.put("theme", store.theme)
        o.put("readerSize", store.readerSize)
        o.put("shield", store.shieldEnabled)
        o.put("cookie", store.cookieBlock)
        o.put("searchUrl", store.searchUrl)
        o.put("homeUrl", store.homeUrl)
        o.put("version", BuildConfig.VERSION_NAME)
        o.put("bookmarks", store.bookmarks().length())
        o.put("secure", store.secureScreen)
        o.put("autoupdate", store.autoUpdate)
        o.put("barBottom", store.barBottom)
        o.put("third", store.thirdCookies)
        o.put("blockedHosts", act.blocklistSize())
        o.put("textZoom", store.textZoom)
        o.put("newsSavedAt", if (store.newsSavedAt == 0L) "" else
            android.text.format.DateFormat.format("d MMM, HH:mm", store.newsSavedAt).toString())
        return o.toString()
    }

    // ---- search engines the person adds ----

    @JavascriptInterface
    fun engines(): String = store.engines().toString()

    /** Returns "ok", or a short message that says what is wrong. */
    @JavascriptInterface
    fun addEngine(name: String, template: String): String {
        val n = name.trim().take(30)
        var u = template.trim()
        if (n.isEmpty()) return "Write a name first."
        if (u.startsWith("http://")) u = "https://" + u.substring(7)
        else if (!u.startsWith("https://")) u = "https://" + u
        if (u.length > 300 || u.any { it.isWhitespace() }) return "That link is not valid."
        val host: String? = try {
            java.net.URI(u.replace("%s", "x")).host
        } catch (e: Exception) {
            null
        }
        if (host.isNullOrEmpty() || !host.contains('.')) return "That link is not valid."
        if (!u.contains("%s") && !u.endsWith("=")) return "Put %s where the search words go."
        val id = System.currentTimeMillis().toString()
        synchronized(ENGINE_LOCK) {
            val list = store.engines()
            if (list.length() >= 10) return "You can add 10 engines at most."
            val o = JSONObject()
            o.put("id", id)
            o.put("name", n)
            o.put("url", u)
            o.put("icon", "")
            list.put(o)
            store.saveEngines(list)
        }
        val siteHost: String = host
        Thread {
            val icon = fetchIcon(siteHost)
            if (icon.isNotEmpty()) setEngineIcon(id, icon)
        }.start()
        return "ok"
    }

    @JavascriptInterface
    fun useEngine(id: String) {
        synchronized(ENGINE_LOCK) {
            val list = store.engines()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                if (o.optString("id") == id) store.searchUrl = o.optString("url")
            }
        }
    }

    @JavascriptInterface
    fun removeEngine(id: String) {
        synchronized(ENGINE_LOCK) {
            val list = store.engines()
            val out = JSONArray()
            var wasUsed = false
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                if (o.optString("id") == id) {
                    if (o.optString("url") == store.searchUrl) wasUsed = true
                } else {
                    out.put(o)
                }
            }
            store.saveEngines(out)
            if (wasUsed) store.searchUrl = "https://nirobo.pages.dev/?q="
        }
    }

    private fun setEngineIcon(id: String, icon: String) {
        synchronized(ENGINE_LOCK) {
            val list = store.engines()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                if (o.optString("id") == id) o.put("icon", icon)
            }
            store.saveEngines(list)
        }
    }

    /** Reads at most [max] bytes. If the data is bigger: null (or what we have, when [failIfBig] is false). */
    private fun readCapped(ins: java.io.InputStream, max: Int, failIfBig: Boolean): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        ins.use {
            while (true) {
                val n = it.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > max) {
                    return if (failIfBig) null else buf.toByteArray()
                }
            }
        }
        return buf.toByteArray()
    }

    /** Only real picture formats are accepted. Anything else is thrown away. */
    private fun sniffImage(b: ByteArray): String? {
        if (b.size < 12) return null
        fun at(i: Int): Int = b[i].toInt() and 0xFF
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0x00 && at(1) == 0x00 && at(2) == 0x01 && at(3) == 0x00 -> "image/x-icon"
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 -> "image/webp"
            else -> null
        }
    }

    /** Gets the website's small logo once and keeps it, so it shows offline too. */
    private fun fetchIcon(host: String): String {
        try {
            val base = URL("https://" + host + "/")
            var iconUrl = "https://" + host + "/favicon.ico"
            // 1) ask the home page which logo it uses
            try {
                val c = base.openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) NiroboBrowser")
                if (c.responseCode == 200) {
                    val head = readCapped(c.inputStream, 200000, false)
                    if (head != null) {
                        val html = String(head, Charsets.UTF_8)
                        val tag = Regex("""<link[^>]+rel=["'][^"']*icon[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
                        val href = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        for (m in tag.findAll(html)) {
                            val h = href.find(m.value)?.groupValues?.get(1) ?: continue
                            val full = URL(base, h).toString()
                            if (full.startsWith("https://") && !full.endsWith(".svg")) {
                                iconUrl = full
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // fall back to /favicon.ico
            }
            // 2) download it
            val c2 = URL(iconUrl).openConnection() as HttpURLConnection
            c2.connectTimeout = 6000
            c2.readTimeout = 6000
            if (c2.responseCode != 200) return ""
            val bytes = readCapped(c2.inputStream, 60000, true) ?: return ""
            val mime = sniffImage(bytes) ?: return ""
            return "data:" + mime + ";base64," +
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            return ""
        }
    }

    @JavascriptInterface
    fun set(key: String, value: String) {
        when (key) {
            "theme" -> store.theme = value.toIntOrNull() ?: 0
            "readerSize" -> store.readerSize = (value.toIntOrNull() ?: 18).coerceIn(14, 26)
            "engine" -> store.searchUrl = when (value) {
                "google" -> "https://www.google.com/search?q="
                "ddg" -> "https://duckduckgo.com/?q="
                "bing" -> "https://www.bing.com/search?q="
                else -> "https://nirobo.pages.dev/?q="
            }
            "bar" -> {
                store.barBottom = value == "bottom"
                act.runOnUiThread { act.applyBarPosition() }
            }
            "textZoom" -> {
                store.textZoom = (value.toIntOrNull() ?: 100).coerceIn(50, 200)
                act.runOnUiThread { act.applyTextZoom() }
            }
            "homepage" -> {
                var u = value.trim()
                if (u.isEmpty()) {
                    u = "https://nirobo.pages.dev/"
                } else if (u.startsWith("http://")) {
                    u = "https://" + u.substring(7)
                } else if (!u.startsWith("https://")) {
                    u = "https://" + u
                }
                store.homeUrl = u
            }
        }
    }

    @JavascriptInterface
    fun openSystem(what: String) {
        act.runOnUiThread { act.openSystemSetting(what) }
    }

    @JavascriptInterface
    fun toggle(key: String) {
        when (key) {
            "shield" -> store.shieldEnabled = !store.shieldEnabled
            "cookie" -> store.cookieBlock = !store.cookieBlock
            "autoupdate" -> store.autoUpdate = !store.autoUpdate
            "third" -> {
                store.thirdCookies = !store.thirdCookies
                act.runOnUiThread { act.applyCookiePolicy() }
            }
            "secure" -> {
                store.secureScreen = !store.secureScreen
                act.runOnUiThread { act.applySecure() }
            }
        }
    }

    // ---------- updates ----------

    @JavascriptInterface
    fun updateState(): String {
        val o = JSONObject()
        o.put("installed", BuildConfig.VERSION_NAME + " (" + Updater.installedCode(act) + ")")
        o.put("latest", store.getString("upd_latest", ""))
        val last = store.getString("upd_last", "0").toLongOrNull() ?: 0L
        o.put("last", if (last == 0L) "never" else
            android.text.format.DateFormat.format("d MMM, HH:mm", last).toString())
        o.put("status", store.getString("upd_status", "Not checked yet"))
        o.put("auto", store.autoUpdate)
        o.put("repo", Updater.repo(act))
        return o.toString()
    }

    @JavascriptInterface
    fun checkUpdate() {
        act.runOnUiThread { Updater.check(act, true) }
    }

    @JavascriptInterface
    fun toggleSite(host: String) {
        val h = host.lowercase().trim()
        if (h.isEmpty()) return
        store.setSiteShield(h, !store.siteShield(h))
    }

    @JavascriptInterface
    fun shieldState(): String {
        val o = JSONObject()
        o.put("master", store.shieldEnabled)
        o.put("cookie", store.cookieBlock)
        val arr = JSONArray()
        val m = try {
            JSONObject(store.getString("sites", "{}"))
        } catch (e: Exception) {
            JSONObject()
        }
        val keys = m.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val e = JSONObject()
            e.put("host", k)
            e.put("on", m.optBoolean(k, true))
            arr.put(e)
        }
        o.put("sites", arr)
        return o.toString()
    }

    @JavascriptInterface
    fun isBlocked(host: String): Boolean = act.hostBlocked(host)

    // ---- email sites ----

    @JavascriptInterface
    fun mailSites(): String = store.mailSites().toString()

    @JavascriptInterface
    fun autofillState(): String = act.autofillState()

    /** Returns "ok", or a short message that says what is wrong. */
    @JavascriptInterface
    fun addMail(name: String, link: String): String {
        val n = name.trim().take(30)
        var u = link.trim()
        if (n.isEmpty()) return "Write a name first."
        if (u.startsWith("http://")) u = "https://" + u.substring(7)
        else if (!u.startsWith("https://")) u = "https://" + u
        if (u.length > 300 || u.any { it.isWhitespace() }) return "That link is not valid."
        val host: String? = try {
            java.net.URI(u).host
        } catch (e: Exception) {
            null
        }
        if (host.isNullOrEmpty() || !host.contains('.')) return "That link is not valid."
        val id = System.currentTimeMillis().toString()
        synchronized(ENGINE_LOCK) {
            val list = store.mailSites()
            if (list.length() >= 12) return "You can add 12 email sites at most."
            val o = JSONObject()
            o.put("id", id)
            o.put("name", n)
            o.put("url", u)
            o.put("icon", "")
            list.put(o)
            store.saveMailSites(list)
        }
        val siteHost: String = host
        Thread {
            val icon = fetchIcon(siteHost)
            if (icon.isNotEmpty()) setMailIcon(id, icon)
        }.start()
        return "ok"
    }

    @JavascriptInterface
    fun removeMail(id: String) {
        synchronized(ENGINE_LOCK) {
            val list = store.mailSites()
            val out = JSONArray()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                if (o.optString("id") != id) out.put(o)
            }
            store.saveMailSites(out)
        }
    }

    private fun setMailIcon(id: String, icon: String) {
        synchronized(ENGINE_LOCK) {
            val list = store.mailSites()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                if (o.optString("id") == id) o.put("icon", icon)
            }
            store.saveMailSites(list)
        }
    }

    // ---- downloads ----

    @JavascriptInterface
    fun downloadsLive(): String = act.downloadsJson()

    @JavascriptInterface
    fun openDownload(id: String) {
        act.runOnUiThread { act.openDownload(id) }
    }

    @JavascriptInterface
    fun deleteDownload(id: String) {
        act.runOnUiThread { act.deleteDownload(id) }
    }

    @JavascriptInterface
    fun retryDownload(id: String) {
        act.runOnUiThread { act.retryDownload(id) }
    }

    /** Returns "ok", or a short message that says what is wrong. */
    @JavascriptInterface
    fun downloadLink(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return "Paste a link first."
        if (u.startsWith("http://")) return "Only secure (https) links can be downloaded."
        if (!u.startsWith("https://")) u = "https://" + u
        if (u.length > 2000 || u.any { it.isWhitespace() }) return "That link is not valid."
        act.runOnUiThread { act.enqueueDownload(u, null, null, null) }
        return "ok"
    }

    @JavascriptInterface
    fun clear(key: String) {
        when (key) {
            "history" -> store.clearHistory()
            "downloads" -> store.clearDownloads()
            "all" -> act.runOnUiThread { act.clearBrowsingData() }
            "cookies" -> act.runOnUiThread { act.clearCookiesNow() }
            "cache" -> act.runOnUiThread { act.clearCacheNow() }
            "site" -> act.runOnUiThread { act.clearSiteDataNow() }
        }
    }

    // ---------- saved news ----------

    @JavascriptInterface
    fun savedNews(): String {
        val o = JSONObject()
        val f = File(act.filesDir, "news_saved.json")
        if (!f.exists()) return o.put("items", JSONArray()).put("savedAt", 0).toString()
        return try {
            val d = JSONObject(f.readText())
            val out = JSONObject()
            out.put("items", d.optJSONArray("items") ?: JSONArray())
            out.put("savedAt", store.newsSavedAt)
            out.toString()
        } catch (e: Exception) {
            o.put("items", JSONArray()).put("savedAt", 0).toString()
        }
    }

    @JavascriptInterface
    fun saveNews() {
        act.saveNewsNow { }
    }

    // ---------- about:phone ----------

    @JavascriptInterface
    fun phoneInfo(): String {
        val o = JSONObject()
        try {
            val bm = act.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            o.put("level", if (level in 0..100) level else 0)

            val i = act.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
            o.put("charging", charging)
            o.put("stateText", when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            })
            o.put("plugText", when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Not plugged in"
            })

            val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            o.put("tempC", if (temp > 0) temp / 10.0 else 0.0)
            val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            o.put("voltV", if (volt > 0) volt / 1000.0 else 0.0)
            o.put("tech", i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "")
            o.put("healthText", when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                else -> "—"
            })

            val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            o.put("chargeCounter", if (counter > 0) counter / 1000 else 0)
            val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            o.put("currentNow", if (current != Int.MIN_VALUE) current / 1000 else 0)
            val watts = if (current != Int.MIN_VALUE && volt > 0)
                (current / 1_000_000.0) * (volt / 1000.0) else 0.0
            o.put("watts", if (watts > 0) watts else 0.0)

            // Android only exposes these two on API 28+
            val full = bm.computeChargeTimeRemaining()
            o.put("timeToFull", if (full > 0) fmt(full) else "—")
            o.put("timeLeft", "—")

            o.put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            o.put("manufacturer", Build.MANUFACTURER)
            o.put("android", Build.VERSION.RELEASE)
            o.put("sdk", Build.VERSION.SDK_INT)
            o.put("cpu", Build.SUPPORTED_ABIS.firstOrNull() ?: "—")
            o.put("cores", Runtime.getRuntime().availableProcessors())
            val dm = act.resources.displayMetrics
            o.put("screen", "${dm.widthPixels} × ${dm.heightPixels} px")
            o.put("density", "${dm.densityDpi} dpi")
            val rt = Runtime.getRuntime()
            o.put("ram", "${(rt.maxMemory() / 1048576)} MB available to the app")

            val st = StatFs(act.filesDir.absolutePath)
            val total = st.blockCountLong * st.blockSizeLong
            val free = st.availableBlocksLong * st.blockSizeLong
            o.put("diskTotal", gb(total))
            o.put("diskFree", gb(free))
            o.put("diskUsed", gb(total - free))
        } catch (e: Exception) {
            o.put("error", e.message ?: "unknown")
        }
        return o.toString()
    }

    private fun fmt(ms: Long): String {
        val m = ms / 60000
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    private fun gb(b: Long): String = String.format("%.1f GB", b / 1073741824.0)

    // ---------- about:internet ----------

    @JavascriptInterface
    fun connection(): String {
        val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val n = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(n)
        return when {
            caps == null -> "Offline"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    }

    /**
     * Runs the speed test and returns the result as JSON.
     *
     * This blocks, which is safe here: Android calls a JavascriptInterface method
     * on a background thread, never on the UI thread.
     */
    @JavascriptInterface
    fun speedTest(): String {
        val o = JSONObject()
        try {
            // latency: five small requests, take the median
            val pings = ArrayList<Long>()
            for (i in 0 until 5) {
                val t0 = System.nanoTime()
                val c = URL("https://speed.cloudflare.com/__down?bytes=1000").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.inputStream.use { it.readBytes() }
                c.disconnect()
                pings.add((System.nanoTime() - t0) / 1_000_000)
            }
            pings.sort()
            val ping = pings[pings.size / 2]
            val jitter = if (pings.size > 1) pings.last() - pings.first() else 0
            o.put("ping", ping)
            o.put("jitter", jitter)

            // download: 10 MB, measured from first byte to last
            val dlBytes = 10 * 1024 * 1024
            val t1 = System.nanoTime()
            val dc = URL("https://speed.cloudflare.com/__down?bytes=$dlBytes").openConnection() as HttpURLConnection
            dc.connectTimeout = 15000
            dc.readTimeout = 30000
            var read = 0L
            dc.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    read += n
                }
            }
            dc.disconnect()
            val dlSec = (System.nanoTime() - t1) / 1_000_000_000.0
            val down = if (dlSec > 0) (read * 8.0 / dlSec) / 1_000_000.0 else 0.0
            o.put("down", down)

            // upload: 5 MB
            val upBytes = 5 * 1024 * 1024
            val payload = ByteArray(upBytes)
            val t2 = System.nanoTime()
            val uc = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
            uc.requestMethod = "POST"
            uc.doOutput = true
            uc.connectTimeout = 15000
            uc.readTimeout = 30000
            uc.setRequestProperty("Content-Type", "application/octet-stream")
            uc.setFixedLengthStreamingMode(upBytes)
            uc.outputStream.use { it.write(payload) }
            try {
                uc.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
            }
            uc.disconnect()
            val upSec = (System.nanoTime() - t2) / 1_000_000_000.0
            val up = if (upSec > 0) (upBytes * 8.0 / upSec) / 1_000_000.0 else 0.0
            o.put("up", up)
            o.put("bytes", ((read + upBytes) / 1048576.0).toInt())
            o.put("conn", connection())
        } catch (e: Exception) {
            o.put("error", e.message ?: "network error")
        }
        return o.toString()
    }

    // ---------- about:web ----------

    /** Checks a site and returns the result as JSON. Blocks on a background thread. */
    @JavascriptInterface
    fun checkSite(input: String): String {
        val o = JSONObject()
        try {
                var raw = input.trim()
                if (!raw.startsWith("http")) raw = "https://$raw"
                val t0 = System.nanoTime()
                var url = URL(raw)
                var conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                val status = conn.responseCode
                val ms = (System.nanoTime() - t0) / 1_000_000
                val finalUrl = conn.url.toString()
                val html = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    ""
                }
                val bytes = html.toByteArray().size

                o.put("status", status)
                o.put("ms", ms)
                o.put("bytes", bytes)
                o.put("finalUrl", finalUrl)
                o.put("https", finalUrl.startsWith("https://"))
                o.put("redirects", if (finalUrl != raw && finalUrl != "$raw/") 1 else 0)

                fun tag(re: Regex): String? = re.find(html)?.groupValues?.getOrNull(1)?.trim()
                val title = tag(Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) ?: ""
                val desc = tag(Regex("<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)", RegexOption.IGNORE_CASE))
                    ?: tag(Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+name=[\"']description[\"']", RegexOption.IGNORE_CASE)) ?: ""
                val canonical = tag(Regex("<link[^>]+rel=[\"']canonical[\"'][^>]+href=[\"']([^\"']*)", RegexOption.IGNORE_CASE))
                val lang = tag(Regex("<html[^>]+lang=[\"']([^\"']*)", RegexOption.IGNORE_CASE))
                val ogTitle = tag(Regex("<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']*)", RegexOption.IGNORE_CASE))
                val ogImage = tag(Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']*)", RegexOption.IGNORE_CASE))
                val viewport = tag(Regex("<meta[^>]+name=[\"']viewport[\"']", RegexOption.IGNORE_CASE))
                val h1 = Regex("<h1[^>]*>", RegexOption.IGNORE_CASE).findAll(html).count()
                val imgs = Regex("<img[^>]*>", RegexOption.IGNORE_CASE).findAll(html).toList()
                val noAlt = imgs.count { !it.value.contains("alt=", true) }

                o.put("title", title)
                o.put("desc", desc)
                o.put("canonical", canonical ?: "")
                o.put("lang", lang ?: "")
                o.put("ogTitle", ogTitle ?: "")
                o.put("ogImage", ogImage ?: "")
                o.put("viewport", viewport ?: "")
                o.put("h1", h1)
                o.put("imgNoAlt", noAlt)

                val h = conn.headerFields
                fun hdr(n: String) = h.entries.firstOrNull { it.key?.equals(n, true) == true }
                    ?.value?.firstOrNull() ?: ""
                o.put("hsts", hdr("Strict-Transport-Security"))
                o.put("nosniff", hdr("X-Content-Type-Options"))
                o.put("frame", hdr("X-Frame-Options"))
                o.put("csp", hdr("Content-Security-Policy"))
                conn.disconnect()

                // robots.txt and sitemap.xml
                val base = finalUrl.substringBefore("/", finalUrl).let {
                    val u = URL(finalUrl)
                    "${u.protocol}://${u.host}"
                }
                o.put("robots", exists("$base/robots.txt"))
                o.put("sitemap", exists("$base/sitemap.xml"))

                // score
                var score = 0
                if (status == 200) score += 15
                if (title.isNotEmpty()) score += 10
                if (title.length in 30..60) score += 5
                if (desc.isNotEmpty()) score += 10
                if (desc.length in 70..160) score += 5
                if (h1 == 1) score += 10
                if (canonical != null) score += 5
                if (lang != null) score += 5
                if (viewport != null) score += 10
                if (noAlt == 0) score += 5
                if (finalUrl.startsWith("https://")) score += 10
                if (ms < 1000) score += 5
                if (hdr("Strict-Transport-Security").isNotEmpty()) score += 5
                o.put("score", score.coerceIn(0, 100))
                o.put("verdict", when {
                    score >= 85 -> "Excellent. This page is well set up for search and sharing."
                    score >= 65 -> "Good, with a few things worth fixing."
                    score >= 40 -> "Needs work. Fix the red items first."
                    else -> "Poor. Search engines will struggle with this page."
                })
            } catch (e: Exception) {
                o.put("error", "Could not reach that site: ${e.message ?: "unknown error"}")
            }
        return o.toString()
    }

    private fun exists(u: String): Boolean = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.requestMethod = "HEAD"
        val ok = c.responseCode in 200..299
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }
}

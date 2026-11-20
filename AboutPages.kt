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
internal object AboutPages {

    /** Every page the address bar understands. */
    val NAMES = listOf(
        "blank", "home", "internet", "web", "phone", "bookmarks", "history",
        "downloads", "settings", "shield", "news", "version", "nirobo",
        "update", "charge", "battery", "sandbox"
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
        <h1>Downloads</h1>
        <p class="sub">Files this browser has downloaded.</p>
        <p><button class="ghost" onclick="clearAll()">Clear list</button></p>
        <div class="card" id="list"></div>
        <p class="hint">Files are saved to your phone's Downloads folder.</p>
        """
        val script = """
        function esc(s){return String(s).replace(/[&<>"]/g,function(c){
          return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
        function draw(){
          (function(json){
            var a; try{a=JSON.parse(json)}catch(e){a=[]}
            var el=document.getElementById('list');
            if(!a.length){el.innerHTML='<div class="empty">No downloads yet.</div>';return}
            var h='';
            for(var i=0;i<a.length;i++){
              h+='<div class="item"><div class="t">'+esc(a[i].n)+'</div>'+
                 '<div class="u">'+esc(a[i].u)+'</div></div>';
            }
            el.innerHTML=h;
          })(Bridge.downloads());
        }
        function clearAll(){Bridge.clearDownloads();draw()}
        draw();
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
            <div class="si" onclick="show('safe')"><div class="t"><b>Safety check</b><span id="s-safe">Updates and protection</span></div><div class="ch">›</div></div>
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
            <div class="si"><div class="t"><b>Updates</b><span id="upd">—</span></div></div>
            <div class="si"><div class="t"><b>Last checked</b><span id="updlast">—</span></div></div>
            <div class="si"><div class="t"><b>Check for updates now</b></div><button class="ghost" onclick="checkUpd()">Check</button></div>
            <div class="si"><div class="t"><b>Check when the app opens</b></div><div class="sw" id="sw-autoupdate" onclick="tog('autoupdate')"><i></i></div></div>
          </div>
          <div class="sg">
            <div class="si"><div class="t"><b>Ad shield</b><span id="safe-shield">—</span></div></div>
            <div class="si"><div class="t"><b>Only secure pages</b><span>Nirobo opens https pages only</span></div></div>
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
          var eng=engineOf(S.searchUrl);
          g('s-engine').textContent=NAMES[eng];
          g('s-bar').textContent=S.barBottom?'Bottom':'Top';
          g('s-priv').textContent=(S.shield?'Shield on':'Shield off')+(S.secure?' · screen locked':'');
          g('s-home').textContent=String(S.homeUrl).replace('https://','');
          g('s-app').textContent=['Follow phone','Light','Dark'][S.theme]||'';
          g('s-acc').textContent=S.textZoom+'% text';
          g('s-ver').textContent='Version '+S.version;
          g('safe-shield').textContent=S.shield?'On':'Off';
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
        function setBar(k){Bridge.set('bar',k);load()}
        function tog(k){Bridge.toggle(k);load()}
        function setTheme(v){Bridge.set('theme',v);load()}
        function setReader(v){Bridge.set('readerSize',v)}
        function setZoom(v){g('zlabel').textContent=v+'%';Bridge.set('textZoom',v)}
        function saveHome(){Bridge.set('homepage',g('home-in').value);load()}
        function clr(k){Bridge.clear(k);load()}
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
        o.put("textZoom", store.textZoom)
        o.put("newsSavedAt", if (store.newsSavedAt == 0L) "" else
            android.text.format.DateFormat.format("d MMM, HH:mm", store.newsSavedAt).toString())
        return o.toString()
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
    fun clear(key: String) {
        when (key) {
            "history" -> store.clearHistory()
            "downloads" -> store.clearDownloads()
            "all" -> act.runOnUiThread { act.clearBrowsingData() }
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

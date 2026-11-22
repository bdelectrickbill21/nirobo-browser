# Reference snippets (Chat B). Merge by hook; see CLAUDE.md for the plan.

All Kotlin page functions go inside `object AboutPages` (AboutPages.kt) next to the other page functions, and each needs: name in the page-name list, a `when` branch in `build()`.


## about:phone  (replace `private fun phone`) - charging screen

```kotlin
    private fun phone(dark: Boolean): String {
        val cardBg = if (dark) "#141A24" else "#FFFFFF"
        val cardLine = if (dark) "#262E3E" else "#D6DCE6"
        val badgeBg = if (dark) "#1F2736" else "#E8EDF5"
        val body = """
        <style>
        .cm{background:$cardBg;border:1px solid $cardLine;border-radius:28px;padding:18px;margin:14px 0}
        .tg{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin:12px 0}
        .tl{border-radius:20px;padding:14px 4px 12px;text-align:center;border:1px solid $cardLine;background:$cardBg;min-width:0}
        .tl .tv{font-size:19px;font-weight:700;margin-top:6px;word-break:break-word}
        .tl .tn{font-size:13px;opacity:.7;margin-top:2px}
        .tl .ts{font-size:13px;font-weight:600;margin-top:2px}
        .tl .ti{font-size:34px;line-height:40px}
        .cap{height:16px;border-radius:8px;display:flex;overflow:hidden;background:rgba(127,127,127,.18);margin:14px 0}
        .cap i{display:block;height:100%}
        .hatch{background:repeating-linear-gradient(135deg,rgba(127,127,127,.5) 0 3px,transparent 3px 7px)}
        .pill3{display:inline-block;padding:6px 14px;border-radius:16px;font-size:14px;font-weight:700}
        .ok2{color:#2BC47C}.wa2{color:#E8A93A}.bd2{color:#EE6257}
        .bdg{width:50px;height:50px;border-radius:25px;display:flex;align-items:center;justify-content:center;flex:none}
        .mrow{display:flex;align-items:center;gap:12px}
        .lg2{display:flex;align-items:center;gap:12px;padding:11px 0}
        .lg2 .k{flex:1;opacity:.75;font-size:16px}
        .lg2 .v{font-weight:700;font-size:16px}
        .sq{width:16px;height:16px;border-radius:5px;flex:none;box-sizing:border-box}
        .line{height:1px;background:$cardLine;margin:6px 0}
        </style>

        <div class="cm">
          <div style="display:flex;justify-content:space-between;align-items:center">
            <div style="display:flex;align-items:center;gap:12px">
              <svg width="40" height="22" viewBox="0 0 40 22"><rect x="1" y="2" width="33" height="18" rx="5" fill="none" stroke="currentColor" stroke-width="2" opacity=".8"/><rect x="35" y="7" width="3.5" height="8" rx="1.5" fill="currentColor" opacity=".8"/><rect id="bfill" x="4" y="5" width="0" height="12" rx="3" fill="currentColor"/></svg>
              <div><span id="pct" style="font-size:42px;font-weight:700">-</span><span style="font-size:20px;opacity:.7">%</span></div>
            </div>
            <div class="hint" id="pctd" style="font-size:15px">Battery</div>
          </div>
          <div id="g1" style="margin-top:6px"></div>
          <div style="text-align:center;margin-top:-4px"><span id="ma" style="font-size:66px;font-weight:300;letter-spacing:-1px">-</span><span style="font-size:24px;opacity:.7"> mA</span></div>
          <div style="display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:14px">
            <div class="mrow">
              <div class="bdg" style="background:#3A2227"><svg width="26" height="26" viewBox="0 0 26 26"><path d="M3 18a10 10 0 1 1 20 0" fill="none" stroke="#EE6257" stroke-width="2.4" stroke-linecap="round"/><path d="M13 18L18 10" stroke="#EE6257" stroke-width="2.4" stroke-linecap="round"/></svg></div>
              <div><div style="font-weight:700;font-size:20px" id="m1">Measuring...</div><div class="hint" id="m1s" style="font-size:15px"></div></div>
            </div>
            <div class="mrow">
              <div class="bdg" style="background:#1D2A44"><svg width="26" height="26" viewBox="0 0 26 26"><circle cx="13" cy="13" r="10" fill="none" stroke="#6AA7F0" stroke-width="2.4"/><path d="M13 7v6l4 3" fill="none" stroke="#6AA7F0" stroke-width="2.4" stroke-linecap="round"/></svg></div>
              <div><div style="font-weight:700;font-size:20px" id="m2">...</div><div class="hint" id="m2s" style="font-size:15px">Lasts about</div></div>
            </div>
          </div>
          <div class="line" style="margin-top:16px"></div>
          <div style="display:flex;justify-content:space-between;align-items:center;gap:10px">
            <div style="font-size:16px"><span style="opacity:.7">Lifetime max</span> <b id="lmax">-</b><br><span style="opacity:.7">Lifetime min</span> <b id="lmin">-</b></div>
            <div style="color:#5AA9FF;font-size:17px;padding:8px" onclick="Bridge.resetLife();refresh()">Reset</div>
          </div>
        </div>

        <div class="cm">
          <div style="display:flex;align-items:center;gap:14px">
            <div class="bdg" style="background:$badgeBg;border-radius:16px"><svg width="26" height="26" viewBox="0 0 26 26"><rect x="7" y="4" width="12" height="19" rx="3" fill="none" stroke="#F2C230" stroke-width="2"/><rect x="10" y="2" width="6" height="3" rx="1" fill="#F2C230"/><path d="M14 8l-3 6h3l-2 5" fill="none" stroke="#F2C230" stroke-width="1.8" stroke-linejoin="round"/></svg></div>
            <div style="flex:1;font-size:22px;font-weight:700">Battery capacity</div>
            <span class="pill3" id="hchip">...</span>
          </div>
          <div class="cap"><i id="c1" style="background:#7DB0F0;width:0"></i><i id="c2" style="background:#40506B;width:0"></i><i id="c3" class="hatch" style="width:0"></i></div>
          <div id="capRows"></div>
          <p class="hint" style="font-size:14px">From your phone's battery data. A dash means this phone does not share that number.</p>
        </div>

        <div class="tg" id="t1"></div>
        <h2>Device</h2>
        <div class="tg" id="t2"></div>

        <h2>More about this phone</h2>
        <div class="card" id="dev"></div>
        <h2>Storage</h2>
        <div class="card" id="disk"></div>
        """
        val script = """
        function g(i){return document.getElementById(i)}
        function row(k,v){return '<div class="row"><span class="k">'+k+'</span><span class="v">'+v+'</span></div>'}
        function pol(cx,cy,r,deg){var a=deg*Math.PI/180;return [cx+r*Math.cos(a),cy+r*Math.sin(a)]}
        function arc(cx,cy,r,a0,a1){
          var p0=pol(cx,cy,r,a0),p1=pol(cx,cy,r,a1);
          return 'M'+p0[0].toFixed(1)+' '+p0[1].toFixed(1)+' A'+r+' '+r+' 0 0 1 '+p1[0].toFixed(1)+' '+p1[1].toFixed(1)}
        function gauge(w,h,cx,cy,r,sw,segs,v,min,max,nw){
          var s='<svg viewBox="0 0 '+w+' '+h+'" width="100%" style="display:block">';
          for(var i=0;i<segs.length;i++){
            var a0=180+(segs[i][0]-min)/(max-min)*180,a1=180+(segs[i][1]-min)/(max-min)*180;
            s+='<path d="'+arc(cx,cy,r,a0,a1)+'" fill="none" stroke="'+segs[i][2]+'" stroke-width="'+sw+'"/>'}
          var c=Math.max(min,Math.min(max,v)),ang=180+(c-min)/(max-min)*180,p=pol(cx,cy,r-sw/2-2,ang);
          s+='<line x1="'+cx+'" y1="'+cy+'" x2="'+p[0].toFixed(1)+'" y2="'+p[1].toFixed(1)+'" stroke="currentColor" stroke-width="'+nw+'" stroke-linecap="round"/>';
          s+='<circle cx="'+cx+'" cy="'+cy+'" r="'+(nw+2)+'" fill="currentColor"/></svg>';
          return s}
        function kfmt(v){var a=Math.abs(v);return (v<0?'\u2212':'')+(a>=1000?(a/1000)+'k':a)}
        function bigGauge(ma,rng,use,ucol){
          var W=360,H=232,cx=180,cy=168,r=142,sw=40,s='<svg viewBox="0 0 '+W+' '+H+'" width="100%" style="display:block">';
          var segs=[[-rng,-rng*0.1,'#7B3436'],[-rng*0.1,0,'#FF4D3D'],[0,rng,'#17663A']];
          for(var i=0;i<segs.length;i++){
            var a0=180+(segs[i][0]+rng)/(2*rng)*180,a1=180+(segs[i][1]+rng)/(2*rng)*180;
            s+='<path d="'+arc(cx,cy,r,a0,a1)+'" fill="none" stroke="'+segs[i][2]+'" stroke-width="'+sw+'"/>'}
          var inner=r-sw/2;
          for(var v=-rng;v<=rng;v+=100){
            var ang=180+(v+rng)/(2*rng)*180,major=(v%500===0);
            var p0=pol(cx,cy,inner-6,ang),p1=pol(cx,cy,inner-(major?18:12),ang);
            s+='<line x1="'+p0[0].toFixed(1)+'" y1="'+p0[1].toFixed(1)+'" x2="'+p1[0].toFixed(1)+'" y2="'+p1[1].toFixed(1)+'" stroke="currentColor" stroke-width="'+(major?2.2:1.2)+'" opacity="'+(major?.7:.35)+'"/>';
            if(major){
              var t=pol(cx,cy,inner-36,ang);
              s+='<text x="'+t[0].toFixed(1)+'" y="'+(t[1]+4).toFixed(1)+'" font-size="14" text-anchor="middle" fill="currentColor" opacity=".75">'+kfmt(v)+'</text>'}}
          var c=Math.max(-rng,Math.min(rng,ma)),na=180+(c+rng)/(2*rng)*180,np=pol(cx,cy,inner-8,na);
          s+='<line x1="'+cx+'" y1="'+cy+'" x2="'+np[0].toFixed(1)+'" y2="'+np[1].toFixed(1)+'" stroke="currentColor" stroke-width="7" stroke-linecap="round"/>';
          s+='<circle cx="'+cx+'" cy="'+cy+'" r="15" fill="currentColor"/><circle cx="'+cx+'" cy="'+cy+'" r="7" fill="$cardBg"/>';
          s+='<text x="'+(cx-r+sw/2)+'" y="'+(cy+36)+'" font-size="17" text-anchor="middle" fill="#EE6257">Discharging</text>';
          s+='<text x="'+(cx+r-sw/2)+'" y="'+(cy+36)+'" font-size="17" text-anchor="middle" fill="currentColor" opacity=".7">Charging</text>';
          s+='<rect x="'+(cx-56)+'" y="'+(cy+22)+'" width="112" height="34" rx="17" fill="'+ucol+'" fill-opacity=".18"/>';
          s+='<text x="'+cx+'" y="'+(cy+45)+'" font-size="17" font-weight="700" text-anchor="middle" fill="'+ucol+'">'+use+'</text></svg>';
          return s}
        function hours(h){
          if(!isFinite(h)||h<=0)return '...';
          if(h>=48)return Math.round(h/24)+' days';
          if(h>=1)return Math.floor(h)+' h '+Math.round((h-Math.floor(h))*60)+' min';
          return Math.round(h*60)+' min'}
        function tileG(name,val,txt,cls,svg){
          return '<div class="tl">'+svg+'<div class="tv">'+val+'</div><div class="tn">'+name+'</div><div class="ts '+cls+'">'+txt+'</div></div>'}
        function tileI(name,val,icon,cls,txt){
          return '<div class="tl"><div class="ti">'+icon+'</div><div class="tn">'+name+'</div><div class="tv">'+val+'</div>'+(txt?'<div class="ts '+cls+'">'+txt+'</div>':'')+'</div>'}
        function legend(kind,label,val){
          var sq={fill:'background:#7DB0F0',ring:'border:2px solid #7DB0F0',dots:'border:2px dotted #8C95A6;border-radius:8px',hollow:'border:2px solid #8C95A6'}[kind];
          return '<div class="lg2"><div class="sq" style="'+sq+'"></div><div class="k">'+label+'</div><div class="v">'+val+'</div></div>'}
        function refresh(){
          var r;try{r=JSON.parse(Bridge.phoneInfo())}catch(e){return}
          var ma=r.currentMa||0,abs=Math.abs(ma);
          var rng=Math.max(1000,Math.ceil(abs/500)*500);
          var use,ucol;
          if(r.stateText==='Full'){use='Full';ucol='#2BC47C'}
          else if(ma>=2000){use='Fast charge';ucol='#2BC47C'}
          else if(ma>0){use='Charging';ucol='#2BC47C'}
          else if(abs<300){use='Light use';ucol='#2BC47C'}
          else if(abs<800){use='Normal use';ucol='#E8A93A'}
          else{use='Heavy use';ucol='#EE6257'}
          g('pct').textContent=r.level;
          g('bfill').setAttribute('width',Math.max(0,Math.min(29,r.level*29/100)).toFixed(1));
          g('pctd').textContent=r.charging?'Charging':'Battery';
          g('g1').innerHTML=bigGauge(ma,rng,use,ucol);
          g('ma').textContent=ma;
          g('m1').textContent=r.stateText;
          g('m1s').textContent=(r.watts?r.watts.toFixed(1)+' W':'');
          var t2='...',cap2='Lasts about';
          if(ma<0&&r.nowMah>0&&abs>5){t2=hours(r.nowMah/abs)}
          else if(ma>20&&r.fullMah>r.nowMah){t2=hours((r.fullMah-r.nowMah)/ma);cap2='Full in'}
          g('m2').textContent=t2;g('m2s').textContent=cap2;
          g('lmax').textContent=r.curMax?r.curMax+' mA':'-';
          g('lmin').textContent=r.curMin?r.curMin+' mA':'-';

          var design=r.designMah||0,full=r.fullMah||0,now=r.nowMah||0;
          var base=design>0?design:(full>0?full:1);
          var lost=design>0&&full>0?Math.max(0,design-full):0;
          g('c1').style.width=Math.min(100,now*100/base)+'%';
          g('c2').style.width=Math.max(0,Math.min(100,(full-now)*100/base))+'%';
          g('c3').style.width=Math.min(100,lost*100/base)+'%';
          var hp=r.healthPct||0;
          g('hchip').textContent=hp>0?('Health '+hp+'%'):'Health -';
          g('hchip').style.color=hp>=80?'#2BC47C':(hp>0?'#E8A93A':'inherit');
          g('hchip').style.background=hp>=80?'rgba(43,196,124,.16)':'rgba(232,169,58,.16)';
          var c='';
          c+=legend('fill','In the battery now',now>0?now.toLocaleString()+' mAh \u00B7 '+r.level+'%':'-');
          c+=legend('ring','Holds when full, today',full>0?full.toLocaleString()+' mAh':'-');
          c+=legend('dots','Lost to battery wear',lost>0?lost.toLocaleString()+' mAh':'-');
          c+='<div class="line"></div>';
          c+=legend('hollow','Design capacity (maker)',design>0?design.toLocaleString()+' mAh':'-');
          g('capRows').innerHTML=c;

          var v=r.voltV||0,tc=r.tempC||0,w=r.watts||0;
          var vseg=[[3.0,3.5,'#EE6257'],[3.5,3.7,'#E3C928'],[3.7,4.25,'#2BC47C'],[4.25,4.45,'#E3C928']];
          var tseg=[[0,15,'#4AA8E8'],[15,38,'#2BC47C'],[38,45,'#E8A93A'],[45,60,'#EE6257']];
          var lseg=[[0,20,'#EE6257'],[20,40,'#E8903A'],[40,60,'#DCC62A'],[60,80,'#9BBF2C'],[80,100,'#2BC47C']];
          var pseg=[[-8,0,'#EE6257'],[0,8,'#2BC47C']];
          var wsig=r.charging?Math.abs(w):-Math.abs(w);
          var h1='';
          h1+=tileG('Voltage',v?v.toFixed(2)+' V':'-',(v>=3.5&&v<=4.45)?'Normal':'Check',(v>=3.5&&v<=4.45)?'ok2':'wa2',gauge(100,60,50,52,40,11,vseg,v,3.0,4.45,3));
          h1+=tileG('Temperature',tc?tc.toFixed(1)+' \u00B0C':'-',tc<38?'Normal':(tc<45?'Warm':'Hot'),tc<38?'ok2':(tc<45?'wa2':'bd2'),gauge(100,60,50,52,40,11,tseg,tc,0,60,3));
          h1+=tileG('Level',r.level+'%',r.level>=60?'Good':(r.level>=20?'Okay':'Low'),r.level>=60?'ok2':(r.level>=20?'wa2':'bd2'),gauge(100,60,50,52,40,11,lseg,r.level,0,100,3));
          h1+=tileG('Power',(wsig>=0?'':'-')+Math.abs(w).toFixed(2)+' W',use,ucol==='#2BC47C'?'ok2':(ucol==='#E8A93A'?'wa2':'bd2'),gauge(100,60,50,52,40,11,pseg,wsig,-8,8,3));
          h1+=tileI('Status',r.stateText,'\uD83D\uDD0B','','');
          h1+=tileI('Plugged',r.plugText==='Not plugged in'?'On battery':r.plugText,'\uD83D\uDD0C','','');
          h1+=tileI('Health',hp>0?hp+'%':(r.healthText||'-'),'\u2764\uFE0F',hp>=80||hp===0?'ok2':'wa2',hp>0?(hp>=80?'Good':'Worn'):'');
          h1+=tileI('Technology',r.tech||'-','\uD83D\uDCA1','','');
          h1+=tileI('Charge cycles',(r.cycles>=0)?r.cycles:'-','\uD83D\uDD04','','');
          g('t1').innerHTML=h1;
          var h2='';
          h2+=tileI('Android',r.android,'\uD83E\uDD16','','');
          h2+=tileI('Maker',r.manufacturer,'\uD83C\uDFED','','');
          h2+=tileI('Model',r.model.replace(r.manufacturer+' ',''),'\uD83D\uDCF1','','');
          g('t2').innerHTML=h2;

          var d='';
          d+=row('SDK',r.sdk);d+=row('CPU',r.cpu);d+=row('Cores',r.cores);
          d+=row('Screen',r.screen);d+=row('Density',r.density);d+=row('RAM',r.ram);
          g('dev').innerHTML=d;
          var s='';
          s+=row('Total',r.diskTotal);s+=row('Free',r.diskFree);s+=row('Used',r.diskUsed);
          g('disk').innerHTML=s}
        refresh();
        setInterval(refresh,2000);
        """
        return page("Charge", dark, body, script)
    }


```


## about:check  (new page)

```kotlin
    private fun check(dark: Boolean): String {
        val body = """
        <style>
        h3{font-size:17px;margin:22px 4px 8px}
        .in{width:100%;font:inherit;padding:12px;border-radius:11px;border:1px solid rgba(127,127,127,.4);background:transparent;color:inherit;box-sizing:border-box}
        .step{display:flex;gap:10px;align-items:flex-start;padding:9px 0;border-top:1px solid rgba(127,127,127,.18)}
        .step:first-child{border-top:0}
        .step .n{width:24px;height:24px;border-radius:12px;background:rgba(127,127,127,.25);display:flex;align-items:center;justify-content:center;font-size:13px;flex:none}
        .step .u{flex:1;min-width:0;word-break:break-all;font-size:14px}
        .code{font-size:12px;padding:3px 9px;border-radius:10px;background:rgba(127,127,127,.2);flex:none}
        .fd{display:flex;gap:10px;align-items:flex-start;padding:10px 0;border-top:1px solid rgba(127,127,127,.18)}
        .fd:first-child{border-top:0}
        .fd .t{flex:1;min-width:0;line-height:1.4}
        .tag{font-size:12px;font-weight:700;padding:4px 10px;border-radius:12px;flex:none}
        .tag.info{background:rgba(127,127,127,.2)}
        .tag.warn{background:rgba(232,169,58,.2);color:#E8A93A}
        .tag.bad{background:rgba(238,98,87,.2);color:#EE6257}
        </style>
        <h1>Link check</h1>
        <p class="sub">Follows every redirect step by step without opening the page, then looks for traps: gambling, 18+, viruses, files that download by themselves and fake copies (mirrors) of known sites.</p>
        <div class="card">
          <input class="in" id="u" type="url" placeholder="Paste a link" autocapitalize="off" autocomplete="off">
          <div style="height:10px"></div>
          <button id="go" onclick="run()">Check link</button>
          <p class="hint" id="st">Nirobo visits the link once from this phone, with no cookies. The website can see your phone's internet address, like with any visit.</p>
        </div>
        <div id="res"></div>
        """
        val script = """
        function g(i){return document.getElementById(i)}
        function mk(tag,cls,text){var e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined)e.textContent=text;return e}
        var timer=null;
        function run(){
          var u=g('u').value.trim();if(!u)return;
          g('res').innerHTML='';
          g('st').textContent='Checking... this can take up to 40 seconds.';
          g('go').disabled=true;
          var id=Bridge.startCheck(u),tries=0;
          clearInterval(timer);
          timer=setInterval(function(){
            tries++;
            var r=Bridge.checkResult(id);
            if(r){clearInterval(timer);g('go').disabled=false;
              var o=null;try{o=JSON.parse(r)}catch(e){}
              if(o)show(o);else g('st').textContent='The check gave no answer.'}
            else if(tries>80){clearInterval(timer);g('go').disabled=false;
              g('st').textContent='This is taking too long. The website may be very slow or may block checks. That alone is a reason to be careful.'}
          },500)}
        function show(r){
          var box=g('res');box.innerHTML='';
          if(!r.ok){g('st').textContent=r.error||'The check failed.';return}
          g('st').textContent='Done. No check finds everything, so keep your own care too.';
          var V={ok:['Nothing unusual found','#2BC47C'],careful:['Be careful','#E8A93A'],risky:['Risky: do not open unless you trust it','#E8903A'],bad:['Dangerous: do not open this link','#EE6257']}[r.verdict];
          var card=mk('div','card');
          var h=mk('div','',V[0]);h.style.fontSize='22px';h.style.fontWeight='700';h.style.color=V[1];
          card.appendChild(h);
          if(r.title){var t=mk('div','hint','Page title: '+r.title);t.style.marginTop='6px';card.appendChild(t)}
          box.appendChild(card);

          var fc=mk('div','card');
          if(!r.findings.length){fc.appendChild(mk('div','hint','No warning signs were found in the path, the address or the page.'))}
          for(var i=0;i<r.findings.length;i++){
            var f=r.findings[i],row=mk('div','fd');
            row.appendChild(mk('div','tag '+f.l,f.l==='bad'?'Risk':(f.l==='warn'?'Check':'Info')));
            row.appendChild(mk('div','t',f.t));fc.appendChild(row)}
          box.appendChild(mk('h3','','What was found'));box.appendChild(fc);

          var cc=mk('div','card');
          for(var j=0;j<r.chain.length;j++){
            var s=mk('div','step');
            s.appendChild(mk('div','n',String(j+1)));
            s.appendChild(mk('div','u',r.chain[j].u));
            s.appendChild(mk('div','code',r.chain[j].c?String(r.chain[j].c):'-'));
            cc.appendChild(s)}
          box.appendChild(mk('h3','',r.chain.length>1?'The path ('+(r.chain.length-1)+' redirect'+(r.chain.length>2?'s':'')+')':'The path'));
          box.appendChild(cc);

          var fin=mk('div','card');
          var fu=mk('div','',r.final);fu.style.wordBreak='break-all';fu.style.fontSize='14px';fin.appendChild(fu);
          if(r.type){var ty=mk('div','hint','Type: '+r.type);ty.style.marginTop='6px';fin.appendChild(ty)}
          var web=/^https?:/i.test(r.final);
          var bx=mk('div');bx.style.marginTop='12px';
          function btn(label,ghost,fn){var b=mk('button',ghost?'ghost':'',label);b.style.marginRight='8px';b.style.marginBottom='8px';b.onclick=fn;bx.appendChild(b)}
          if(web){
            btn('Open in sandbox',r.verdict==='ok'?true:false,function(){Bridge.openSandbox(r.final)});
            btn('Open normally',true,function(){Bridge.open(r.final)})}
          btn('Copy final link',true,function(){Bridge.copyText(r.final)});
          fin.appendChild(bx);
          box.appendChild(mk('h3','','Where it ends'));box.appendChild(fin)}
        var pre=Bridge.checkTarget();
        if(pre){g('u').value=pre;run()}
        """
        return page("Link check", dark, body, script)
    }


```


## about:qr  (new page, contains the tested JS QR maker)

```kotlin
    private fun qr(dark: Boolean): String {
        val body = """
        <h1>QR code</h1>
        <p class="sub">Scan this with another phone to open the page there.</p>
        <div class="card" style="text-align:center">
          <div id="qr" style="background:#fff;color:#000;border-radius:14px;padding:12px;display:inline-block;max-width:100%"></div>
          <p class="hint" id="addr" style="word-break:break-all"></p>
        </div>
        <p class="hint">The code is made on your phone. Nothing is sent anywhere.</p>
        """
        val script = """
        function g(i){return document.getElementById(i)}
// Small QR code maker: byte mode, error correction L or M, versions 1 to 10.
var QR_ECC_PER_BLOCK = { L: [7, 10, 15, 20, 26, 18, 20, 24, 30, 18], M: [10, 16, 26, 18, 24, 16, 18, 22, 22, 26] };
var QR_NUM_BLOCKS = { L: [1, 1, 1, 1, 1, 2, 2, 2, 2, 4], M: [1, 1, 1, 2, 2, 4, 4, 4, 5, 5] };
var QR_FORMAT_BITS = { L: 1, M: 0 };

function qrRawModules(ver) {
  var r = (16 * ver + 128) * ver + 64;
  if (ver >= 2) {
    var na = Math.floor(ver / 7) + 2;
    r -= (25 * na - 10) * na - 55;
    if (ver >= 7) r -= 36;
  }
  return r;
}
function qrDataCodewords(ver, ecl) {
  return Math.floor(qrRawModules(ver) / 8) - QR_ECC_PER_BLOCK[ecl][ver - 1] * QR_NUM_BLOCKS[ecl][ver - 1];
}
function qrMul(x, y) {
  var z = 0;
  for (var i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11D);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}
function qrDivisor(deg) {
  var res = [];
  for (var i = 0; i < deg; i++) res.push(0);
  res[deg - 1] = 1;
  var root = 1;
  for (var i = 0; i < deg; i++) {
    for (var j = 0; j < deg; j++) {
      res[j] = qrMul(res[j], root);
      if (j + 1 < deg) res[j] ^= res[j + 1];
    }
    root = qrMul(root, 2);
  }
  return res;
}
function qrRemainder(data, div) {
  var res = div.map(function () { return 0; });
  for (var k = 0; k < data.length; k++) {
    var f = data[k] ^ res.shift();
    res.push(0);
    for (var i = 0; i < div.length; i++) res[i] ^= qrMul(div[i], f);
  }
  return res;
}
function qrAlign(ver) {
  if (ver === 1) return [];
  var na = Math.floor(ver / 7) + 2;
  var step = Math.ceil((ver * 4 + 4) / (na * 2 - 2)) * 2;
  var size = ver * 4 + 17;
  var res = [6];
  for (var pos = size - 7; res.length < na; pos -= step) res.splice(1, 0, pos);
  return res;
}
function qrMake(text, ecl) {
  ecl = ecl || 'M';
  var enc = unescape(encodeURIComponent(text));
  var bytes = [];
  for (var i = 0; i < enc.length; i++) bytes.push(enc.charCodeAt(i));
  var ver = 0;
  for (var v = 1; v <= 10; v++) {
    var ccBits = v <= 9 ? 8 : 16;
    if (4 + ccBits + bytes.length * 8 <= qrDataCodewords(v, ecl) * 8) { ver = v; break; }
  }
  if (!ver) return null;
  var bits = [];
  function put(val, len) { for (var i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); }
  put(4, 4);
  put(bytes.length, ver <= 9 ? 8 : 16);
  for (var i = 0; i < bytes.length; i++) put(bytes[i], 8);
  var cap = qrDataCodewords(ver, ecl) * 8;
  for (var i = 0; i < 4 && bits.length < cap; i++) bits.push(0);
  while (bits.length % 8) bits.push(0);
  var data = [];
  for (var i = 0; i < bits.length; i += 8) {
    var b = 0;
    for (var j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
    data.push(b);
  }
  for (var pad = 0xEC; data.length < cap / 8; pad ^= 0xEC ^ 0x11) data.push(pad);

  // error correction and interleaving
  var nb = QR_NUM_BLOCKS[ecl][ver - 1], el = QR_ECC_PER_BLOCK[ecl][ver - 1];
  var raw = Math.floor(qrRawModules(ver) / 8);
  var nShort = nb - raw % nb, shortLen = Math.floor(raw / nb);
  var blocks = [], div = qrDivisor(el);
  for (var i = 0, k = 0; i < nb; i++) {
    var dat = data.slice(k, k + shortLen - el + (i < nShort ? 0 : 1));
    k += dat.length;
    var ecc = qrRemainder(dat, div);
    if (i < nShort) dat.push(0);
    blocks.push(dat.concat(ecc));
  }
  var all = [];
  for (var i = 0; i < blocks[0].length; i++) {
    for (var j = 0; j < blocks.length; j++) {
      if (i !== shortLen - el || j >= nShort) all.push(blocks[j][i]);
    }
  }

  // draw
  var size = ver * 4 + 17, m = [], fn = [];
  for (var y = 0; y < size; y++) {
    m.push([]); fn.push([]);
    for (var x = 0; x < size; x++) { m[y].push(false); fn[y].push(false); }
  }
  function set(x, y, d) { m[y][x] = d; fn[y][x] = true; }
  for (var i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
  function finder(cx, cy) {
    for (var dy = -4; dy <= 4; dy++) for (var dx = -4; dx <= 4; dx++) {
      var d = Math.max(Math.abs(dx), Math.abs(dy)), xx = cx + dx, yy = cy + dy;
      if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, d !== 2 && d !== 4);
    }
  }
  finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
  var ap = qrAlign(ver), na = ap.length;
  for (var i = 0; i < na; i++) for (var j = 0; j < na; j++) {
    if ((i === 0 && j === 0) || (i === 0 && j === na - 1) || (i === na - 1 && j === 0)) continue;
    for (var dy = -2; dy <= 2; dy++) for (var dx = -2; dx <= 2; dx++) {
      set(ap[i] + dx, ap[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }
  }
  function bit(x, i) { return ((x >>> i) & 1) !== 0; }
  function format(mask) {
    var d = (QR_FORMAT_BITS[ecl] << 3) | mask, rem = d;
    for (var i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    var bt = ((d << 10) | rem) ^ 0x5412;
    for (var i = 0; i <= 5; i++) set(8, i, bit(bt, i));
    set(8, 7, bit(bt, 6)); set(8, 8, bit(bt, 7)); set(7, 8, bit(bt, 8));
    for (var i = 9; i < 15; i++) set(14 - i, 8, bit(bt, i));
    for (var i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bt, i));
    for (var i = 8; i < 15; i++) set(8, size - 15 + i, bit(bt, i));
    set(8, size - 8, true);
  }
  format(0);
  if (ver >= 7) {
    var rem = ver;
    for (var i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
    var vb = (ver << 12) | rem;
    for (var i = 0; i < 18; i++) {
      var b = bit(vb, i), a = size - 11 + i % 3, c = Math.floor(i / 3);
      set(a, c, b); set(c, a, b);
    }
  }
  var idx = 0;
  for (var right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (var vert = 0; vert < size; vert++) for (var j = 0; j < 2; j++) {
      var x = right - j, up = ((right + 1) & 2) === 0, y = up ? size - 1 - vert : vert;
      if (!fn[y][x] && idx < all.length * 8) { m[y][x] = bit(all[idx >>> 3], 7 - (idx & 7)); idx++; }
    }
  }
  var mask = 0;
  for (var y = 0; y < size; y++) for (var x = 0; x < size; x++) {
    if (!fn[y][x] && (x + y) % 2 === 0) m[y][x] = !m[y][x];
  }
  format(mask);
  return { size: size, version: ver, modules: m };
}

        var text=Bridge.qrText();
        g('addr').textContent=text;
        var q=qrMake(text,'M')||qrMake(text,'L');
        if(!q){g('qr').textContent='This link is too long for a QR code.'}
        else{
          var n=q.size,quiet=4,s=n+quiet*2,d='';
          for(var y=0;y<n;y++)for(var x=0;x<n;x++){if(q.modules[y][x])d+='M'+(x+quiet)+' '+(y+quiet)+'h1v1h-1z'}
          g('qr').innerHTML='<svg viewBox="0 0 '+s+' '+s+'" width="280" height="280" shape-rendering="crispEdges" style="max-width:100%"><rect width="'+s+'" height="'+s+'" fill="#fff"/><path d="'+d+'" fill="#000"/></svg>';
        }
        """
        return page("QR code", dark, body, script)
    }


```


## about:news  (replace `private fun news`) - country picker + listen

```kotlin
    private fun news(dark: Boolean): String {
        val body = """
        <style>
        .ncard{padding:14px;margin-bottom:14px;overflow:hidden}
        .nimg{display:none;margin:-14px -14px 12px;background:rgba(127,127,127,.15)}
        .nimg img{width:100%;display:block;max-height:230px;object-fit:cover}
        .nt{font-size:18px;font-weight:700;line-height:1.3;margin-bottom:4px}
        .nm{font-size:13px;opacity:.65;margin-bottom:8px}
        .nd{font-size:15px;line-height:1.5;white-space:pre-wrap;word-break:break-word;margin-bottom:12px}
        .in{width:100%;font:inherit;padding:12px;border-radius:11px;border:1px solid rgba(127,127,127,.4);background:transparent;color:inherit;box-sizing:border-box}
        .lb{font-size:12px;opacity:.7;margin:0 0 4px}
        </style>
        <h1>Saved news</h1>
        <p class="sub" id="sub">The copy Nirobo keeps on this phone. It works with no internet.</p>
        <div class="card">
          <div class="lb">Country</div>
          <select class="in" id="cc" onchange="pick()"></select>
          <p style="margin:12px 0 0">
            <button onclick="again()">Download now</button>
            <button class="ghost" onclick="listenAll()">Listen to all</button>
            <button class="ghost" onclick="Bridge.stopSpeak()">Stop</button>
          </p>
          <p class="hint" id="nst"></p>
        </div>
        <div id="out"><div class="empty">Loading...</div></div>
        """
        val script = """
        function g(i){return document.getElementById(i)}
        function esc(s){return String(s).replace(/[&<>\"]/g,function(c){
          return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]})}
        function ago(ts){
          if(!ts)return 'never';
          var d=new Date(ts),diff=(new Date()-d)/1000;
          if(diff<60)return 'just now';
          if(diff<3600)return Math.floor(diff/60)+' min ago';
          if(diff<86400)return Math.floor(diff/3600)+' h ago';
          return Math.floor(diff/86400)+' d ago';
        }
        function open_(u){Bridge.open(decodeURIComponent(u))}
        var items=[],q=[],poll=null;
        function nextImg(){
          if(!q.length)return;
          var i=q.shift();
          var u='';try{u=Bridge.newsImage(items[i].i)}catch(e){}
          var el=document.getElementById('ni-'+i);
          if(u&&el){var im=document.createElement('img');im.src=u;el.appendChild(im);el.style.display='block'}
          setTimeout(nextImg,25)}
        function say(i){
          var x=items[i];
          Bridge.speak((x.t||'')+'. '+(x.d||'').substring(0,1500))}
        function listenAll(){
          if(!items.length)return;
          var a=[];
          for(var i=0;i<items.length&&i<15;i++){a.push((items[i].t||'')+'. '+(items[i].d||'').substring(0,400))}
          Bridge.speakMany(JSON.stringify(a))}
        function render(){
          var r;try{r=JSON.parse(Bridge.savedNews())}catch(e){
            g('out').innerHTML='<div class="empty">No saved copy.</div>';return}
          if(!r.items||!r.items.length){
            g('out').innerHTML='<div class="empty">No saved copy yet.<br>Connect to the internet once and it saves itself, or tap Download now.</div>';
            g('sub').textContent='Nothing saved yet.';
            return;
          }
          items=r.items;q=[];
          g('sub').textContent=items.length+' stories - saved '+ago(r.savedAt)+(r.country&&r.country!=='BD'?' - headlines only, no pictures':'');
          var h='';
          for(var i=0;i<items.length;i++){
            var x=items[i];
            h+='<div class="card ncard">'+
               (x.i?'<div class="nimg" id="ni-'+i+'"></div>':'')+
               '<div class="nt">'+esc(x.t)+'</div>'+
               '<div class="nm">'+esc(x.s||'')+'</div>'+
               (x.d?'<div class="nd">'+esc(x.d)+'</div>':'')+
               '<button onclick="open_(\''+encq(x.u)+'\')">Read full story</button> '+
               '<button class="ghost" onclick="say('+i+')">Listen</button></div>';
            if(x.i)q.push(i);
          }
          g('out').innerHTML=h;
          nextImg();
        }
        function watch(){
          clearInterval(poll);
          poll=setInterval(function(){
            var s=Bridge.newsStatus();
            if(s==='downloading'){g('nst').textContent='Downloading...';return}
            clearInterval(poll);
            if(s==='ok'){g('nst').textContent='Saved.';render()}
            else if(s.indexOf('error')===0){g('nst').textContent='Could not download ('+s.substring(7)+'). Showing the last saved copy.'}
            else{g('nst').textContent=''}
          },700)}
        function pick(){
          var r=Bridge.setNewsCountry(g('cc').value);
          if(r==='started'){g('nst').textContent='Downloading...';watch()}
          else if(r==='busy'){g('nst').textContent='Another download is running. Try again in a moment.'}
        }
        function again(){
          var r=Bridge.redownloadNews();
          if(r==='started'){g('nst').textContent='Downloading...';watch()}
          else{g('nst').textContent='Another download is running. Try again in a moment.'}
        }
        (function(){
          var C=[];try{C=JSON.parse(Bridge.newsCountries())}catch(e){}
          var cur=Bridge.newsCountry(),sel=g('cc');
          for(var i=0;i<C.length;i++){
            var o=document.createElement('option');o.value=C[i].cc;o.textContent=C[i].name;
            if(C[i].cc===cur)o.selected=true;sel.appendChild(o)}
          if(cur==='BD'){g('nst').textContent='Bangladesh uses Nirobo\'s own news: full stories with pictures. Other countries show national headlines.'}
          render();
        })();
        """
        return page("Saved news", dark, body, script)
    }


```


## qr.js standalone (tested: 6 of 6 decoded by OpenCV, versions 1-9, UTF-8)

```javascript
// Small QR code maker: byte mode, error correction L or M, versions 1 to 10.
var QR_ECC_PER_BLOCK = { L: [7, 10, 15, 20, 26, 18, 20, 24, 30, 18], M: [10, 16, 26, 18, 24, 16, 18, 22, 22, 26] };
var QR_NUM_BLOCKS = { L: [1, 1, 1, 1, 1, 2, 2, 2, 2, 4], M: [1, 1, 1, 2, 2, 4, 4, 4, 5, 5] };
var QR_FORMAT_BITS = { L: 1, M: 0 };

function qrRawModules(ver) {
  var r = (16 * ver + 128) * ver + 64;
  if (ver >= 2) {
    var na = Math.floor(ver / 7) + 2;
    r -= (25 * na - 10) * na - 55;
    if (ver >= 7) r -= 36;
  }
  return r;
}
function qrDataCodewords(ver, ecl) {
  return Math.floor(qrRawModules(ver) / 8) - QR_ECC_PER_BLOCK[ecl][ver - 1] * QR_NUM_BLOCKS[ecl][ver - 1];
}
function qrMul(x, y) {
  var z = 0;
  for (var i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11D);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}
function qrDivisor(deg) {
  var res = [];
  for (var i = 0; i < deg; i++) res.push(0);
  res[deg - 1] = 1;
  var root = 1;
  for (var i = 0; i < deg; i++) {
    for (var j = 0; j < deg; j++) {
      res[j] = qrMul(res[j], root);
      if (j + 1 < deg) res[j] ^= res[j + 1];
    }
    root = qrMul(root, 2);
  }
  return res;
}
function qrRemainder(data, div) {
  var res = div.map(function () { return 0; });
  for (var k = 0; k < data.length; k++) {
    var f = data[k] ^ res.shift();
    res.push(0);
    for (var i = 0; i < div.length; i++) res[i] ^= qrMul(div[i], f);
  }
  return res;
}
function qrAlign(ver) {
  if (ver === 1) return [];
  var na = Math.floor(ver / 7) + 2;
  var step = Math.ceil((ver * 4 + 4) / (na * 2 - 2)) * 2;
  var size = ver * 4 + 17;
  var res = [6];
  for (var pos = size - 7; res.length < na; pos -= step) res.splice(1, 0, pos);
  return res;
}
function qrMake(text, ecl) {
  ecl = ecl || 'M';
  var enc = unescape(encodeURIComponent(text));
  var bytes = [];
  for (var i = 0; i < enc.length; i++) bytes.push(enc.charCodeAt(i));
  var ver = 0;
  for (var v = 1; v <= 10; v++) {
    var ccBits = v <= 9 ? 8 : 16;
    if (4 + ccBits + bytes.length * 8 <= qrDataCodewords(v, ecl) * 8) { ver = v; break; }
  }
  if (!ver) return null;
  var bits = [];
  function put(val, len) { for (var i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); }
  put(4, 4);
  put(bytes.length, ver <= 9 ? 8 : 16);
  for (var i = 0; i < bytes.length; i++) put(bytes[i], 8);
  var cap = qrDataCodewords(ver, ecl) * 8;
  for (var i = 0; i < 4 && bits.length < cap; i++) bits.push(0);
  while (bits.length % 8) bits.push(0);
  var data = [];
  for (var i = 0; i < bits.length; i += 8) {
    var b = 0;
    for (var j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
    data.push(b);
  }
  for (var pad = 0xEC; data.length < cap / 8; pad ^= 0xEC ^ 0x11) data.push(pad);

  // error correction and interleaving
  var nb = QR_NUM_BLOCKS[ecl][ver - 1], el = QR_ECC_PER_BLOCK[ecl][ver - 1];
  var raw = Math.floor(qrRawModules(ver) / 8);
  var nShort = nb - raw % nb, shortLen = Math.floor(raw / nb);
  var blocks = [], div = qrDivisor(el);
  for (var i = 0, k = 0; i < nb; i++) {
    var dat = data.slice(k, k + shortLen - el + (i < nShort ? 0 : 1));
    k += dat.length;
    var ecc = qrRemainder(dat, div);
    if (i < nShort) dat.push(0);
    blocks.push(dat.concat(ecc));
  }
  var all = [];
  for (var i = 0; i < blocks[0].length; i++) {
    for (var j = 0; j < blocks.length; j++) {
      if (i !== shortLen - el || j >= nShort) all.push(blocks[j][i]);
    }
  }

  // draw
  var size = ver * 4 + 17, m = [], fn = [];
  for (var y = 0; y < size; y++) {
    m.push([]); fn.push([]);
    for (var x = 0; x < size; x++) { m[y].push(false); fn[y].push(false); }
  }
  function set(x, y, d) { m[y][x] = d; fn[y][x] = true; }
  for (var i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
  function finder(cx, cy) {
    for (var dy = -4; dy <= 4; dy++) for (var dx = -4; dx <= 4; dx++) {
      var d = Math.max(Math.abs(dx), Math.abs(dy)), xx = cx + dx, yy = cy + dy;
      if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, d !== 2 && d !== 4);
    }
  }
  finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
  var ap = qrAlign(ver), na = ap.length;
  for (var i = 0; i < na; i++) for (var j = 0; j < na; j++) {
    if ((i === 0 && j === 0) || (i === 0 && j === na - 1) || (i === na - 1 && j === 0)) continue;
    for (var dy = -2; dy <= 2; dy++) for (var dx = -2; dx <= 2; dx++) {
      set(ap[i] + dx, ap[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }
  }
  function bit(x, i) { return ((x >>> i) & 1) !== 0; }
  function format(mask) {
    var d = (QR_FORMAT_BITS[ecl] << 3) | mask, rem = d;
    for (var i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    var bt = ((d << 10) | rem) ^ 0x5412;
    for (var i = 0; i <= 5; i++) set(8, i, bit(bt, i));
    set(8, 7, bit(bt, 6)); set(8, 8, bit(bt, 7)); set(7, 8, bit(bt, 8));
    for (var i = 9; i < 15; i++) set(14 - i, 8, bit(bt, i));
    for (var i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bt, i));
    for (var i = 8; i < 15; i++) set(8, size - 15 + i, bit(bt, i));
    set(8, size - 8, true);
  }
  format(0);
  if (ver >= 7) {
    var rem = ver;
    for (var i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
    var vb = (ver << 12) | rem;
    for (var i = 0; i < 18; i++) {
      var b = bit(vb, i), a = size - 11 + i % 3, c = Math.floor(i / 3);
      set(a, c, b); set(c, a, b);
    }
  }
  var idx = 0;
  for (var right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (var vert = 0; vert < size; vert++) for (var j = 0; j < 2; j++) {
      var x = right - j, up = ((right + 1) & 2) === 0, y = up ? size - 1 - vert : vert;
      if (!fn[y][x] && idx < all.length * 8) { m[y][x] = bit(all[idx >>> 3], 7 - (idx & 7)); idx++; }
    }
  }
  var mask = 0;
  for (var y = 0; y < size; y++) for (var x = 0; x < size; x++) {
    if (!fn[y][x] && (x + y) % 2 === 0) m[y][x] = !m[y][x];
  }
  format(mask);
  return { size: size, version: ver, modules: m };
}

```


## NewsSaver.kt with country support (full file, replaces current)

```kotlin
package org.nirobo.browser

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Keeps a copy of NewsHub on the phone.
 *
 * It downloads https://nirobo.pages.dev/data/news.json once a day, at UTC
 * midnight, and keeps the newest stories. When the phone has no internet the
 * browser shows this copy instead of an error page.
 *
 * Two things trigger a save:
 *  - an alarm set for the next UTC midnight (works even if the app is closed);
 *  - a check whenever the app comes to the front, which catches a day the
 *    phone was off or the alarm was missed.
 */
internal object NewsSaver {

    private const val FILE = "news_saved.json"
    private const val URL_NEWS = "https://nirobo.pages.dev/data/news.json"
    private const val KEEP = 120
    private const val ALARM_REQUEST = 4711

    private val pool = Executors.newSingleThreadExecutor()
    private var running = false

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** The UTC day number, so "once a day" means once per UTC calendar day. */
    private fun utcDay(ms: Long): Long = ms / 86_400_000L

    /** idle, downloading, ok, or "error: ..." (the news page reads this). */
    @Volatile var status = "idle"

    // Countries other than Bangladesh use public news headlines for that country.
    // name, language of the news, language code
    private val feeds: Map<String, Triple<String, String, String>> = linkedMapOf(
        "AU" to Triple("Australia", "en-AU", "en"),
        "CA" to Triple("Canada", "en-CA", "en"),
        "EG" to Triple("Egypt", "ar", "ar"),
        "FR" to Triple("France", "fr", "fr"),
        "DE" to Triple("Germany", "de", "de"),
        "IN" to Triple("India", "en-IN", "en"),
        "ID" to Triple("Indonesia", "id", "id"),
        "JP" to Triple("Japan", "ja", "ja"),
        "KE" to Triple("Kenya", "en-KE", "en"),
        "MY" to Triple("Malaysia", "en-MY", "en"),
        "NG" to Triple("Nigeria", "en-NG", "en"),
        "PK" to Triple("Pakistan", "en-PK", "en"),
        "PH" to Triple("Philippines", "en-PH", "en"),
        "SA" to Triple("Saudi Arabia", "ar", "ar"),
        "SG" to Triple("Singapore", "en-SG", "en"),
        "ZA" to Triple("South Africa", "en-ZA", "en"),
        "TR" to Triple("Turkey", "tr", "tr"),
        "GB" to Triple("United Kingdom", "en-GB", "en"),
        "US" to Triple("United States", "en-US", "en")
    )

    fun isCountry(cc: String): Boolean = cc == "BD" || feeds.containsKey(cc)

    /** [{"cc":"BD","name":"Bangladesh"}, ...] with Bangladesh first. */
    fun countriesJson(): String {
        val a = JSONArray()
        val bd = JSONObject()
        bd.put("cc", "BD")
        bd.put("name", "Bangladesh")
        a.put(bd)
        for ((cc, f) in feeds) {
            val o = JSONObject()
            o.put("cc", cc)
            o.put("name", f.first)
            a.put(o)
        }
        return a.toString()
    }

    /** Saves only if today's UTC copy of the chosen country is not on the phone yet. */
    fun saveIfDue(ctx: Context, done: (Boolean) -> Unit) {
        val f = file(ctx)
        val store = Store(ctx)
        val last = store.newsSavedAt
        val due = !f.exists() || f.length() < 500 ||
            utcDay(last) != utcDay(System.currentTimeMillis()) ||
            store.newsSavedCountry != store.newsCountry
        if (!due) {
            done(true)
            return
        }
        save(ctx, done)
    }

    /** Nirobo's own feed: full stories with pictures. */
    private fun fetchNirobo(): JSONArray {
        val c = URL(URL_NEWS).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "NiroboBrowser/1.0 (Android)")
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode != 200) throw Exception("Nirobo news answered " + c.responseCode)
        val buf = java.io.ByteArrayOutputStream()
        c.inputStream.use { ins ->
            val chunk = ByteArray(8192)
            while (buf.size() < 2_000_000) {
                val n = ins.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
            }
        }
        c.disconnect()
        val parsed = JSONObject(buf.toString("UTF-8"))
        val items = parsed.optJSONArray("items") ?: throw Exception("The news file is empty")
        val keep = JSONArray()
        val n = minOf(items.length(), KEEP)
        for (i in 0 until n) {
            val o = items.optJSONObject(i) ?: continue
            // only secure links are kept
            if (!o.optString("u").startsWith("https://")) continue
            // the longest text the feed gives us is the description
            val desc = listOf("c", "content", "d", "summary", "description")
                .map { k -> o.optString(k) }
                .maxByOrNull { it.length } ?: ""
            var img = ""
            for (k in listOf("i", "img", "image", "thumb")) {
                val v = o.optString(k)
                if (v.startsWith("https://")) {
                    img = v
                    break
                }
            }
            val slim = JSONObject()
            slim.put("t", o.optString("t"))
            slim.put("u", o.optString("u"))
            slim.put("d", desc.take(6000))
            slim.put("s", o.optString("s"))
            slim.put("i", img)
            keep.put(slim)
        }
        return keep
    }

    private fun plain(html: String): String {
        val t = if (Build.VERSION.SDK_INT >= 24) {
            android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            android.text.Html.fromHtml(html).toString()
        }
        return t.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    /** National headlines of one country (headlines and source names, no pictures). */
    private fun fetchRss(cc: String): JSONArray {
        val f = feeds[cc] ?: throw Exception("No news for this country yet")
        val feed = "https://news.google.com/rss?hl=" + f.second + "&gl=" + cc + "&ceid=" + cc + ":" + f.third
        val c = URL(feed).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "NiroboBrowser/1.0 (Android)")
        if (c.responseCode != 200) throw Exception("The news website answered " + c.responseCode)
        val keep = JSONArray()
        c.inputStream.use { ins ->
            val p = android.util.Xml.newPullParser()
            p.setInput(ins, "UTF-8")
            var ev = p.eventType
            var inItem = false
            var tag = ""
            var title = ""
            var link = ""
            var desc = ""
            var source = ""
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && keep.length() < KEEP) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    tag = p.name
                    if (tag == "item") {
                        inItem = true
                        title = ""
                        link = ""
                        desc = ""
                        source = ""
                    }
                } else if (ev == org.xmlpull.v1.XmlPullParser.TEXT) {
                    if (inItem) {
                        val tx = p.text ?: ""
                        when (tag) {
                            "title" -> title += tx
                            "link" -> link += tx
                            "description" -> desc += tx
                            "source" -> source += tx
                        }
                    }
                } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    if (p.name == "item" && inItem) {
                        inItem = false
                        val l = link.trim()
                        if (l.startsWith("https://") && title.isNotBlank()) {
                            val src = source.trim()
                            var t = title.trim()
                            if (src.isNotEmpty() && t.endsWith(" - $src")) t = t.removeSuffix(" - $src")
                            var d = plain(desc)
                            if (d.startsWith(title.trim())) d = d.removePrefix(title.trim()).trim()
                            if (src.isNotEmpty() && d.startsWith(src)) d = d.removePrefix(src).trim()
                            val o = JSONObject()
                            o.put("t", t)
                            o.put("u", l)
                            o.put("d", if (d.isEmpty()) "" else "Related: " + d.take(500))
                            o.put("s", src)
                            o.put("i", "")
                            keep.put(o)
                        }
                    }
                    tag = ""
                }
                ev = p.next()
            }
        }
        c.disconnect()
        return keep
    }

    /** Downloads and stores the news of the chosen country. Safe to call from any thread. */
    fun save(ctx: Context, done: (Boolean) -> Unit) {
        if (running) {
            done(false)
            return
        }
        running = true
        status = "downloading"
        val app = ctx.applicationContext
        val cc = Store(app).newsCountry
        pool.execute {
            var ok = false
            var err = ""
            var savedKeep: JSONArray? = null
            try {
                val keep = if (cc == "BD") fetchNirobo() else fetchRss(cc)
                if (keep.length() > 0) {
                    val out = JSONObject()
                    out.put("v", 2)
                    out.put("savedAt", System.currentTimeMillis())
                    out.put("country", cc)
                    out.put("items", keep)

                    // write to a temp file first, so a half-written file is never read
                    val tmp = File(app.filesDir, "$FILE.tmp")
                    tmp.writeText(out.toString())
                    val dest = file(app)
                    if (dest.exists()) dest.delete()
                    tmp.renameTo(dest)

                    val st = Store(app)
                    st.newsSavedAt = System.currentTimeMillis()
                    st.newsSavedCountry = cc
                    savedKeep = keep
                    ok = true
                } else {
                    err = "No stories came back"
                }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            running = false
            status = if (ok) "ok" else "error: $err"
            done(ok)
            // pictures come after the text, so the news never waits for them
            val k = savedKeep
            if (ok && k != null) fetchImages(app, k)
        }
    }

    // ---------- pictures kept for offline reading ----------

    private fun imgKey(url: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        return md.digest(url.toByteArray()).joinToString("") { b -> "%02x".format(b) }.substring(0, 20)
    }

    private fun imageMime(b: ByteArray): String? {
        if (b.size < 12) return null
        fun at(i: Int): Int = b[i].toInt() and 0xFF
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 -> "image/webp"
            else -> null
        }
    }

    /** Saves the pictures of the newest stories (small ones only) and forgets old ones. */
    private fun fetchImages(ctx: Context, items: JSONArray) {
        try {
            val dir = File(ctx.filesDir, "news_img")
            dir.mkdirs()
            val want = HashSet<String>()
            for (i in 0 until minOf(items.length(), 40)) {
                val u = items.optJSONObject(i)?.optString("i") ?: continue
                if (!u.startsWith("https://")) continue
                val key = imgKey(u)
                want.add(key)
                val f = File(dir, key)
                if (f.exists()) continue
                try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    if (c.responseCode == 200) {
                        val buf = java.io.ByteArrayOutputStream()
                        var tooBig = false
                        c.inputStream.use { ins ->
                            val chunk = ByteArray(8192)
                            while (true) {
                                val n = ins.read(chunk)
                                if (n < 0) break
                                buf.write(chunk, 0, n)
                                if (buf.size() > 160_000) {
                                    tooBig = true
                                    break
                                }
                            }
                        }
                        val bytes = buf.toByteArray()
                        if (!tooBig && imageMime(bytes) != null) f.writeBytes(bytes)
                    }
                    c.disconnect()
                } catch (e: Exception) {
                    // this picture is skipped
                }
            }
            dir.listFiles()?.forEach { f -> if (f.name !in want) f.delete() }
        } catch (e: Exception) {
            // pictures are a bonus
        }
    }

    /** A saved picture as a data address the news page can show, or "" when there is none. */
    fun imageDataUri(ctx: Context, url: String): String {
        if (!url.startsWith("https://")) return ""
        return try {
            val f = File(File(ctx.filesDir, "news_img"), imgKey(url))
            if (!f.exists()) return ""
            val bytes = f.readBytes()
            val mime = imageMime(bytes) ?: return ""
            "data:" + mime + ";base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    /** Sets the alarm for the next UTC midnight. */
    fun ensureScheduled(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(ctx)
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val at = cal.timeInMillis
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
        }
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, NewsAlarmReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(ctx, ALARM_REQUEST, i, flags)
    }
}

/** Fires at UTC midnight, saves the feed, then sets tomorrow's alarm. */
class NewsAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        NewsSaver.save(app) {
            NewsSaver.ensureScheduled(app)
        }
    }
}

```


## Small pieces still to write when merging
- `Store.kt`: `autoplay`(true) `muteVideo`(true) `darkPages`(false) `checkShort`(true) `newsCountry`("BD") `newsSavedCountry`(""); also `curMax`/`curMin` ints; `getString/putString` already exist.
- `Bridge.toggle` keys: `autoplay` (+`act.applyMedia()`), `mutevideo`, `darkpages` (+`act.applyDark()`), `checkshort`; add the four values to `Bridge.settings()` JSON.
- `about:protect`: new cards "Media and display" (autoplay, mute videos, dark pages) and "Links" (ask before opening short links + row to `about:check`).
- MainActivity helpers used by the snippets: `speak(text, flush)`, `stopSpeaking()`, `applyMedia()`, `applyDark()`, `savePageArchive(t)`, `askShortLink()`, constants `VIDEO_JS`, `MUTE_JS`.

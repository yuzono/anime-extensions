package keiyoushi.lib.cloudscraper

import app.cash.quickjs.QuickJs

/**
 * Injects a browser environment into a QuickJS engine so that Cloudflare
 * challenge scripts can execute.
 *
 * The shim is split into modular constant blocks, each evaluated in its own
 * [QuickJs.evaluate] call so a syntax error in one block surfaces with a clear
 * boundary rather than failing the entire installation. Fingerprint values are
 * derived from the supplied [userAgent] so the navigator, brands and
 * platform all agree — Cloudflare flags mismatches between reported UA and
 * `navigator.userAgentData.brands` / `navigator.platform`.
 */
object BrowserEnvironment {

    fun install(engine: QuickJs, userAgent: String, originUrl: String = "") {
        val fp = Fingerprint.fromUserAgent(userAgent)
        val ua = jsonString(userAgent)
        val url = jsonString(originUrl)

        engine.evaluate(CORE_SHIM) // call-depth guard, console, timers, event registry
        engine.evaluate(CODEC_SHIM) // atob/btoa, TextEncoder/TextDecoder, __cf_parseUrl
        engine.evaluate(WEB_CONSTRUCTORS_SHIM) // fetch, XHR, Event, observers, Blob/AbortController, etc.
        engine.evaluate(documentShim(url)) // __cf_el factory + __cf_docObj document shim
        engine.evaluate(navigatorShim(fp, ua)) // navigator + screen + performance + crypto
        engine.evaluate(windowShim(ua, url)) // win object and globalThis wiring
        engine.evaluate(POST_INSTALL_SHIM) // clientInformation alias, wffQc7, scripts shim, URL/URLSearchParams
    }

    // ── 1 Core: call-depth guard, console, timers, event registry ──────

    private const val CORE_SHIM = """
        var __cf_callDepth=0;
        var __cf_maxCallDepth=256;
        globalThis.console={log:function(){},warn:function(){},error:function(){},
        info:function(){},debug:function(){},trace:function(){},dir:function(){},
        table:function(){},time:function(){},timeEnd:function(){},
        group:function(){},groupEnd:function(){}};
        var __cf_tid=0;
        var __cf_t={};
        var __cf_iv={};
        var __cf_pt=[];
        var __cf_pi=[];
        var __cf_st_counter=0;
        function __cf_setTimeout(fn,ms){
            var id=++__cf_tid;
            __cf_t[id]=fn;
            __cf_pt.push({id:id,fn:fn,ms:ms||0});
            __cf_st_counter++;
            if(!globalThis.__cf_st_trace)globalThis.__cf_st_trace=[];
            globalThis.__cf_st_trace.push('st#'+__cf_st_counter+' ms='+ms);
            return id;
        }
        function __cf_clearTimeout(id){delete __cf_t[id];}
        function __cf_setInterval(fn,ms){
            var id=++__cf_tid;
            __cf_iv[id]=fn;
            __cf_pi.push({id:id,fn:fn,ms:ms||0});
            return id;
        }
        function __cf_clearInterval(id){delete __cf_iv[id];}
        function __cf_run_timers(){
            var i, t, rounds=0;
            while(__cf_pt.length>0&&rounds<10){
                var batch=__cf_pt.splice(0);
                for(i=0;i<batch.length;i++){
                    t=batch[i];
                    try{if(__cf_t[t.id]){__cf_callDepth=0;t.fn();}}catch(e){}
                    delete __cf_t[t.id];
                }
                rounds++;
            }
            for(i=0;i<__cf_pi.length;i++){
                var s=__cf_pi[i];
                try{if(__cf_iv[s.id]){__cf_callDepth=0;s.fn();}}catch(e){}
            }
            return __cf_pt.length+__cf_pi.length;
        }
        var __cf_evtReg={};
        function __cf_addEvt(el,type,fn,opts){
            var k=el.__cf_eid||(el.__cf_eid='e'+(__cf_tid++));
            if(!__cf_evtReg[k])__cf_evtReg[k]={};
            if(!__cf_evtReg[k][type])__cf_evtReg[k][type]=[];
            __cf_evtReg[k][type].push({fn:fn,capture:!!(opts&&(opts.capture||opts===true))});
        }
        function __cf_remEvt(el,type,fn,opts){
            var k=el.__cf_eid;if(!k||!__cf_evtReg[k]||!__cf_evtReg[k][type])return;
            var cap=!!(opts&&(opts.capture||opts===true));
            __cf_evtReg[k][type]=__cf_evtReg[k][type].filter(function(h){return!(h.fn===fn&&h.capture===cap);});
        }
        var __cf_fire_count=0;
        var __cf_fire_trace=[];
        function __cf_fireEvt(el,type,evt){
            var k=el.__cf_eid;
            __cf_fire_trace.push('fire:'+type+' eid='+k+' hasReg='+!!__cf_evtReg[k]+' fnCount='+(__cf_evtReg[k]&&__cf_evtReg[k][type]?__cf_evtReg[k][type].length:0));
            if(!k||!__cf_evtReg[k]||!__cf_evtReg[k][type])return;
            var arr=__cf_evtReg[k][type].slice();
            for(var i=0;i<arr.length;i++){try{__cf_fire_count++;arr[i].fn(evt);}catch(e){__cf_fire_trace.push('fire:ERR:'+e.message);}}
        }
        function __cf_dispatchEvt(el,evt){
            var type=evt.type||'';
            __cf_fireEvt(el,type,evt);
            if(evt.bubbles!==false){__cf_fireEvt(window,type,evt);}
            return true;
        }
    """

    // ── 2 Codec + URLs: atob/btoa, TextEncoder/TextDecoder, __cf_parseUrl

    private const val CODEC_SHIM = """
        function __cf_atob(s){
            if(typeof s!=='string')return'';
            var c='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
            var str=String(s).replace(/[^A-Za-z0-9\+\/\=]/g,'');
            var o='';var i=0;
            while(i<str.length){
                var a=c.indexOf(str.charAt(i++));
                var b=c.indexOf(str.charAt(i++));
                var cc=c.indexOf(str.charAt(i++));
                var d=c.indexOf(str.charAt(i++));
                var e=(a<<18)|(b<<12)|(cc<<6)|d;
                o+=String.fromCharCode((e>>16)&0xFF);
                if(cc!==64)o+=String.fromCharCode((e>>8)&0xFF);
                if(d!==64)o+=String.fromCharCode(e&0xFF);
            }
            return o;
        }
        function __cf_btoa(s){
            var c='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
            var str=String(s);
            var o='';var i=0;
            while(i<str.length){
                var a=str.charCodeAt(i++);
                var b=str.charCodeAt(i++);
                var cc=str.charCodeAt(i++);
                var bits=(a<<16)|((b<<8)|cc);
                o+=c[(bits>>18)&0x3F];
                o+=c[(bits>>12)&0x3F];
                o+=isNaN(b)?'=':c[(bits>>6)&0x3F];
                o+=isNaN(cc)?'=':c[bits&0x3F];
            }
            return o;
        }
        function __cf_TE(e){this.encoding=e||'utf-8';}
        __cf_TE.prototype.encode=function(s){
            var str=String(s);var b=[];var i=0;
            while(i<str.length){
                var c=str.charCodeAt(i++);
                if(c<0x80){b.push(c);}
                else if(c<0x800){b.push(0xC0|(c>>6));b.push(0x80|(c&0x3F));}
                else if(c>=0xD800&&c<=0xDBFF){
                    var hi=c;var lo=str.charCodeAt(i++);
                    if(lo>=0xDC00&&lo<=0xDFFF){
                        var cp=((hi-0xD800)<<10)+(lo-0xDC00)+0x10000;
                        b.push(0xF0|(cp>>18));b.push(0x80|((cp>>12)&0x3F));
                        b.push(0x80|((cp>>6)&0x3F));b.push(0x80|(cp&0x3F));
                    }
                }else{b.push(0xE0|(c>>12));b.push(0x80|((c>>6)&0x3F));b.push(0x80|(c&0x3F));}
            }
            return new Uint8Array(b);
        };
        function __cf_TD(e,o){
            this.encoding=(e||'utf-8').toLowerCase();
            this.fatal=o&&o.fatal||false;
            this.ignoreBOM=o&&o.ignoreBOM||false;
        }
        __cf_TD.prototype.decode=function(buf){
            if(!buf)return'';
            var bytes;
            if(buf instanceof ArrayBuffer){bytes=new Uint8Array(buf);}
            else if(buf&&buf.buffer instanceof ArrayBuffer){
                bytes=new Uint8Array(buf.buffer,buf.byteOffset,buf.byteLength);
            }else{return String(buf);}
            var str='';var i=0;
            while(i<bytes.length){
                var b=bytes[i++];
                if(b<0x80){str+=String.fromCharCode(b);}
                else if(b<0xE0){str+=String.fromCharCode(((b&0x1F)<<6)|(bytes[i++]&0x3F));}
                else if(b<0xF0){
                    str+=String.fromCharCode(((b&0x0F)<<12)|((bytes[i++]&0x3F)<<6)|(bytes[i++]&0x3F));
                }else{
                    var cp=((b&0x07)<<18)|((bytes[i++]&0x3F)<<12)|((bytes[i++]&0x3F)<<6)|(bytes[i++]&0x3F);
                    cp-=0x10000;
                    str+=String.fromCharCode(0xD800+(cp>>10),0xDC00+(cp&0x3FF));
                }
            }
            return str;
        };
        function __cf_parseUrl(url){
            var loc={};
            loc.href=url||'';
            loc.origin='';
            loc.protocol='https:';
            loc.host='';
            loc.hostname='';
            loc.port='';
            loc.pathname='/';
            loc.search='';
            loc.hash='';
            loc.assign=function(){};
            loc.replace=function(){};
            loc.reload=function(){};
            if(!url)return loc;
            var m=url.match(/^((https?:)\/\/([^\/\?#]+))([^\?#]*)?(\?[^#]*)?(#.*)?$/);
            if(m){
                loc.origin=m[1];
                loc.protocol=m[2];
                loc.host=m[3];
                loc.hostname=m[3].split(':')[0];
                loc.port=m[3].indexOf(':')>=0?m[3].split(':')[1]:'';
                loc.pathname=m[4]||'/';
                loc.search=m[5]||'';
                loc.hash=m[6]||'';
            }
            return loc;
        }
    """

    // ── 3 Web constructors: fetch, XHR, Event, observers, blobs, etc.

    private const val WEB_CONSTRUCTORS_SHIM = """
        globalThis.fetch=function(url,opts){
            if(opts&&opts.method==='POST'&&opts.body){
                globalThis.__cf_sensor_payload=typeof opts.body==='string'?opts.body:String(opts.body);
                globalThis.__cf_sensor_url=typeof url==='string'?url:String(url);
            }
            var resp={};
            resp.ok=true;resp.status=200;resp.headers=new Map();
            resp.json=function(){return Promise.resolve({});};
            resp.text=function(){return Promise.resolve('');};
            resp.clone=function(){return this;};
            return Promise.resolve(resp);
        };
        globalThis.XMLHttpRequest=function(){
            this.readyState=0;this.status=0;this.responseText='';
            this.response='';this.responseType='';this.withCredentials=false;
            this._m='';this._u='';this._h={};
            this.onreadystatechange=null;this.onload=null;this.onerror=null;
            this.onabort=null;this.ontimeout=null;this.onprogress=null;
            this.upload={onload:null,onerror:null,onprogress:null};
            this.timeout=0;this.responseURL='';
            this.getAllResponseHeaders=function(){return'';};
            this.getResponseHeader=function(){return null;};
        };
        globalThis.XMLHttpRequest.prototype.open=function(m,u,a){
            this._m=m;this._u=u;this.readyState=1;
        };
        globalThis.XMLHttpRequest.prototype.setRequestHeader=function(k,v){
            this._h[k]=v;
        };
        globalThis.XMLHttpRequest.prototype.send=function(body){
            if(this._m==='POST'&&body){
                globalThis.__cf_sensor_payload=typeof body==='string'?body:String(body);
                globalThis.__cf_sensor_url=this._u;
            }
            this.readyState=4;this.status=200;this.responseURL=this._u;
            var self=this;
            function fire(){if(self.onreadystatechange)self.onreadystatechange();
                if(self.onload)self.onload();}
            setTimeout(fire,0);
        };
        globalThis.XMLHttpRequest.prototype.abort=function(){this.readyState=0;this.status=0;};
        globalThis.Event=function(type,opts){
            this.type=type||'';this.bubbles=!!(opts&&opts.bubbles);
            this.cancelable=!!(opts&&opts.cancelable);this.composed=!!(opts&&opts.composed);
            this.target=null;this.currentTarget=null;this.timeStamp=Date.now();
            this.defaultPrevented=false;this.eventPhase=0;this.isTrusted=true;
            this.preventDefault=function(){this.defaultPrevented=true;};
            this.stopPropagation=function(){};this.stopImmediatePropagation=function(){};
            this.initEvent=function(t,b,c){this.type=t;this.bubbles=!!b;this.cancelable=!!c;};
        };
        globalThis.CustomEvent=function(type,opts){
            Event.call(this,type,opts);this.detail=opts&&opts.detail;
        };
        globalThis.CustomEvent.prototype=Object.create(Event.prototype);
        globalThis.MessageEvent=function(type,opts){
            Event.call(this,type,opts);this.data=opts&&opts.data;
            this.origin=opts&&opts.origin||'';this.source=opts&&opts.source;
        };
        globalThis.MessageEvent.prototype=Object.create(Event.prototype);
        globalThis.MutationObserver=function(callback){
            this._cb=callback;this._records=[];
            this.observe=function(){};
            this.disconnect=function(){};
            this.takeRecords=function(){var r=this._records;this._records=[];return r;};
        };
        globalThis.IntersectionObserver=function(cb){
            this._cb=cb;
            this.observe=function(){};this.disconnect=function(){};
            this.unobserve=function(){};this.takeRecords=function(){return[];};
        };
        globalThis.ResizeObserver=function(cb){
            this._cb=cb;
            this.observe=function(){};this.disconnect=function(){};
            this.unobserve=function(){};
        };
        globalThis.PerformanceObserver=function(cb){
            this._cb=cb;
            this.observe=function(){};this.disconnect=function(){};
        };
        globalThis.Blob=function(p,o){this.size=0;this.type=o&&o.type||'';};
        globalThis.File=function(p,n,o){this.name=n;this.size=0;this.type=o&&o.type||'';};
        globalThis.AbortController=function(){
            this.signal={aborted:false,reason:undefined,onabort:null};
            this.abort=function(){this.signal.aborted=true;};
        };
        globalThis.URL={};
        globalThis.URL.createObjectURL=function(){return'blob:null';};
        globalThis.URL.revokeObjectURL=function(){};
        globalThis.FormData=function(){
            this._d={};
            this.append=function(k,v){this._d[k]=v;};
            this.get=function(k){return this._d[k];};
            this.has=function(k){return k in this._d;};
            this.toString=function(){return'';};
        };
        globalThis.DOMParser=function(){
            this.parseFromString=function(){return globalThis.document||{};};
        };
        globalThis.Image=function(){return{src:'',onload:null,onerror:null,complete:true,width:0,height:0};};
        globalThis.HTMLElement=function(){};
        globalThis.HTMLFormElement=function(){};
        globalThis.HTMLInputElement=function(){};
        globalThis.HTMLCanvasElement=function(w,h){
            this.width=w||300;this.height=h||150;
            this.style={};this.id='';this.className='';
            this.addEventListener=function(t,fn,o){__cf_addEvt(this,t,fn,o);};
            this.removeEventListener=function(t,fn,o){__cf_remEvt(this,t,fn,o);};
            this.dispatchEvent=function(e){return __cf_dispatchEvt(this,e);};
            var _ops=[];var _w=this.width;var _h=this.height;
            this.getContext=function(type){
                if(type==='webgl'||type==='webgl2'){
                    var gl={};
                    gl.drawingBufferWidth=_w;gl.drawingBufferHeight=_h;
                    gl.canvas=this;
                    gl.getParameter=function(p){
                        if(p===7937)return'WebKit WebGL';
                        if(p===7936)return'WebKit';
                        if(p===7938)return'WebGL 1.0 (OpenGL ES 2.0 Chromium)';
                        if(p===35724)return'WebGL GLSL ES 1.0 (OpenGL ES GLSL ES 1.0 Chromium)';
                        if(p===36347)return1024;
                        if(p===36349)return16384;
                        if(p===3386)return new Float32Array([1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]);
                        if(p===36345)return'Intel Inc.';
                        if(p===36346)return'Intel Iris OpenGL Engine';
                        return null;};
                    gl.getExtension=function(){return null;};
                    gl.getSupportedExtensions=function(){return['OES_standard_derivatives','OES_element_index_uint','WEBGL_lose_context'];};
                    gl.createBuffer=function(){return{};};gl.bindBuffer=function(){};
                    gl.bufferData=function(){};gl.createShader=function(){return{};};
                    gl.shaderSource=function(){};gl.compileShader=function(){};
                    gl.getShaderParameter=function(){return true;};
                    gl.createProgram=function(){return{};};gl.attachShader=function(){};
                    gl.linkProgram=function(){};gl.getProgramParameter=function(){return true;};
                    gl.useProgram=function(){};gl.getAttribLocation=function(){return 0;};
                    gl.enableVertexAttribArray=function(){};gl.vertexAttribPointer=function(){};
                    gl.getUniformLocation=function(){return{};};gl.uniform1i=function(){};
                    gl.uniform1f=function(){};gl.uniform2f=function(){};
                    gl.uniform4f=function(){};gl.uniformMatrix4fv=function(){};
                    gl.clearColor=function(){};gl.clear=function(){};
                    gl.viewport=function(){};gl.drawArrays=function(){};
                    gl.drawElements=function(){};gl.enable=function(){};
                    gl.disable=function(){};gl.blendFunc=function(){};
                    gl.getError=function(){return 0;};
                    gl.createTexture=function(){return{};};gl.bindTexture=function(){};
                    gl.texParameteri=function(){};gl.texImage2D=function(){};
                    gl.pixelStorei=function(){};gl.activeTexture=function(){};
                    gl.readPixels=function(x,y,w,h,fmt,type,pixels){
                        for(var i=0;i<pixels.length;i+=4){
                            pixels[i]=i%256;pixels[i+1]=(i*7)%256;pixels[i+2]=(i*13)%256;pixels[i+3]=255;}};
                    gl.getShaderPrecisionFormat=function(){return{rangeMin:127,rangeMax:127,precision:23};};
                    gl.isContextLost=function(){return false;};
                    return gl;}
                if(type!=='2d')return null;
                var ctx={};
                ctx.canvas=this;ctx.textBaseline='top';ctx.textAlign='left';
                ctx.font='14px Arial';ctx.fillStyle='#000000';ctx.strokeStyle='#000000';
                ctx.lineWidth=1;ctx.lineCap='butt';ctx.lineJoin='miter';ctx.miterLimit=10;
                ctx.globalAlpha=1;ctx.globalCompositeOperation='source-over';
                ctx.shadowBlur=0;ctx.shadowColor='rgba(0,0,0,0)';ctx.shadowOffsetX=0;ctx.shadowOffsetY=0;
                ctx.fillStyle='#f60';
                ctx.fillRect=function(x,y,w,h){_ops.push('FR:'+x+','+y+','+w+','+h);};
                ctx.fillStyle='#069';
                ctx.fillText=function(t,x,y){_ops.push('FT:'+t+':'+x+','+y);};
                ctx.strokeText=function(t,x,y){_ops.push('ST:'+t+':'+x+','+y);};
                ctx.measureText=function(t){
                    var w=0;for(var i=0;i<t.length;i++){var c=t.charCodeAt(i);w+=(c>=33&&c<127)?9.6:7.2;}
                    return{width:w||7.2,actualBoundingBoxAscent:11,actualBoundingBoxDescent:3,
                        actualBoundingBoxLeft:0,actualBoundingBoxRight:w,fontBoundingBoxAscent:12,fontBoundingBoxDescent:4};};
                ctx.getImageData=function(x,y,w,h){
                    var seed=0;for(var i=0;i<_ops.length;i++){seed=((seed<<5)-seed)+_ops[i].charCodeAt(0);seed|=0;}
                    seed=seed*16807%2147483647;var d=new Uint8Array(w*h*4);
                    for(var i=0;i<d.length;i+=4){seed=seed*16807%2147483647;
                        d[i]=(seed&0xff);d[i+1]=((seed>>8)&0xff);d[i+2]=((seed>>16)&0xff);d[i+3]=255;}
                    return{data:d,width:w,height:h};};
                ctx.putImageData=function(){};
                ctx.createImageData=function(w,h){return ctx.getImageData(0,0,w,h);};
                ctx.arc=function(){};ctx.arcTo=function(){};ctx.beginPath=function(){};
                ctx.closePath=function(){};ctx.fill=function(){};ctx.stroke=function(){};ctx.clip=function(){};
                ctx.lineTo=function(){};ctx.moveTo=function(){};ctx.quadraticCurveTo=function(){};
                ctx.bezierCurveTo=function(){};ctx.rect=function(){};
                ctx.scale=function(){};ctx.rotate=function(){};ctx.translate=function(){};
                ctx.transform=function(){};ctx.setTransform=function(){};ctx.resetTransform=function(){};
                ctx.save=function(){};ctx.restore=function(){};ctx.clearRect=function(){};
                ctx.createLinearGradient=function(){return{addColorStop:function(){}};};
                ctx.createRadialGradient=function(){return{addColorStop:function(){}};};
                ctx.createPattern=function(){return{};};
                ctx.drawImage=function(){};
                ctx.isPointInPath=function(){return false;};ctx.isPointInStroke=function(){return false;};
                ctx.measureText=function(t){var w=t.length*7.5;return{width:w};};
                return ctx;};
            this.toDataURL=function(type){
                var seed=0;for(var i=0;i<_ops.length;i++){seed=((seed<<5)-seed)+_ops[i].charCodeAt(0);seed|=0;}
                seed=(seed>>>0)%2147483647;if(seed<0)seed+=2147483647;
                var r=(seed&0xff),g=((seed>>8)&0xff),b=((seed>>16)&0xff);
                var px='\\x'+('0'+r.toString(16)).slice(-2)+'\\x'+('0'+g.toString(16)).slice(-2)+'\\x'+('0'+b.toString(16)).slice(-2);
                var raw='\\x89PNG\\r\\n\\x1a\\n';
                raw+='\\x00\\x00\\x00\\rIHDR\\x00\\x00\\x00\\x01\\x00\\x00\\x00\\x01\\x08\\x02\\x00\\x00\\x00\\x90wS\\xde';
                raw+='\\x00\\x00\\x00\\x0cIDATx\\x9cc'+px+'\\x01\\x01\\x00\\x05\\x00\\x01\\r\\n\\xb4\\x00\\x00\\x00\\x00IEND\\xaeB\\x60\\x82';
                return'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVQI12NgAAIABQABNjN9GQAAAABJRU5ErkJggg==';};
            this.toBlob=function(cb,type,quality){
                var b64=this.toDataURL(type).split(',')[1];
                var bin=atob(b64);var arr=new Uint8Array(bin.length);
                for(var i=0;i<bin.length;i++)arr[i]=bin.charCodeAt(i);
                cb(new Blob([arr],{type:type||'image/png'}));};
            this.addEventListener=function(t,fn,o){__cf_addEvt(this,t,fn,o);};
            this.removeEventListener=function(t,fn,o){__cf_remEvt(this,t,fn,o);};
            this.dispatchEvent=function(e){return __cf_dispatchEvt(this,e);};
            this.cloneNode=function(){return new HTMLCanvasElement(_w,_h);};
            this.getBoundingClientRect=function(){return{top:0,left:0,right:_w,bottom:_h,width:_w,height:_h,x:0,y:0,toJSON:function(){return this;}};};
        };
        globalThis.HTMLIFrameElement=function(){
            this.contentWindow=win;
            this.contentDocument=__cf_docObj;
        };
        globalThis.WebGLRenderingContext=function(){};
        globalThis.WebGL2RenderingContext=function(){};
        globalThis.AudioContext=function(){
            this.state='running';this.sampleRate=48000;this.currentTime=0;
            this.destination={numberOfInputs:0,numberOfOutputs:0};
            this.close=function(){this.state='closed';};
        };
        globalThis.webkitAudioContext=globalThis.AudioContext;
        globalThis.OfflineAudioContext=function(c,l,r){
            this.length=l;this.sampleRate=r;this.state='suspended';
            this.startRendering=function(){
                var buf={};
                buf.getChannelData=function(){return new Float32Array(l);};
                buf.numberOfChannels=c;buf.length=l;buf.sampleRate=r;
                return Promise.resolve(buf);
            };
        };
        globalThis.webkitRTCPeerConnection=function(){};
        globalThis.RTCPeerConnection=function(){};
        globalThis.RTCSessionDescription=function(){};
        globalThis.RTCIceCandidate=function(){};
        globalThis.MediaStream=function(){};
        globalThis.MediaRecorder=function(){};
        globalThis.Worker=function(){};
        globalThis.SharedWorker=function(){};
        globalThis.ServiceWorker=function(){};
        globalThis.FileReader=function(){
            this.result=null;
            this.readAsDataURL=function(){};
            this.readAsText=function(){};
            this.readAsArrayBuffer=function(){};
        };
        globalThis.Notification=function(){};
        globalThis.Notification.permission='default';
        globalThis.Notification.requestPermission=function(){
            return Promise.resolve('denied');
        };
        globalThis.Intl={};
        globalThis.Intl.DateTimeFormat=function(l,o){
            this.resolvedOptions=function(){
                return{locale:l&&l[0]||'en-US',timeZone:'America/New_York',
                    year:'numeric',month:'2-digit',day:'2-digit',
                    hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false};
            };
            this.format=function(d){
                var dt=d||new Date();
                var p=function(n){return n<10?'0'+n:''+n;};
                return p(dt.getMonth()+1)+'/'+p(dt.getDate())+'/'+dt.getFullYear()
                    +', '+p(dt.getHours())+':'+p(dt.getMinutes())+':'+p(dt.getSeconds());
            };
        };
        globalThis.Intl.NumberFormat=function(){
            this.format=function(n){return''+n;};
        };
        globalThis.Intl.Collator=function(){
            this.compare=function(a,b){return a<b?-1:a>b?1:0;};
        };
        globalThis.MessageChannel=function(){
            this.port1={postMessage:function(){},onmessage:null};
            this.port2={postMessage:function(){},onmessage:null};
        };
    """

    // ── 4 Document factory + __cf_docObj

    private fun documentShim(url: String): String = """
        function __cf_el(tag){
            var e={};
            e.tagName=tag.toUpperCase();
            e.nodeName=tag.toUpperCase();
            e.children=[];
            e.childNodes=[];
            e.style={};
            e.attributes=[];
            e.classList={add:function(){},remove:function(){},
                contains:function(){return false;},toggle:function(){return false;}};
            e.querySelector=function(){return null;};
            e.querySelectorAll=function(){return[];};
            e.getElementsByTagName=function(){return[];};
            e.getElementsByClassName=function(){return[];};
            e.getElementById=function(){return null;};
            e.addEventListener=function(type,fn,opts){__cf_addEvt(this,type,fn,opts);};
            e.removeEventListener=function(type,fn,opts){__cf_remEvt(this,type,fn,opts);};
            e.dispatchEvent=function(e){return __cf_dispatchEvt(this,e);};
            e.setAttribute=function(){};
            e.getAttribute=function(){return null;};
            e.hasAttribute=function(){return false;};
            e.removeAttribute=function(){};
            e.appendChild=function(c){
                this.children.push(c);this.childNodes.push(c);
                c.parentNode=this;c.parentElement=this;return c;
            };
            e.removeChild=function(c){return c;};
            e.insertBefore=function(n){
                this.children.unshift(n);this.childNodes.unshift(n);return n;
            };
            e.replaceChild=function(n,o){return o;};
            e.cloneNode=function(){return this;};
            e.contains=function(){return false;};
            e.parentNode=null;e.parentElement=null;e.firstChild=null;
            e.lastChild=null;e.nextSibling=null;e.previousSibling=null;
            e.ownerDocument=null;e.textContent='';e.innerHTML='';
            e.innerText='';e.outerHTML='';e.nodeType=1;e.nodeValue=null;
            e.offsetWidth=0;e.offsetHeight=0;e.offsetTop=0;e.offsetLeft=0;
            e.clientWidth=0;e.clientHeight=0;e.scrollWidth=0;e.scrollHeight=0;
            e.scrollTop=0;e.scrollLeft=0;e.dataset={};
            e.getBoundingClientRect=function(){
                return{top:0,left:0,right:0,bottom:0,width:0,height:0,
                    x:0,y:0,toJSON:function(){return this;}};
            };
            e.getClientRects=function(){return{length:0};};
            e.focus=function(){};e.blur=function(){};e.click=function(){};
            e.dispatchEvent=function(){return true;};
            return e;
        }
        var __cf_elements={};
        var __cf_docUrl=$url;
        var __cf_docParsed=__cf_parseUrl(__cf_docUrl);
        var __cf_docDomain=__cf_docParsed.hostname;
        var __cf_head=__cf_el('head');
        var __cf_body=__cf_el('body');
        var __cf_htmlEl=__cf_el('html');
        __cf_htmlEl.appendChild(__cf_head);
        __cf_htmlEl.appendChild(__cf_body);
        var __cf_docObj=__cf_el('#document');
        __cf_docObj.nodeType=9;
        __cf_docObj.head=__cf_head;
        __cf_docObj.body=__cf_body;
        __cf_docObj.documentElement=__cf_htmlEl;
        __cf_docObj._cookie='';
        __cf_docObj.cookie='';
        __cf_docObj.title='';
        __cf_docObj.domain=__cf_docDomain;
        __cf_docObj.URL=__cf_docUrl;
        __cf_docObj.documentURI=__cf_docUrl;
        __cf_docObj.referrer='';
        __cf_docObj.readyState='loading';
        __cf_docObj.visibilityState='visible';
        __cf_docObj.hidden=false;
        __cf_docObj.characterSet='UTF-8';
        __cf_docObj.charset='UTF-8';
        __cf_docObj.inputEncoding='UTF-8';
        __cf_docObj.contentType='text/html';
        __cf_docObj.compatMode='CSS1Compat';
        __cf_docObj.designMode='off';
        __cf_docObj.activeElement=__cf_body;
        __cf_docObj.currentScript=null;
        __cf_docObj.defaultView=null;
        __cf_docObj.createElement=function(tag){
            var e=__cf_el(tag);
            e.ownerDocument=__cf_docObj;
            if(tag.toLowerCase()==='iframe'){
                e.contentWindow=win;
                e.contentDocument=__cf_docObj;
            }
            return e;
        };
        __cf_docObj.createElementNS=function(ns,tag){
            return __cf_docObj.createElement(tag);
        };
        __cf_docObj.createDocumentFragment=function(){
            var f=__cf_el('#fragment');f.nodeType=11;return f;
        };
        __cf_docObj.createTextNode=function(t){
            return{nodeType:3,textContent:t,data:t,nodeName:'#text'};
        };
        __cf_docObj.createComment=function(t){
            return{nodeType:8,data:t,nodeName:'#comment'};
        };
        __cf_docObj.createEvent=function(type){
            return{initEvent:function(){},preventDefault:function(){},stopPropagation:function(){}};
        };
        __cf_docObj.createTreeWalker=function(){
            return{nextNode:function(){return null;}};
        };
        __cf_docObj.elementFromPoint=function(){return __cf_body;};
        __cf_docObj.elementsFromPoint=function(){return [__cf_body];};
        __cf_docObj.elementExists=function(){return false;};
        __cf_docObj.createRange=function(){
            return{setStart:function(){},setEnd:function(){},
                createContextualFragment:function(h){
                    var d=__cf_el('div');d.innerHTML=h;return d;
                }};
        };
        __cf_docObj.getElementById=function(id){
            if(__cf_elements[id]!==undefined)return __cf_elements[id];
            if(id==='challenge-form'||id==='challenge-platform'){
                var f=__cf_el('form');
                f.id=id;f.action='';f.method='POST';
                f.submit=function(){};
                f.querySelector=function(sel){
                    if(sel==='input[name="r"]')return{value:'',name:'r'};return null;
                };
                f.querySelectorAll=function(){return[];};
                __cf_elements[id]=f;return f;
            }
            if(id==='jschl-answer'||id==='jschl_answer'){
                var inp=__cf_el('input');
                inp.id=id;inp.name='jschl_answer';inp.value='';inp.type='hidden';
                __cf_elements[id]=inp;return inp;
            }
            return null;
        };
        __cf_docObj.getElementsByTagName=function(tag){
            tag=tag.toLowerCase();
            if(tag==='head')return[__cf_head];
            if(tag==='body')return[__cf_body];
            if(tag==='html')return[__cf_htmlEl];
            return[];
        };
        __cf_docObj.getElementsByClassName=function(){return[];};
        __cf_docObj.querySelectorAll=function(sel){
            var res=[];
            if(!sel)return res;
            if(sel.charAt(0)==='#'){
                var el=__cf_docObj.getElementById(sel.substring(1));
                if(el)res.push(el);
                return res;
            }
            var tagMatch=sel.match(/^([a-zA-Z]+)$/);
            if(tagMatch){
                var tag=tagMatch[1].toLowerCase();
                if(tag==='head')res.push(__cf_head);
                else if(tag==='body')res.push(__cf_body);
                return res;
            }
            return res;
        };
        __cf_docObj.querySelector=function(sel){
            if(!sel)return null;
            if(sel==='head')return __cf_head;
            if(sel==='body')return __cf_body;
            if(sel.charAt(0)==='#')return __cf_docObj.getElementById(sel.substring(1));
            var tagMatch=sel.match(/^([a-zA-Z]+)$/);
            if(tagMatch){
                var tag=tagMatch[1].toLowerCase();
                if(tag==='head')return __cf_head;
                if(tag==='body')return __cf_body;
            }
            return null;
        };
        __cf_docObj.addEventListener=function(type,fn,opts){__cf_addEvt(this,type,fn,opts);};
        __cf_docObj.removeEventListener=function(type,fn,opts){__cf_remEvt(this,type,fn,opts);};
        __cf_docObj.dispatchEvent=function(e){return __cf_dispatchEvt(this,e);};
        __cf_docObj.hasFocus=function(){return true;};
        __cf_docObj.__cf_elements=__cf_elements;
    """.trimIndent()

    // ── 5 Navigator / Screen / Performance / Crypto — fingerprint values from UA

    private fun navigatorShim(fp: Fingerprint, ua: String): String = """
        var _loc=__cf_parseUrl($ua);  // unused placeholder; location set in windowShim
        var _nav={};
        _nav.userAgent=${jsonString(fp.userAgent)};
        _nav.appVersion=_nav.userAgent.substring(_nav.userAgent.indexOf('/')+1);
        _nav.platform=${jsonString(fp.platform)};
        _nav.vendor='Google Inc.';
        _nav.language='en-US';
        _nav.languages=['en-US','en'];
        _nav.onLine=true;
        _nav.cookieEnabled=true;
        _nav.doNotTrack=null;
        _nav.maxTouchPoints=0;
        _nav.hardwareConcurrency=8;
        _nav.deviceMemory=8;
        _nav.webdriver=false;
        _nav.t=__cf_btoa(String(Date.now()));
        _nav.r=__cf_btoa(String(Date.now()-50));
        _nav.getGamepads=function(){return[];};
        _nav.sendBeacon=function(url,data){
            if(data){
                globalThis.__cf_sensor_payload=typeof data==='string'?data:String(data);
                globalThis.__cf_sensor_url=typeof url==='string'?url:String(url);
            }
            return true;
        };
        _nav.javaEnabled=function(){return false;};
        _nav.globalPrivacyControl=false;
        _nav.connection={effectiveType:'4g',rtt:100,downlink:10,saveData:false};
        _nav.plugins={length:5,item:function(){return null;},
            namedItem:function(){return null;},refresh:function(){}};
        _nav.mimeTypes={length:2,item:function(){return null;},
            namedItem:function(){return null;}};
        _nav.getBattery=function(){
            return Promise.resolve({charging:true,chargingTime:0,
                dischargingTime:Infinity,level:1});
        };
        _nav.mediaDevices={enumerateDevices:function(){return Promise.resolve([]);}};
        _nav.permissions={query:function(){return Promise.resolve({state:'prompt'});}};
        _nav.serviceWorker={ready:Promise.resolve({}),register:function(){return Promise.resolve({});}};
        _nav.clipboard={readText:function(){return Promise.resolve('');},
            writeText:function(){return Promise.resolve();}};
        _nav.locks={request:function(){return Promise.resolve();},
            query:function(){return Promise.resolve({held:[],pending:[]});}};
        _nav.storage={estimate:function(){return Promise.resolve({quota:1073741824,usage:0});}};
        _nav.userAgentData={
            brands:[
                {brand:'Chromium',version:${jsonString(fp.chromeMajor)}},
                {brand:'Google Chrome',version:${jsonString(fp.chromeMajor)}},
                {brand:'Not_A Brand',version:${jsonString(fp.notABrandVersion)}}
            ],
            mobile:false,platform:${jsonString(fp.userAgentDataPlatform)},
            getHighEntropyValues:function(){return Promise.resolve({
                architecture:'x86',model:'',platform:${jsonString(fp.userAgentDataPlatform)},
                platformVersion:${jsonString(fp.platformVersion)},
                uaFullVersion:${jsonString(fp.userAgentFullVersion)},
                bitness:'64'
            });}
        };
        var _scr={width:1920,height:1080,availWidth:1920,availHeight:1040,
            colorDepth:24,pixelDepth:24,
            orientation:{angle:0,type:'landscape-primary',onchange:null},
            isExtended:false};
        var ts=Date.now();
        var _perf={};
        _perf.now=function(){return Date.now()-ts;};
        _perf.timing={
            navigationStart:ts-500,fetchStart:ts-400,
            domainLookupStart:ts-380,domainLookupEnd:ts-370,
            connectStart:ts-360,connectEnd:ts-300,
            secureConnectionStart:ts-340,requestStart:ts-280,
            responseStart:ts-200,responseEnd:ts-100,
            domLoading:ts-90,domInteractive:ts-50,
            domContentLoadedEventStart:ts-40,domContentLoadedEventEnd:ts-39,
            domComplete:ts-10,loadEventStart:ts-5,loadEventEnd:ts
        };
        _perf.navigation={type:0,redirectCount:0};
        _perf.timeOrigin=ts-500;
        _perf.getEntries=function(){return[];};
        _perf.getEntriesByType=function(){return[];};
        _perf.getEntriesByName=function(){return[];};
        _perf.mark=function(){};
        _perf.measure=function(){};
        _perf.clearMarks=function(){};
        _perf.clearMeasures=function(){};
        _perf.setResourceTimingBufferSize=function(){};
        var _sub={};
        _sub.digest=function(){return Promise.resolve(new ArrayBuffer(32));};
        _sub.importKey=function(){return Promise.resolve({});};
        _sub.exportKey=function(){return Promise.resolve({});};
        _sub.sign=function(){return Promise.resolve(new ArrayBuffer(64));};
        _sub.verify=function(){return Promise.resolve(true);};
        _sub.encrypt=function(){return Promise.resolve(new ArrayBuffer(0));};
        _sub.decrypt=function(){return Promise.resolve(new ArrayBuffer(0));};
        _sub.generateKey=function(){return Promise.resolve({});};
        _sub.deriveBits=function(){return Promise.resolve(new ArrayBuffer(32));};
        _sub.deriveKey=function(){return Promise.resolve({});};
        var _crypto={subtle:_sub};
        _crypto.getRandomValues=function(a){
            for(var i=0;i<a.length;i++){a[i]=Math.floor(Math.random()*256);}return a;
        };
        _crypto.randomUUID=function(){
            return'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,function(c){
                var r=Math.random()*16|0;return(c==='x'?r:(r&0x3|0x8)).toString(16);
            });
        };
    """.trimIndent()

    // ── 6 Window assembly + globalThis wiring

    private fun windowShim(ua: String, url: String): String = """
        var _loc=__cf_parseUrl($url);
        var win={};
        win.navigator=_nav;win.document=__cf_docObj;win.screen=_scr;
        win.performance=_perf;win.crypto=_crypto;win.location=_loc;
        win.self=win;win.top=win;win.parent=win;win.frames=win;
        win.opener=null;win.closed=false;win.length=0;win.name='';
        win.status='';win.innerWidth=1920;win.innerHeight=1080;
        win.outerWidth=1920;win.outerHeight=1040;
        win.screenX=0;win.screenY=0;
        win.pageXOffset=0;win.pageYOffset=0;win.devicePixelRatio=1;
        win.history={length:1,pushState:function(){},replaceState:function(){},go:function(){}};
        win.localStorage={getItem:function(){return null;},setItem:function(){},
            removeItem:function(){},clear:function(){}};
        win.sessionStorage={getItem:function(){return null;},setItem:function(){},
            removeItem:function(){},clear:function(){}};
        win.getComputedStyle=function(el){
            var s=el&&el.style||{};
            return{
                getPropertyValue:function(p){return s[p]||'';},
                length:Object.keys(s).length,
                item:function(i){return Object.keys(s)[i]||'';},
                cssText:'',fontFamily:'Arial',fontSize:'14px',fontSizeAdjust:'none',
                fontStyle:'normal',fontWeight:'normal',fontVariant:'normal',
                lineHeight:'normal',letterSpacing:'normal',wordSpacing:'normal',
                textDecoration:'none',textTransform:'none',textIndent:'0px',
                textShadow:'none',whiteSpace:'normal',overflow:'visible',
                display:'block',position:'static',visibility:'visible',
                width:'0px',height:'0px',top:'0px',left:'0px',right:'0px',bottom:'0px',
                margin:'0px',padding:'0px',border:'0px none rgb(0, 0, 0)',
                backgroundColor:'rgba(0, 0, 0, 0)',color:'rgb(0, 0, 0)',
                opacity:'1',zIndex:'auto',cursor:'auto',resize:'none',
                get overflowX(){return this.overflow;},
                get overflowY(){return this.overflow;},
                get cssFloat(){return 'none';},
                get float(){return 'none';},
                get clear(){return 'none';},
                get position(){return 'static';},
                get display(){return 'block';},
                get visibility(){return 'visible';'},
                get width(){return '0px';},
                get height(){return '0px';},
                get content(){return 'none';},
                get borderLeftWidth(){return '0px';},
                get borderTopWidth(){return '0px';},
                get borderRightWidth(){return '0px';},
                get borderBottomWidth(){return '0px';},
                get marginLeft(){return '0px';},
                get marginTop(){return '0px';},
                get marginRight(){return '0px';},
                get marginBottom(){return '0px';},
                get paddingLeft(){return '0px';},
                get paddingTop(){return '0px';},
                get paddingRight(){return '0px';},
                get paddingBottom(){return '0px';},
                map:function(){return{};},
                forEach:function(){},
            };
        };
        win.visualViewport={width:1920,height:1080,offsetLeft:0,offsetTop:0,
            pageLeft:0,pageTop:0,scale:1,addEventListener:function(t,fn,o){__cf_addEvt(this,t,fn,o);},
            removeEventListener:function(t,fn,o){__cf_remEvt(this,t,fn,o);}};
        win.requestAnimationFrame=function(cb){return __cf_setTimeout(cb,16);};
        win.cancelAnimationFrame=function(id){__cf_clearTimeout(id);};
        win.requestIdleCallback=function(cb,opts){return __cf_setTimeout(function(){cb({didTimeout:false,timeRemaining:function(){return 50;}});},opts&&opts.timeout||0);};
        win.cancelIdleCallback=function(id){__cf_clearTimeout(id);};
        win.queueMicrotask=function(fn){try{fn();}catch(e){}};
        win.structuredClone=function(v){return JSON.parse(JSON.stringify(v));};
        win.createImageBitmap=function(img){return Promise.resolve({width:0,height:0,close:function(){}});};
        win.setTimeout=__cf_setTimeout;
        win.clearTimeout=__cf_clearTimeout;
        win.setInterval=__cf_setInterval;
        win.clearInterval=__cf_clearInterval;
        win.atob=__cf_atob;
        win.btoa=__cf_btoa;
        win.TextEncoder=__cf_TE;
        win.TextDecoder=__cf_TD;
        win.addEventListener=function(type,fn,opts){__cf_addEvt(this,type,fn,opts);};
        win.removeEventListener=function(type,fn,opts){__cf_remEvt(this,type,fn,opts);};
        win.dispatchEvent=function(e){return __cf_dispatchEvt(this,e);};
        win.postMessage=function(msg,origin){
            var payload=typeof msg==='string'?msg:(typeof msg==='object'?JSON.stringify(msg):String(msg));
            globalThis.__cf_sensor_payload=payload;
            globalThis.__cf_sensor_url=origin||'';
            var resp={status:'ok',ts:Date.now(),type:'response'};
            try{if(win.onmessage)win.onmessage({data:resp,origin:location.href,source:win,type:'message'});}catch(e){}
            try{if(win._onmessage)win._onmessage({data:resp,origin:location.href,source:win,type:'message'});}catch(e){}
            __cf_fireEvt(win,'message',{data:resp,origin:location.href,source:win,type:'message',bubbles:false,cancelable:false});
        };
        win.chrome={runtime:{id:undefined,connect:function(){return{onMessage:{addListener:function(){},removeListener:function(){}},postMessage:function(){}};},
            sendMessage:function(){},onMessage:{addListener:function(){},removeListener:function(){}},onConnect:{addListener:function(){},removeListener:function(){}}},
            loadTimes:function(){return{};},csi:function(){return{};}};
        win.close=function(){};win.blur=function(){};
        win.focus=function(){};win.open=function(){return null;};
        win.print=function(){};
        win.scrollX=0;win.scrollY=0;win.isSecureContext=true;
        win.origin=_loc.origin;win.crossOriginIsolated=false;
        win.self=win;win.top=win;win.parent=win;win.frames=win;
        __cf_docObj.defaultView=win;
        globalThis.window=win;
        globalThis.self=win;
        globalThis.top=win;
        globalThis.parent=win;
        globalThis.frames=win;
        globalThis.navigator=win.navigator;
        globalThis.document=win.document;
        globalThis.screen=win.screen;
        globalThis.performance=win.performance;
        globalThis.crypto=win.crypto;
        globalThis.location=win.location;
        globalThis.localStorage=win.localStorage;
        globalThis.sessionStorage=win.sessionStorage;
        globalThis.getComputedStyle=win.getComputedStyle;
        globalThis.requestAnimationFrame=win.requestAnimationFrame;
        globalThis.cancelAnimationFrame=win.cancelAnimationFrame;
        globalThis.addEventListener=function(type,fn,opts){__cf_addEvt(win,type,fn,opts);};
        globalThis.removeEventListener=function(type,fn,opts){__cf_remEvt(win,type,fn,opts);};
        globalThis.dispatchEvent=function(e){return __cf_dispatchEvt(win,e);};
        globalThis.atob=win.atob;
        globalThis.btoa=win.btoa;
        globalThis.postMessage=win.postMessage;
        globalThis.setTimeout=__cf_setTimeout;
        globalThis.clearTimeout=__cf_clearTimeout;
        globalThis.setInterval=__cf_setInterval;
        globalThis.clearInterval=__cf_clearInterval;
        globalThis.chrome=win.chrome;
        globalThis.origin=win.origin;
        globalThis.isSecureContext=true;
        globalThis.TextEncoder=__cf_TE;
        globalThis.TextDecoder=__cf_TD;
    """.trimIndent()

    // ── 7 Post-install tweaks: clientInformation, wffQc7, scripts shim, URL/USP

    private const val POST_INSTALL_SHIM = """
        (function(){
            /* navigator.clientInformation — alias for navigator in real browsers.
               The JSD main.js function R() reads:
                 p.clientInformation || p.parent && p.parent.clientInformation
               Without this, R() returns undefined and h.r throws TypeError. */
            if(typeof navigator!=='undefined'){
                navigator.clientInformation=navigator;
                if(!navigator.connection){
                    Object.defineProperty(navigator,'connection',{
                        value:{downlink:100,rtt:50,effectiveType:'4g',saveData:false},
                        writable:false,configurable:true
                    });
                } else if(typeof navigator.connection.downlink==='undefined'){
                    navigator.connection.downlink=100;
                }
            }
            if(typeof window!=='undefined'){
                window.clientInformation=window.navigator;
            }

            /* wffQc7 — called by the JSD sensor script's z() function to
               compute a canvas fingerprint hash.  In a real browser this is
               a complex fingerprinting function; here we provide a stub that
               returns deterministic values so z() completes without error. */
            function wffQc7(text,extra,prefix,state){
                if(typeof state==='undefined') state={};
                var key=(prefix||'')+String(text||'');
                var h=0;
                for(var i=0;i<key.length;i++){
                    h=((h<<5)-h)+key.charCodeAt(i);
                    h=h&0x7FFFFFFF;
                }
                state['k'+(prefix||'')]=h;
                return state;
            }
            globalThis.wffQc7=wffQc7;

            /* fUPs3 — some JSD variants check for this global as a callback
               object set by the parent frame.  Provide a no-op so the typeof
               check does not throw. */
            if(typeof fUPs3==='undefined'){
                globalThis.fUPs3={};
            }
        })();

        (function(){
            /* document.scripts — CF reads script tags via getElementsByTagName('script'). */
            var _origGetByTag=document.getElementsByTagName.bind(document);
            document.getElementsByTagName=function(tag){
                if(tag==='script'){
                    var list=_origGetByTag(tag);
                    if(list.length===0){
                        var fake={src:'/cdn-cgi/challenge-platform/scripts/jsd/main.js',
                            nonce:'',async:false,type:'text/javascript',
                            getAttribute:function(n){return this[n]||'';}
                        };
                        var arr=[fake];
                        arr.item=function(i){return this[i];};
                        arr.namedItem=function(){return null;};
                        arr.refresh=function(){};
                        return arr;
                    }
                    return list;
                }
                return _origGetByTag(tag);
            };
            Object.defineProperty(document,'scripts',{
                get:function(){return document.getElementsByTagName('script');},
                configurable:true
            });
        })();

        (function(){
            /* URLSearchParams — proper implementation, parses ?-prefixed and bare query strings. */
            function __cf_USP(init){
                this._pairs=[];
                if(typeof init==='string'){
                    var pairs=init.split('&');
                    for(var i=0;i<pairs.length;i++){
                        if(!pairs[i])continue;
                        var eq=pairs[i].indexOf('=');
                        var k=eq<0?decodeURIComponent(pairs[i]):decodeURIComponent(pairs[i].substring(0,eq));
                        var v=eq<0?'':decodeURIComponent(pairs[i].substring(eq+1));
                        this._pairs.push([k,v]);
                    }
                } else if(init&&typeof init==='object'){
                    var keys=Object.keys(init);
                    for(var i=0;i<keys.length;i++){
                        var val=init[keys[i]];
                        if(Array.isArray(val)){
                            for(var j=0;j<val.length;j++) this._pairs.push([keys[i],String(val[j])]);
                        } else {
                            this._pairs.push([keys[i],String(val==null?'':val)]);
                        }
                    }
                }
            }
            __cf_USP.prototype.get=function(k){
                for(var i=0;i<this._pairs.length;i++){
                    if(this._pairs[i][0]===k) return this._pairs[i][1];
                }
                return null;
            };
            __cf_USP.prototype.getAll=function(k){
                var r=[];
                for(var i=0;i<this._pairs.length;i++){
                    if(this._pairs[i][0]===k) r.push(this._pairs[i][1]);
                }
                return r;
            };
            __cf_USP.prototype.has=function(k){
                for(var i=0;i<this._pairs.length;i++){
                    if(this._pairs[i][0]===k) return true;
                }
                return false;
            };
            __cf_USP.prototype.set=function(k,v){
                this.delete(k);
                this._pairs.push([k,String(v==null?'':v)]);
            };
            __cf_USP.prototype.append=function(k,v){
                this._pairs.push([k,String(v==null?'':v)]);
            };
            __cf_USP.prototype.delete=function(k){
                for(var i=this._pairs.length-1;i>=0;i--){
                    if(this._pairs[i][0]===k) this._pairs.splice(i,1);
                }
            };
            __cf_USP.prototype.toString=function(){
                var parts=[];
                for(var i=0;i<this._pairs.length;i++){
                    parts.push(encodeURIComponent(this._pairs[i][0])+'='+encodeURIComponent(this._pairs[i][1]));
                }
                return parts.join('&');
            };
            __cf_USP.prototype.sort=function(){
                this._pairs.sort(function(a,b){return a[0]<b[0]?-1:a[0]>b[0]?1:0;});
            };
            __cf_USP.prototype.entries=function(){
                var arr=[];
                for(var i=0;i<this._pairs.length;i++) arr.push([this._pairs[i][0],this._pairs[i][1]]);
                return arr;
            };
            globalThis.URLSearchParams=__cf_USP;
        })();

        (function(){
            /* URL — minimal WHATWG URL parser for relative/absolute URLs. */
            function __cf_URL(spec,base){
                if(!spec) spec='';
                this.href='';
                this.protocol='https:';
                this.hostname=location.hostname;
                this.port=location.port;
                this.pathname='/';
                this.search='';
                this.hash='';
                this.origin=location.origin;
                try{
                    if(spec.indexOf('://')>0||spec.indexOf('//')===0){
                        var a=document.createElement('a');
                        a.href=spec;
                        this.href=a.href;
                        this.protocol=a.protocol;
                        this.hostname=a.hostname;
                        this.port=a.port;
                        this.pathname=a.pathname;
                        this.search=a.search;
                        this.hash=a.hash;
                        this.origin=a.protocol+'//'+a.hostname+(a.port?':'+a.port:'');
                    } else if(base){
                        var b=new __cf_URL(base);
                        this.protocol=b.protocol;
                        this.hostname=b.hostname;
                        this.port=b.port;
                        if(spec.charAt(0)==='/'){
                            this.pathname=spec;
                        } else {
                            var lastSlash=b.pathname.lastIndexOf('/');
                            this.pathname=(lastSlash>=0?b.pathname.substring(0,lastSlash+1):'/')+spec;
                        }
                        this.origin=b.origin;
                        this.href=this.protocol+'//'+this.hostname+(this.port?':'+this.port:'')+this.pathname;
                    } else {
                        this.pathname=spec;
                        this.href=location.protocol+'//'+location.hostname+spec;
                    }
                }catch(e){}
            }
            __cf_URL.prototype.toString=function(){return this.href;};
            globalThis.URL=__cf_URL;
        })();
    """

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun jsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}

/**
 * Browser fingerprint derived from the [userAgent] string.
 *
 * Cloudflare's JSD sensor cross-checks `navigator.userAgent` against
 * `navigator.userAgentData.brands[].version` and `navigator.platform`,
 * flagging mismatches between Chrome major versions or platform strings
 * (e.g. UA says "Chrome/148" but brands say "131"). This class parses the
 * Chrome major version and OS family from the UA and produces matching values
 * for all three so the shim presents a coherent fingerprint.
 */
private data class Fingerprint(
    val userAgent: String,
    /** Chrome major version extracted from the UA, e.g. "148". */
    val chromeMajor: String,
    /** navigator.platform value, e.g. "Win32". */
    val platform: String,
    /** navigator.userAgentData.platform value, e.g. "Windows". */
    val userAgentDataPlatform: String,
    /** Windows NT version derived from UA, e.g. "15.0.0". */
    val platformVersion: String,
    /** Full Chrome version string for uaFullVersion, e.g. "148.0.0.0". */
    val userAgentFullVersion: String,
    /** "Not_A Brand" major version, deterministic per year. */
    val notABrandVersion: String,
) {
    companion object {
        private val CHROME_REGEX = Regex("""Chrome/(\d+)""")
        private val CHROME_FULL_REGEX = Regex("""Chrome/(\d+(?:\.\d+)+)""")
        private val WIN_NT_REGEX = Regex("""Windows NT (\d+\.\d+)""")
        private val MAC_OS_REGEX = Regex("""Mac OS X (\d+_\d+(?:_\d+)?)""")
        private val ANDROID_REGEX = Regex("""Android (\d+(?:\.\d+)*)""")

        fun fromUserAgent(userAgent: String): Fingerprint {
            val chromeMajor = CHROME_REGEX.find(userAgent)?.groupValues?.get(1) ?: "131"
            val uaFull = CHROME_FULL_REGEX.find(userAgent)?.groupValues?.get(1) ?: "$chromeMajor.0.0.0"

            // UA → platform mapping. Three main families: Windows, macOS, Android.
            val (platform, uaDataPlatform, platformVersion) = when {
                WIN_NT_REGEX.containsMatchIn(userAgent) -> {
                    val nt = WIN_NT_REGEX.find(userAgent)!!.groupValues[1]
                    // Windows NT 10.0 → platformVersion "15.0.0" (Win11 23H2 family).
                    // NT 6.3 (8.1) → "6.3", 6.1 (7) → "6.1".
                    val pv = when (nt) {
                        "10.0" -> "15.0.0"
                        "6.3" -> "6.3"
                        "6.2" -> "6.2"
                        "6.1" -> "6.1"
                        else -> "$nt.0"
                    }
                    Triple("Win32", "Windows", pv)
                }
                MAC_OS_REGEX.containsMatchIn(userAgent) -> {
                    val mac = MAC_OS_REGEX.find(userAgent)!!.groupValues[1].replace('_', '.')
                    // macOS 14 Sonoma → platformVersion "14.0.0"; 15 → "15.0.0".
                    val pv = if (mac.contains('.')) "${mac.split('.').first()}.0.0" else "$mac.0.0"
                    Triple("MacIntel", "macOS", pv)
                }
                ANDROID_REGEX.containsMatchIn(userAgent) -> {
                    val andr = ANDROID_REGEX.find(userAgent)!!.groupValues[1]
                    val pv = if (andr.contains('.')) "$andr.0" else "$andr.0.0"
                    Triple("Linux armv8l", "Android", pv)
                }
                else -> Triple("Win32", "Windows", "15.0.0")
            }

            // Not_A Brand version is determined by Chrome version brackets,
            // but a stable value per major version is sufficient for spoofing.
            // See https://wicg.github.io/ua-client-hints/#notABrand
            val notABrandVersion = chromeMajor.toIntOrNull()?.let { "${(it / 14) * 14 + 1}" } ?: "99"

            return Fingerprint(
                userAgent = userAgent,
                chromeMajor = chromeMajor,
                platform = platform,
                userAgentDataPlatform = uaDataPlatform,
                platformVersion = platformVersion,
                userAgentFullVersion = uaFull,
                notABrandVersion = notABrandVersion,
            )
        }
    }
}

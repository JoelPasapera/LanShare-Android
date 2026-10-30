package io.github.joelpasapera.lanshare.core.console

/**
 * Script de la consola remota, portado de LanShare para Windows: reenvía al
 * registro de la app lo que la página escribiría en la consola del navegador.
 * Errores sin capturar, promesas rechazadas, recursos que no cargan y las
 * llamadas a console.error, warn, info, log y debug.
 *
 * En el móvil no hay herramientas de desarrollo, y la causa típica de una
 * pantalla en blanco es un error de JavaScript al arrancar: por eso se carga
 * al principio de &lt;head&gt;, antes que cualquier script de la página.
 *
 * Se sirve como archivo propio (/__consola.js) y no en línea: así funciona
 * también en páginas con una CSP "script-src 'self'".
 *
 * Agrupa los repetidos, envía en lotes cada 250 ms (con sendBeacon al ocultar
 * la página) y deja de enviar si el servidor responde 404 o 403.
 */
object ConsoleScript {
    const val PATH = "/__consola.js"
    const val ENDPOINT = "/__consola"

    /** Etiqueta que se inserta en las páginas. */
    const val TAG = "<script src=\"$PATH\"></script>"

    const val SOURCE = """(function(){
'use strict';
if(window.__lanshareConsole)return;window.__lanshareConsole=1;
var MAX_TEXT=2000,MAX_STACK=1500,MAX_QUEUE=100,MAX_BODY=16000,DELAY=250;
var origin=location.protocol+'//'+location.host,queue=[],dropped=0,timer=0,off=false,busy=false;
function clip(text,max){
text=String(text);if(text.length<=max)return text;
var cut=max,c=text.charCodeAt(cut-1);if(c>=0xD800&&c<=0xDBFF)cut--;
return text.substring(0,cut)+'...';}
function shorten(url){url=String(url||'');return url.indexOf(origin)===0?(url.substring(origin.length)||'/'):url;}
function frames(stack,skip,max){
var out=[],lines=String(stack||'').split('\n');
for(var i=0;i<lines.length&&out.length<max;i++){
var line=lines[i];
if(!/^\s*at\s/.test(line)&&line.indexOf('@')<0)continue;
var m=/([^\s(@]+):(\d+):(\d+)\)?\s*$/.exec(line);
if(!m)continue;
if(skip>0){skip--;continue;}
out.push(shorten(m[1])+':'+m[2]+':'+m[3]);}
return out;}
function element(node){
var text='<'+String(node.tagName).toLowerCase();
if(node.id)text+='#'+node.id;
if(typeof node.className==='string'&&node.className.trim())text+='.'+node.className.trim().split(/\s+/).join('.');
return text+'>';}
function special(item){
if(item===null)return null;
var type=typeof item;
if(type==='bigint'||type==='symbol')return String(item);
if(type==='function')return 'function '+(item.name||'(anonima)');
if(type!=='object')return null;
if(item instanceof Error)return (item.name||'Error')+': '+item.message;
if(item.nodeType===1&&typeof item.tagName==='string')return element(item);
if(typeof Map==='function'&&item instanceof Map)return 'Map('+item.size+')';
if(typeof Set==='function'&&item instanceof Set)return 'Set('+item.size+')';
return null;}
function stringify(value){
var seen=[],budget=400;
try{
var json=JSON.stringify(value,function(key,item){
if(--budget<0)return '...';
var text=special(item);if(text!==null)return text;
if(item!==null&&typeof item==='object'){if(seen.indexOf(item)>=0)return '[repetido]';seen.push(item);}
return item;});
if(typeof json==='string')return json;
}catch(e){}
try{return Object.prototype.toString.call(value);}catch(e2){return '[objeto]';}}
function describe(value){
if(typeof value==='string')return value;
if(value===undefined)return 'undefined';
if(value===null||typeof value!=='object'&&typeof value!=='function')return String(value);
var text=special(value);
return text!==null?text:stringify(value);}
function format(args){
var parts=[],next=0;
if(args.length>1&&typeof args[0]==='string'&&args[0].indexOf('%')>=0){
var fmt=args[0],out='';next=1;
for(var i=0;i<fmt.length;i++){
var ch=fmt.charAt(i),spec=fmt.charAt(i+1);
if(ch!=='%'||!spec||'sdifoOc'.indexOf(spec)<0){out+=ch;continue;}
i++;
if(next>=args.length){out+='%'+spec;continue;}
var arg=args[next++];
if(spec==='c')continue;
if(spec==='d'||spec==='i')out+=typeof arg==='symbol'?'NaN':String(parseInt(arg,10));
else if(spec==='f')out+=typeof arg==='symbol'?'NaN':String(parseFloat(arg));
else out+=describe(arg);}
parts.push(out);}
for(;next<args.length;next++)parts.push(describe(args[next]));
return parts.join(' ');}
function push(level,text,where,stack){
if(off)return;
where=clip(where||'',MAX_STACK);
text=clip(text,MAX_TEXT);stack=stack?clip(stack,MAX_STACK):'';
var last=queue[queue.length-1];
if(last&&last.l===level&&last.m===text&&last.s===where)last.n++;
else if(queue.length>=MAX_QUEUE)dropped++;
else queue.push({l:level,m:text,s:where,k:stack,n:1});
if(!timer)timer=setTimeout(function(){flush(false);},DELAY);}
function send(page,batch,lost,beacon){
var body=JSON.stringify({p:page,d:lost,e:batch});
if(beacon&&navigator.sendBeacon){try{if(navigator.sendBeacon('/__consola',body))return;}catch(e){}}
try{
var xhr=new XMLHttpRequest();
xhr.open('POST','/__consola',true);
xhr.setRequestHeader('Content-Type','text/plain;charset=UTF-8');
xhr.onload=function(){if(xhr.status===404||xhr.status===403)off=true;};
xhr.send(body);
}catch(e2){}}
function flush(beacon){
if(timer){clearTimeout(timer);timer=0;}
if(off||!queue.length&&!dropped)return;
var items=queue,lost=dropped,page=location.pathname+location.search,batch=[],size=0;
queue=[];dropped=0;
for(var i=0;i<items.length;i++){
var piece=JSON.stringify(items[i]).length+1;
if(batch.length&&size+piece>MAX_BODY){send(page,batch,lost,beacon);lost=0;batch=[];size=0;}
batch.push(items[i]);size+=piece;}
send(page,batch,lost,beacon);}
function hook(name){
var original=console[name];
if(typeof original!=='function')return;
console[name]=function(){
if(!busy&&!off){
busy=true;
try{
var stack='';
for(var i=0;i<arguments.length;i++){if(arguments[i] instanceof Error){stack=String(arguments[i].stack||'');break;}}
push(name,format(arguments),frames(new Error().stack,1,1)[0],stack);
}catch(e){}
busy=false;}
return original.apply(console,arguments);};}
var names=['error','warn','info','log','debug'];
for(var n=0;n<names.length;n++)hook(names[n]);
window.addEventListener('error',function(event){
if(off)return;
try{
var target=event.target;
if(target&&target!==window&&target.nodeType===1){
push('error','No se pudo cargar <'+String(target.tagName).toLowerCase()+'> '+shorten(target.currentSrc||target.src||target.href||''),'','');
return;}
var error=event.error;
push('error',event.message||(error&&error.message)||'Error',
event.filename?shorten(event.filename)+':'+(event.lineno||0)+':'+(event.colno||0):'',
error&&error.stack?String(error.stack):'');
}catch(e){}},true);
window.addEventListener('unhandledrejection',function(event){
if(off)return;
try{
var reason=event.reason,stack=reason instanceof Error&&reason.stack?String(reason.stack):'';
push('error','Promesa rechazada sin capturar: '+describe(reason),frames(stack,0,1)[0],stack);
}catch(e){}});
window.addEventListener('pagehide',function(){flush(true);});
document.addEventListener('visibilitychange',function(){if(document.visibilityState==='hidden')flush(true);});
})();
"""
}

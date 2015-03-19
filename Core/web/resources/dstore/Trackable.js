//>>built
define("dstore/Trackable","dojo/_base/lang dojo/_base/declare dojo/aspect dojo/when dojo/promise/all dojo/_base/array dojo/on".split(" "),function(A,w,E,y,p,B,F){function C(g,k,q){for(var r=g.length-1;0<=r;--r){var l=g[r],v=l.start,l=v+l.count;if(k>l){g.splice(r+1,0,{start:k,count:q-k});return}q>=v&&(k=Math.min(k,v),q=Math.max(q,l),g.splice(r,1))}g.unshift({start:k,count:q-k})}var G=0,z={track:function(){function g(){return function(){var d=this;return y(this.inherited(arguments),function(a){a=d._results=
a.slice();d._ranges=[];C(d._ranges,0,a.length);return a})}}function k(){return function(d){var a=this,e=d.start,f=d.end,c=this.inherited(arguments);y(c,function(b){return y(b.totalLength,function(c){var d=a._partialResults||(a._partialResults=[]);f=Math.min(f,e+b.length);d.length=c;c=[e,f-e].concat(b);d.splice.apply(d,c);C(a._ranges,e,f);return b})});return c}}function q(d,a){G++;var e=a.target;a=A.delegate(a,z[d]);y(t._results||t._partialResults,function(f){if(f){var c,b,s,m=t._ranges,h,g="id"in
a?a.id:r.getIdentity(e),k=-1,l=-1,n=-1,u=-1;if("delete"===d||"update"===d)for(c=0;-1===k&&c<m.length;++c){h=m[c];b=h.start;for(s=b+h.count;b<s;++b)if(r.getIdentity(f[b])==g){k=a.previousIndex=b;l=c;f.splice(k,1);h.count--;for(b=c+1;b<m.length;++b)m[b].start--;break}}if("add"===d||"update"===d){if(x){if(x([e]).length){s=0;for(var l=m.length-1,g=-1,p,q;s<=l&&-1===n;)c=s+Math.round((l-s)/2),h=m[c],b=f.slice(h.start,h.start+h.count),"beforeId"in a&&(g=null===a.beforeId?b.length:D(b,a.beforeId)),-1===
g&&(g=k>=Math.max(0,h.start-1)&&k<=h.start+h.count?k:r.defaultNewToStart?0:b.length),b.splice(g,0,e),p=B.indexOf(x(b),e),q=h.start+p,0===p&&0!==h.start?l=c-1:p>=b.length-1&&q<f.length?s=c+1:(n=q,u=c)}}else{b=-1;if("beforeId"in a)if(null===a.beforeId)n=f.length,b=m.length-1;else{c=0;for(s=m.length;-1===u&&c<s;++c)h=m[c],n=D(f,a.beforeId,h.start,h.start+h.count),-1!==n&&(u=c)}else"update"===d?(n=k,u=l):r.defaultNewToStart?b=n=0:(n=f.length,b=m.length-1);-1!==b&&-1===u&&(h=m[b])&&(h.start<=n&&n<=h.start+
h.count)&&(u=b)}if(-1<n&&-1<u){a.index=n;f.splice(n,0,e);m[u].count++;for(c=u+1;c<m.length;++c)m[c].start++}}a.totalLength=f.length}(f=t["on_tracked"+d])&&f.call(t,a)})}var r=this.store||this,l=[],v={add:1,update:1,"delete":1},p;for(p in v)l.push(this.on(p,function(d){return function(a){q(d,a)}}(p)));var t=w.safeMixin(A.delegate(this),{_ranges:[],fetch:g(),fetchRange:k(),releaseRange:function(d,a){if(this._partialResults){a:for(var e=this._ranges,f=0,c;c=e[f];++f){var b=c.start,g=b+c.count;if(d<=
b)if(a>=g)e.splice(f,1);else{c.start=a;c.count=g-c.start;break a}else if(d<g)if(a>b){e.splice(f,1,{start:b,count:d-b},{start:a,count:g-a});break a}else c.count=d-c.start}for(e=d;e<a;++e)delete this._partialResults[e]}},on:function(d,a){var e=this,f=this.getInherited(arguments);return F.parse(t,d,a,function(c,b){return b in v?E.after(t,"on_tracked"+b,a,!0):f.call(e,b,a)})},tracking:{remove:function(){for(;0<l.length;)l.pop().remove();this.remove=function(){}}},track:null});this.fetchSync&&w.safeMixin(t,
{fetchSync:g(),fetchRangeSync:k()});var x;B.forEach(this.queryLog,function(d){var a=x,e=d.querier;e&&(x=a?function(d){return e(a(d))}:e)});var z={add:{index:void 0},update:{previousIndex:void 0,index:void 0},"delete":{previousIndex:void 0}},D=function(d,a,e,f){f=void 0!==f?f:d.length;for(e=void 0!==e?e:0;e<f;++e)if(r.getIdentity(d[e])===a)return e;return-1};return t}};p=w(null,z);p.create=function(g,k){g=w.safeMixin(A.delegate(g),z);w.safeMixin(g,k);return g};return p});
//# sourceMappingURL=Trackable.js.map
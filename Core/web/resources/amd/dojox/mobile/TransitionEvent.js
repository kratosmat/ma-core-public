//>>built
define(["dojo/_base/declare","dojo/on"],function(a,b){return a("dojox.mobile.TransitionEvent",null,{constructor:function(a,b,c){this.transitionOptions=b;this.target=a;this.triggerEvent=c||null},dispatch:function(){b.emit(this.target,"startTransition",{bubbles:!0,cancelable:!0,detail:this.transitionOptions,triggerEvent:this.triggerEvent})}})});
//# sourceMappingURL=TransitionEvent.js.map
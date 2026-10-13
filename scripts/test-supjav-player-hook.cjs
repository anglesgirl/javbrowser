const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const listeners = {}, reports = [], timers = new Map();
let timerId = 0, label = 'EVS', observer;
const player = {src:'https://lk1.supremejav.com/player/EVS'};
const context = {
  window:{}, Android:{onSupjavPlayerFrame:(...args)=>reports.push(args), onSupjavServerChanging:()=>reports.push(['clear'])},
  JumpChain:key=>'https://lk1.supremejav.com/player/'+key,
  document:{documentElement:{}, querySelector:s=>s.includes('iframe#video')?player:{textContent:label},
    addEventListener:(name,fn)=>listeners[name]=fn},
  MutationObserver:class {constructor(fn){observer=fn;} observe(){}},
  setTimeout:fn=>{timers.set(++timerId,fn);return timerId;},clearTimeout:id=>timers.delete(id)
};
function flush(){const queue=[...timers.values()];timers.clear();queue.forEach(fn=>fn());}
function click(key){const target={closest:()=>target,getAttribute:()=>key};listeners.click({target});label=key;}
vm.runInNewContext(fs.readFileSync('app/src/main/assets/supjav-player-hook.js','utf8'),context);
assert.deepEqual(reports,[['https://lk1.supremejav.com/player/EVS','EVS']]);
click('LUC');flush();
assert.deepEqual(reports.at(-1),['clear']);
listeners.load({target:player});
assert.deepEqual(reports.at(-1),['clear']); // Old iframe's late load must not resurrect EVS.
player.src='https://lk1.supremejav.com/player/LUC';observer();flush();
assert.deepEqual(reports.at(-1),[player.src,'LUC']);
const count=reports.length;listeners.load({target:player});assert.equal(reports.length,count);
click('LUC');flush();assert.deepEqual(reports.at(-1),['clear']);
listeners.load({target:player});assert.deepEqual(reports.at(-1),[player.src,'LUC']);
console.log('SUPJAV Server changes, late callbacks, duplicate loads and same-Server retry passed');

import { initialize, callTool } from "./_mcp_client.mjs";
await initialize();
const txt = (r) => { const c=r?.result?.content; return Array.isArray(c)? c.map(x=>x.text ?? "(image)").join(" | ") : JSON.stringify(r).slice(0,900); };
const code = `(function(){
  var g = function(n){ return Group.all.filter(function(x){return x.name===n;})[0]; };
  var wp = function(n){
    var grp = g(n), out = [];
    (grp.children||[]).forEach(function(c){ if(c.mesh){ var v=new THREE.Vector3(); v.setFromMatrixPosition(c.mesh.matrixWorld); out.push([+v.x.toFixed(2),+v.y.toFixed(2),+v.z.toFixed(2)]); } });
    return out;
  };
  return { t: Timeline.time, rFoot: wp('r_foot'), lFoot: wp('l_foot'), rShin: wp('r_shin'), rHand: wp('r_hand'), head: wp('head').slice(0,2) };
})()`;
console.log(txt(await callTool("risky_eval", { code })).slice(0,1400));

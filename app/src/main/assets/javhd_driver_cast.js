// Driver for the javhdporn direct extraction (cast stage).
//
// Runs alongside the bundled javhd_cast.js in a blank hidden WebView
// that holds only the .box-server/.srv anchors. Overrides jQuery.ajax
// BEFORE cast.js loads, so the click handler's POST to /api/play/ is
// captured instead of executed: the app POSTs natively (ECH) and feeds
// the response back into the handler's own .done() callback, which runs
// binddata and sets window.videodata to the embed URL.
//
// Per-click calls/callbacks are kept in arrays (one entry per Part),
// because the app triggers every Part's click on the same page before
// feeding any response.
(function() {
  window.__javhdAjaxCalls = [];
  window.__javhdDoneCbs = [];
  var jq = window.jQuery;
  if (!jq) { window.__javhdError = 'no-jquery'; return; }
  jq.ajax = function(opts) {
    var o = (typeof opts === 'string') ? { url: opts } : (opts || {});
    window.__javhdAjaxCalls.push({
      url: o.url || '',
      type: o.type || o.method || 'GET',
      data: o.data || null
    });
    var jqXHR = {
      done: function(fn) { window.__javhdDoneCbs.push(fn); return jqXHR; },
      fail: function(fn) { return jqXHR; },
      always: function(fn) { return jqXHR; },
      then: function(a, b) { if (a) window.__javhdDoneCbs.push(a); return jqXHR; }
    };
    return jqXHR;
  };
})();

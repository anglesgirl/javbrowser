// Capture the site's own AJAX without replacing its sources/decode logic.
// /api/play/ has one stable dispatcher per request. Startup requests such as
// /api/scribe/ must not shift the Part -> play callback mapping.
(function() {
  window.__javhdAjaxCalls = [];
  window.__javhdDoneCbs = [];
  var jq = window.jQuery;
  if (!jq) { window.__javhdError = 'no-jquery'; return; }

  jq.ajax = function(opts, settings) {
    var o = typeof opts === 'string' ? (settings || {}) : (opts || {});
    var url = typeof opts === 'string' ? opts : (o.url || '');
    var callbacks = [];
    var entry = {
      url: url,
      type: o.type || o.method || 'GET',
      data: o.data || null,
      playIndex: -1
    };
    var jqXHR = {
      done: function(fn) { if (typeof fn === 'function') callbacks.push(fn); return jqXHR; },
      fail: function() { return jqXHR; },
      always: function() { return jqXHR; },
      then: function(fn) { if (typeof fn === 'function') callbacks.push(fn); return jqXHR; }
    };
    if (/(?:^|\/)api\/play\/?(?:[?#]|$)/i.test(url)) {
      entry.playIndex = window.__javhdDoneCbs.length;
      window.__javhdDoneCbs.push(function(response) {
        callbacks.slice().forEach(function(fn) { fn.call(window, response, 'success', jqXHR); });
      });
    }
    window.__javhdAjaxCalls.push(entry);
    return jqXHR;
  };

  // Old pages expose genuine server links. Current data-mpu pages instead
  // bind the real player button; fabricating a .srv changes the code path.
  window.__javhdTriggerPart = function(index) {
    var servers = jq('.box-server .srv');
    if (servers.length) {
      if (index < 0 || index >= servers.length) return 'server-index-out-of-range';
      servers.removeClass('default-srv');
      servers.eq(index).trigger('click');
      return 'server';
    }
    var player = document.getElementById('video-player');
    if (index !== 0 || !player || !player.getAttribute('data-mpu')) return 'no-player-token';
    var button = jq('#video-player .play-button.button');
    if (button.length !== 1) return 'no-unique-play-button';
    button.trigger('click');
    return 'player-button';
  };
})();

// Driver for the javhdporn direct extraction (embed stage).
//
// Runs alongside the bundled javhd_embed_main.js in a blank hidden
// WebView holding only <div id="jwplayer" data-config="...">. Stubs the
// global jwplayer() the way the embed page's own player script provides
// it, and captures whatever setup() receives — that config holds the
// real file URL. Any other player method is accepted and ignored
// (chainable), the way the page's own player behaves.
(function() {
  window.__jwSetup = null;
  function makePlayer() {
    var p = new Proxy({}, {
      get: function(t, prop) {
        if (prop === 'then') return undefined;
        return function() {
          var args = Array.prototype.slice.call(arguments);
          if (prop === 'setup' && args.length > 0) {
            try { window.__jwSetup = JSON.stringify(args[0]); } catch (e) { window.__jwSetup = 'ERR'; }
          }
          return p;
        };
      },
      set: function(t, prop, v) { return true; }
    });
    return p;
  }
  window.jwplayer = function(id) { return makePlayer(); };
  window.jwplayer.key = 'fsyPPl++p0zCkXk0GDqVGaosf6IyGoQBlx69rPB55Ww4bMO/jsmomA==';
})();

(function () {
  "use strict";

  // Kotlin passes the localized labels and site domains in location.hash.
  var landingData = (function () {
    try {
      var match = /(?:^|&)data=([^&]*)/.exec(location.hash.substring(1));
      if (!match) return null;
      return JSON.parse(decodeURIComponent(match[1]));
    } catch (_) {
      return null;
    }
  })() || {};
  var domains = landingData.domains || {};
  var searchInput = document.getElementById("searchInput");
  var searchResults = document.getElementById("searchResults");
  var javCodes = [];
  var javCodePattern = /\b(FC2(?:[-_\s]?PPV)?[-_\s]?\d{5,10}|[A-Za-z]{2,8}-\d{1,5}(?:-\d{2,5})?)\b/gi;

  function fillText(id, text) {
    var element = document.getElementById(id);
    if (element && text != null) element.textContent = text;
  }

  function detectJavCodes(text) {
    var match;
    var codes = [];
    javCodePattern.lastIndex = 0;
    while ((match = javCodePattern.exec(text)) !== null) {
      var code = match[1].toUpperCase();
      if (codes.indexOf(code) === -1) codes.push(code);
    }
    return codes;
  }

  function updateJavHint(codes) {
    var hint = document.getElementById("jav-hint");
    var label = document.getElementById("jav-detected-label");
    if (!hint || !label) return;
    if (codes.length > 0) {
      hint.style.display = "block";
      if (codes.length === 1) {
        label.textContent = (landingData.bookmarkSinglePrefix || "") + codes[0];
      } else {
        label.textContent = (landingData.bookmarkBatchPrefix || "") + codes.length +
          (landingData.bookmarkCountSuffix || "") + codes.slice(0, 3).join(", ") +
          (codes.length > 3 ? "…" : "");
      }
    } else {
      hint.style.display = "none";
    }
  }

  function handleSaveJav() {
    if (!javCodes.length) return;
    var button = document.getElementById("btn-save-jav");
    button.disabled = true;
    button.style.cursor = "not-allowed";
    button.style.background = "linear-gradient(to right, #7B1FA2 0%, #BB86FC 0%)";
    document.getElementById("jav-detected-label").textContent =
      (landingData.savingLabel || "") + "0 / " + javCodes.length;
    window.Android.saveJavCodes(JSON.stringify(javCodes));
  }

  // Called by landing-client.js for native batchProgress messages.
  function updateBatchProgress(done, total) {
    var button = document.getElementById("btn-save-jav");
    var percent = Math.round(done / total * 100);
    button.style.background = "linear-gradient(to right, #7B1FA2 " + percent + "%, #BB86FC " + percent + "%)";
    document.getElementById("jav-detected-label").textContent =
      (landingData.savingLabel || "") + done + " / " + total;
    if (done >= total) {
      button.style.background = "#7B1FA2";
      document.getElementById("jav-detected-label").textContent =
        (landingData.savedPrefix || "") + total + (landingData.savedSuffix || "");
      window.setTimeout(function () {
        button.disabled = false;
        button.style.cursor = "pointer";
        button.style.background = "#BB86FC";
        updateJavHint(javCodes);
      }, 1800);
    }
  }

  // Expose only the callbacks consumed by the client bridge.
  window.onClipboardResult = function (text, link) {
    var card = document.getElementById("clipboard-link");
    card.hidden = !link;
    document.getElementById("clipboard-link-site").textContent = link ? link.site : "";
    document.getElementById("clipboard-link-url").textContent = link ? link.url : "";
    if (link) return;
    var codes = detectJavCodes(text || "");
    if (codes.length > 0) {
      searchInput.value = codes.join(" ");
      searchInput.dispatchEvent(new Event("input"));
    }
  };

  window.prefillJavCodes = function (codesJson) {
    var codes = JSON.parse(codesJson);
    if (codes.length > 0) {
      searchInput.value = codes.join(" ");
      searchInput.dispatchEvent(new Event("input"));
    }
  };
  window.updateBatchProgress = updateBatchProgress;

  function updateSearchResults() {
    var keyword = searchInput.value.trim();
    javCodes = detectJavCodes(keyword);
    updateJavHint(javCodes);
    if (!keyword.length) {
      searchResults.classList.remove("show");
      return;
    }

    searchResults.classList.add("show");
    document.getElementById("searchQueryHint").textContent =
      (landingData.searchHintPrefix || "") + keyword;
    document.querySelectorAll(".search-site-grid a").forEach(function (link) {
      link.setAttribute("aria-label", link.textContent + ": " + keyword);
    });

    var encoded = encodeURIComponent(keyword);
    document.getElementById("searchMissAV").href =
      "https://" + domains.missAvDomain + "/search/" + encoded;
    document.getElementById("searchJable").href = "https://jable.tv/search/" + encoded + "/";
    document.getElementById("searchAvJoy").href =
      "https://" + domains.avJoyDomain + "/search/videos/" + encoded;
    document.getElementById("searchPigAV").href =
      "https://pigav.ws/search?search=" + encoded + "&searchTarget=local";
    document.getElementById("searchAVToday").href = "https://avtoday.io/search?s=" + encoded;
    document.getElementById("searchJavHD").href = "https://www.javhdporn.net/?s=" + encoded;
    document.getElementById("search7MMTV").href =
      "https://" + domains.sevenMmTvDomain + "/zh/searchall_search/all/" + encoded + "/1.html";
    document.getElementById("searchAvple").href =
      "https://" + domains.avpleDomain + "/search?key=" + encoded;
    document.getElementById("searchWhos").href =
      "https://" + domains.whosDomain + "/result?search=" + encoded;
    document.getElementById("searchJavday").href = "https://javday.app/search/?wd=" + encoded;
    var rouVideos = document.getElementById("searchRouVideos");
    rouVideos.href = "https://" + domains.rouVideoDomain + "/search?q=" + encoded;
    document.getElementById("searchRouSeries").href = rouVideos.href + "&tab=series";
  }

  var siteActions = {
    missav: function () { window.Android.openSiteBrowse("missav", domains.missAvBase); },
    jable: function () { window.Android.openSiteBrowse("jable", "https://" + domains.jableDomain + "/"); },
    rou_video: function () { window.Android.openSiteBrowse("rou_video", "https://" + domains.rouVideoDomain + "/home"); },
    avjoy: function () { window.Android.openSiteBrowse("avjoy", "https://" + domains.avJoyDomain + "/"); },
    pigav: function () { window.Android.openSiteBrowse("pigav", "https://pigav.ws/"); },
    avtoday: function () { window.Android.openSiteBrowse("avtoday", "https://avtoday.io/cht/index.html"); },
    javhd: function () { window.Android.openSiteBrowse("javhd", "https://www.javhdporn.net/"); },
    "7mmtv": function () { window.Android.openSiteBrowse("7mmtv", domains.sevenMmTvBase); },
    avple: function () { window.Android.openSiteBrowse("avple", domains.avpleBase); },
    whos: function () { window.Android.openSiteBrowse("whos", domains.whosBase); },
    javday: function () { window.Android.openSiteBrowse("javday", "https://javday.app/"); },
    supjav: function () { window.Android.openSiteBrowse("supjav", "https://supjav.com/"); },
    javrate: function () { window.Android.openSiteBrowse("javrate", "https://www.javrate.com/"); }
  };

  function toggleAd() {
    var ad = document.getElementById("lingmaoAd");
    var collapsed = ad.classList.toggle("collapsed");
    var button = ad.querySelector(".ad-toggle");
    button.textContent = collapsed ? "+" : "−";
    try { localStorage.setItem("landingAdCollapsed", collapsed ? "1" : "0"); } catch (_) {}
  }

  function handleAction(action) {
    switch (action) {
      case "show-help": window.Android.showHelpDialog(); break;
      case "clipboard-open": window.Android.openClipboardLink(); break;
      case "clipboard-dismiss":
        document.getElementById("clipboard-link").hidden = true;
        window.Android.dismissClipboardLink();
        break;
      case "unified-search": window.Android.openUnifiedSearch(searchInput.value); break;
      case "save-jav": handleSaveJav(); break;
      case "today-new": window.Android.openTodayNew(); break;
      case "sales-rankings": window.Android.openSalesRankings(); break;
      case "playback-history": window.Android.openPlaybackHistory(); break;
      case "toggle-ad": toggleAd(); break;
      default: break;
    }
  }

  function initialize() {
    fillText("clipboard-from", landingData.clipboardFrom);
    fillText("clipboard-ask", landingData.clipboardAsk);
    fillText("clipboard-link-open", landingData.clipboardOpen);
    fillText("clipboard-link-dismiss", landingData.clipboardDismiss);
    if (landingData.searchPlaceholder) searchInput.placeholder = landingData.searchPlaceholder;
    fillText("unifiedSearchButton", landingData.unifiedSearch);
    fillText("jav-detected-label", landingData.addBookmark);
    fillText("search-fallback-summary", landingData.fallbackSummary);
    fillText("searchRouVideos", landingData.rouVideos);
    fillText("searchRouSeries", landingData.rouSeries);
    fillText("quick-todaynew", landingData.recentReleases);
    fillText("quick-sales", landingData.salesRankings);
    fillText("direct-links-divider", landingData.directLinks);
    fillText("playback-history-link", landingData.playbackHistory);
    fillText("ad-headline", landingData.adHeadline);
    fillText("ad-action", landingData.adAction);

    document.querySelectorAll("[data-site]").forEach(function (anchor) {
      anchor.addEventListener("click", function (event) {
        event.preventDefault();
        var action = siteActions[anchor.getAttribute("data-site")];
        if (action) action();
      });
    });
    document.querySelectorAll("[data-navigate]").forEach(function (anchor) {
      anchor.addEventListener("click", function (event) {
        event.preventDefault();
        window.Android.navigateToUrl(anchor.getAttribute("data-navigate"));
      });
    });
    document.addEventListener("click", function (event) {
      var target = event.target.closest("[data-action]");
      if (!target) return;
      event.preventDefault();
      if (target.getAttribute("data-action") === "toggle-ad") event.stopPropagation();
      handleAction(target.getAttribute("data-action"));
    });

    searchInput.addEventListener("input", updateSearchResults);
    searchInput.addEventListener("keypress", function (event) {
      if (event.key === "Enter" && this.value.trim().length > 0) {
        event.preventDefault();
        window.Android.openUnifiedSearch(this.value.trim());
      }
    });

    try {
      if (localStorage.getItem("landingAdCollapsed") === "1") {
        var ad = document.getElementById("lingmaoAd");
        ad.classList.add("collapsed");
        ad.querySelector(".ad-toggle").textContent = "+";
      }
    } catch (_) {}

    if (typeof window.__flushLandingBridgeMessages === "function") {
      window.__flushLandingBridgeMessages();
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initialize, { once: true });
  } else {
    initialize();
  }
  window.addEventListener("load", function () {
    window.Android.checkClipboard();
  }, { once: true });
})();

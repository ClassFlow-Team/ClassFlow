package com.shiro.classflow.ui.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import java.io.ByteArrayInputStream
import org.json.JSONObject
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.shiro.classflow.data.model.wbu.CampusDeviceKind
import com.shiro.classflow.data.model.wbu.WebAppDefinition
import com.shiro.classflow.data.model.wbu.WebAppId
import com.shiro.classflow.data.network.wbu.CampusCardLegacyLink
import com.shiro.classflow.data.network.wbu.CampusCardRedirect
import com.shiro.classflow.data.network.wbu.UjingQrLink
import com.shiro.classflow.data.network.wbu.WbuAuthTransport
import com.shiro.classflow.ui.campus.components.WasherNoticeDialog
import com.shiro.classflow.ui.campus.components.WbuAuthTipsScenario
import com.shiro.classflow.ui.campus.components.WbuCampusAuthSheet
import com.shiro.classflow.ui.campus.qrscan.QrScannerOverlay
import com.xingheyuzhuan.shiguangschedule.ui.components.UjingBrandLoading
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuLoadingPlaceholder
import com.xingheyuzhuan.shiguangschedule.ui.components.WebPageErrorInfo
import com.xingheyuzhuan.shiguangschedule.ui.components.WebPageErrorView
import com.xingheyuzhuan.shiguangschedule.ui.components.isRealWebLoadFailure
import com.xingheyuzhuan.shiguangschedule.ui.components.WebJsDialogHost
import com.xingheyuzhuan.shiguangschedule.ui.components.WebJsDialogRequest
import com.xingheyuzhuan.shiguangschedule.ui.components.WebJsDialogState
import com.xingheyuzhuan.shiguangschedule.ui.components.webDialogPageLabel
import com.shiro.classflow.data.network.wbu.WasherAvailability
import com.shiro.classflow.data.network.wbu.WasherEntryResolver
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.DESKTOP_USER_AGENT
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 原生扫码请求：区分「平台扫码页回填」、「wx 桩桥接回调」、「新中新/水控桥接回调」。 */
private sealed interface ScanRequest {
    /** 来自平台 `/plat/scan?redirectUrl=...`：扫完把结果以 `scanResult` 拼回该地址。 */
    data class Redirect(val redirectUrl: String) : ScanRequest

    /** 来自注入的 `wx` 桩：扫完通过 `window.__cfScanResolve(callbackId, result)` 回调页面。 */
    data class Bridge(val callbackId: String) : ScanRequest

    /** 来自 `em.scanQRCode`（TjtcApp 协议）：扫完通过 `window.__cfEmScanResolve(callbackId, result)` 回调页面。 */
    data class EmBridge(val callbackId: String) : ScanRequest

    /** 来自 `JsAgent.startScan`（SynATP 协议）：扫完通过 `window[callbackName]({ code: 200, data: { qrCodeUTF: result } })` 回调。 */
    data class JsAgent(val callbackName: String) : ScanRequest

    /** 来自 `AndroidFunc.SynJSNative` / `invokeNativeMethod`：扫完通过 `window[callbackName](result)` 回调页面。 */
    data class AndroidFunc(val callbackName: String) : ScanRequest
}

/**
 * 注入到 U净 等页面的 `wx` 桩脚本（幂等，document 级）。
 *
 * 背景（见研究笔记第 15、16 节）：U净 判定 `wechatWorkH5` 走的是 URL 特征；
 * - water-h5（饮水机）：自建 WebView 里 `window.wx` 未定义，`wx.scanQRCode` 会同步抛错；
 * - washer-h5（洗衣机/烘干机/洗鞋机）：`index.html` **必定加载 jweixin**，其加载时执行
 *   `window.wx = window.jWeixin = _`，会覆盖普通赋值的桩；且非微信环境下 jweixin 的
 *   `WeixinJSBridge` 不存在，`success`/`fail` 都不回调 → 扫码按钮"点了没反应"，
 *   也到不了平台兜底页 `/plat/scan`。
 *
 * 因此这里用 `Object.defineProperty` 的 getter 桩**抗覆盖**（setter 直接吞掉 jweixin 的赋值），
 * 并同时保护 `wx` 与 `jWeixin` 两个全局名，把扫码/支付桥接到原生能力。
 */
private const val WX_STUB_JS = """
(function() {
  if (window.__cfWxStubInstalled) return;
  window.__cfWxStubInstalled = true;
  window.__cfScanCallbacks = window.__cfScanCallbacks || {};
  var shim = {};
  shim.config = function () {};
  shim.ready = function (cb) { try { setTimeout(cb, 0); } catch (e) {} };
  shim.error = function () {};
  shim.checkJsApi = function (opts) {
    opts = opts || {};
    if (opts.success) { try { opts.success({ checkResult: {}, errMsg: 'checkJsApi:ok' }); } catch (e) {} }
    if (opts.complete) { try { opts.complete({ errMsg: 'checkJsApi:ok' }); } catch (e) {} }
  };
  shim.scanQRCode = function (opts) {
    opts = opts || {};
    if (window.__cfAutoScan) {
      var autoRes = String(window.__cfAutoScan);
      window.__cfAutoScan = null;
      setTimeout(function () {
        if (opts.success) { try { opts.success({ resultStr: autoRes, errMsg: 'scanQRCode:ok' }); } catch (e) {} }
        if (opts.complete) { try { opts.complete({ errMsg: 'scanQRCode:ok' }); } catch (e2) {} }
      }, 0);
      return;
    }
    var cbId = 'cfscan_' + Date.now() + '_' + Math.random().toString(36).slice(2);
    window.__cfScanCallbacks[cbId] = opts;
    try {
      window.CFWebAppBridge.scanQRCode(cbId);
    } catch (e) {
      delete window.__cfScanCallbacks[cbId];
      if (opts.fail) { try { opts.fail({ errMsg: 'scanQRCode:fail' }); } catch (e2) {} }
    }
  };
  window.__cfScanResolve = function (cbId, result) {
    var opts = (window.__cfScanCallbacks || {})[cbId];
    if (!opts) return;
    delete window.__cfScanCallbacks[cbId];
    if (result === null || result === undefined || result === '') {
      if (opts.fail) { try { opts.fail({ errMsg: 'scanQRCode:cancel' }); } catch (e) {} }
    } else {
      if (opts.success) { try { opts.success({ resultStr: String(result), errMsg: 'scanQRCode:ok' }); } catch (e) {} }
    }
    if (opts.complete) { try { opts.complete({ errMsg: 'scanQRCode:ok' }); } catch (e) {} }
  };
  function cfBuildWeixinPayUrl(o) {
    var prepay = o.prepayid || '';
    var pkg = o.package || '';
    if (!prepay && typeof pkg === 'string' && pkg.indexOf('prepay_id=') === 0) {
      prepay = pkg.substring('prepay_id='.length);
    }
    var nonce = o.nonceStr || o.noncestr || '';
    var sign = o.paySign || o.sign || '';
    var ts = o.timeStamp || o.timestamp || '';
    if (!prepay && !pkg) return '';
    var q = [];
    if (prepay) q.push('prepayid=' + encodeURIComponent(prepay));
    if (pkg) q.push('package=' + encodeURIComponent(pkg));
    if (nonce) q.push('noncestr=' + encodeURIComponent(nonce));
    if (sign) q.push('sign=' + encodeURIComponent(sign));
    if (ts) q.push('timestamp=' + encodeURIComponent(ts));
    return 'weixin://wap/pay?' + q.join('&');
  }
  shim.chooseWXPay = function (opts) {
    opts = opts || {};
    var url = opts.url || opts.mwebUrl || opts.cashierUrl || cfBuildWeixinPayUrl(opts);
    if (!url) {
      if (opts.fail) { try { opts.fail({ errMsg: 'chooseWXPay:fail' }); } catch (e) {} }
      if (opts.complete) { try { opts.complete({ errMsg: 'chooseWXPay:fail' }); } catch (e) {} }
      return;
    }
    var launched = false;
    try { launched = window.CFWebAppBridge.openExternal(String(url)); } catch (e) { launched = false; }
    setTimeout(function () {
      if (launched) {
        if (opts.success) { try { opts.success({ errMsg: 'chooseWXPay:ok' }); } catch (e) {} }
        if (opts.complete) { try { opts.complete({ errMsg: 'chooseWXPay:ok' }); } catch (e) {} }
      } else {
        if (opts.fail) { try { opts.fail({ errMsg: 'chooseWXPay:fail' }); } catch (e) {} }
        if (opts.complete) { try { opts.complete({ errMsg: 'chooseWXPay:fail' }); } catch (e) {} }
      }
    }, 300);
  };
  function cfDefineWx(name) {
    try {
      Object.defineProperty(window, name, {
        configurable: true,
        get: function () { return shim; },
        set: function () {}
      });
    } catch (e) {
      try { window[name] = shim; } catch (e2) {}
    }
  }
  cfDefineWx('wx');
  cfDefineWx('jWeixin');

  // -------------------------------------------------------------
  // 新中新生活服务 / 智能控水 多协议原生扫码桥接入（研究笔记第 18 节）
  // -------------------------------------------------------------

  // 1. window.em 桩（TjtcApp 协议，被 applications/lifeService 与 yktxyyy:5001 共同支持）
  if (!window.__cfEmStubInstalled) {
    window.__cfEmStubInstalled = true;
    window.__cfEmScanCallbacks = window.__cfEmScanCallbacks || {};
    var emShim = window.em || {};
    emShim.scanQRCode = function(opts) {
      opts = opts || {};
      var cbId = 'cfemscan_' + Date.now() + '_' + Math.random().toString(36).slice(2);
      window.__cfEmScanCallbacks[cbId] = opts;
      try {
        if (window.CFWebAppBridge && window.CFWebAppBridge.scanQRCodeEm) {
          window.CFWebAppBridge.scanQRCodeEm(cbId);
        } else if (window.CFWebAppBridge && window.CFWebAppBridge.scanQRCode) {
          window.CFWebAppBridge.scanQRCode(cbId);
        }
      } catch (e) {
        delete window.__cfEmScanCallbacks[cbId];
        if (opts.fail) { try { opts.fail(e); } catch (e2) {} }
      }
    };
    window.__cfEmScanResolve = function(cbId, result) {
      var opts = (window.__cfEmScanCallbacks || {})[cbId];
      if (!opts) return;
      delete window.__cfEmScanCallbacks[cbId];
      if (result === null || result === undefined || result === '') {
        if (opts.fail) { try { opts.fail({ errMsg: 'scanQRCode:cancel' }); } catch (e) {} }
      } else {
        if (opts.success) {
          try {
            // TjtcApp 既传 resultStr 也传整个对象，同时满足 lifeService 的 res 与 yktxyyy 的 res.resultStr
            opts.success({ resultStr: String(result), text: String(result) });
          } catch (e) {}
        }
      }
      if (opts.complete) { try { opts.complete(); } catch (e) {} }
    };
    try {
      Object.defineProperty(window, 'em', {
        configurable: true,
        get: function() { return emShim; },
        set: function(v) { if (v && typeof v === 'object') { Object.assign(emShim, v); } }
      });
    } catch (e) {
      window.em = emShim;
    }
  }

  // 2. window.JsAgent 桩（SynATP 协议，applications/lifeService 在 SynATP 模式下调用）
  if (!window.JsAgent) {
    window.JsAgent = {
      startScan: function(callbackName) {
        var cb = callbackName || 'scanCallback';
        try {
          if (window.CFWebAppBridge && window.CFWebAppBridge.scanQRCodeJsAgent) {
            window.CFWebAppBridge.scanQRCodeJsAgent(cb);
          }
        } catch (e) {
          console.error('JsAgent.startScan error:', e);
        }
      }
    };
  }
})();
"""

/**
 * 缴费页（`/charge-app/`）的令牌兜底脚本（幂等，document 级）。
 *
 * 背景（真机 + 服务端直连实测）：点「立即缴费」报的 **「服务大厅未授权(1)」** 就是
 * `blade-pay/pay` 返回的 `401 {"code":4030}`，触发条件只有一个 —— 请求头
 * `synjones-auth` 后面**没有有效令牌**（空值、缺失、双重前缀、被篡改，四种情况都复现成 4030）。
 * 而 App 自己保存的 access_token 是有效的（同一个 token 直连该接口能通过鉴权）。
 *
 * 令牌为什么会丢：`charge-app` 的 `getToken()` 从地址栏读 `token` / `synjones-auth`
 * 塞进 Vuex 后，**立刻 `history.replaceState` 把 query 抹掉**，令牌从此只存在内存里。
 * 之后任何一个接口返回 401，页面的响应拦截器会 `LogoutAction` 把 store 清空，
 * 而请求拦截器仍然机械地拼 `"bearer " + store.token` —— 拼出来就是 `bearer `，
 * 于是缴费请求被 4030 拒掉。更糟的是 `ge()` 的 `.catch` 只 `console.error`，
 * 不调失败回调，所以按钮 loading 永远停不下来、也不弹提示，看起来像「卡住」。
 *
 * 因此这里做两件事，都是**只在页面自己没给出有效令牌时**才介入：
 * 1. 把地址栏里的令牌（以及 Kotlin 侧传入的 `platformToken`）落到 `sessionStorage`，
 *    页面抹 query 之后还能捞回来；
 * 2. 接管 `XMLHttpRequest` 的 `setRequestHeader` / `send`：页面设的 `synjones-auth`
 *    先扣下，值有效就原样发出（绝不叠加，避免变成 `bearer A, bearer B`），
 *    空值或缺失才补上我们手里的令牌。
 *
 * `Authorization: Basic …` 由页面拦截器自己加，这里不碰。
 */
private const val CHARGE_APP_PAY_TOKEN_JS_TEMPLATE = """
(function () {
  if (window.__cfPayTokenHook) return;
  window.__cfPayTokenHook = true;

  var HOST = 'yktfwpt.wbu.edu.cn';
  var KEY = 'cf_pay_token';
  var BAKED = __CF_PAY_TOKEN_LITERAL__;

  function norm(v) {
    if (v === null || v === undefined) return '';
    var s = String(v);
    try { s = decodeURIComponent(s); } catch (e) {}
    // 循环剥前缀：页面 store 里万一存的是带 "bearer " 的值，拦截器会再拼一层，
    // 变成 "bearer bearer …"（服务端同样回 4030）。这里剥到只剩裸令牌为止。
    for (var i = 0; i < 4; i++) {
      var next = s.replace(/^\s*bearer\s+/i, '').trim();
      if (next === s) break;
      s = next;
    }
    s = s.trim();
    // 页面 store 被清空后，请求拦截器会机械地拼出 "bearer null" / "bearer undefined"；
    // 这种值非空但同样会被服务端判成 4030，必须当成「没有令牌」。
    if (/^(bearer|null|undefined|NaN)$/i.test(s)) return '';
    return s;
  }

  function queryValue(name) {
    try {
      var q = window.location.search || '';
      if (!q && window.location.hash && window.location.hash.indexOf('?') > -1) {
        q = window.location.hash.substring(window.location.hash.indexOf('?'));
      }
      if (!q) return '';
      var parts = q.replace(/^\?/, '').split('&');
      for (var i = 0; i < parts.length; i++) {
        var eq = parts[i].indexOf('=');
        if (eq < 0) continue;
        if (parts[i].substring(0, eq) === name) return parts[i].substring(eq + 1);
      }
    } catch (e) {}
    return '';
  }

  function stored() {
    try { return sessionStorage.getItem(KEY) || ''; } catch (e) { return ''; }
  }

  function remember(v) {
    var t = norm(v);
    if (!t) return '';
    window.__cfPayToken = t;
    // 只存我们自己的键，**不动页面自己的 sessionStorage 键**（`access_token_pay`）。
    // 页面 `Logout` mutation 里有 `sessionStorage.clear()`，那是它主动登出；
    // 我们若把令牌写回去，会让登出永远不生效（还可能形成 401 → 登出 → 复活 → 401 的死循环）。
    // 令牌只在真正要发缴费请求时由 send()/fetch 现取现用，够用了。
    try { sessionStorage.setItem(KEY, t); } catch (e) {}
    return t;
  }

  function capture() {
    var t = norm(queryValue('token')) || norm(queryValue('synjones-auth'));
    if (t) return remember(t);
    return '';
  }

  function currentToken() {
    var t = norm(window.__cfPayToken) || norm(stored()) || capture() || norm(BAKED);
    if (t) remember(t);
    return t;
  }

  function isPayUrl(url) {
    if (!url) return false;
    var u = String(url);
    return u.indexOf('/blade-pay/') > -1 || u.indexOf('/blade-auth/') > -1;
  }

  function sameHost(url) {
    if (!url) return false;
    var u = String(url);
    if (u.indexOf('//') < 0) return true;
    return u.indexOf(HOST) > -1;
  }

  capture();

  // 供 Kotlin 侧在每次 SPA 路由变化时调用：页面抹 query 的时机不确定，
  // 每次回调都试着从地址栏再捞一次（捞到就是最新的，捞不到就保持已有值）。
  window.__cfPayCapture = function () {
    var t = capture();
    if (t) remember(t);
    return t;
  };
  window.__cfPayCurrent = currentToken;

  var rawSetHeader = XMLHttpRequest.prototype.setRequestHeader;
  var rawOpen = XMLHttpRequest.prototype.open;
  var rawSend = XMLHttpRequest.prototype.send;

  XMLHttpRequest.prototype.open = function (method, url) {
    try { this.__cfUrl = url; this.__cfAuthValue = null; } catch (e) {}
    return rawOpen.apply(this, arguments);
  };

  // **关键：不能在这里把页面设的头放行后再补一次。**
  // 浏览器会把同名头合并成 `bearer <A>, bearer <B>`（实测长度 1242 = 两份 620 字符），
  // 服务端判 4030「服务大厅未授权(1)」—— 这正是 2026-11-06 真机上「明明带了 token 却报未授权」的根因。
  //
  // 所以策略是：页面设的 synjones-auth **当场规范化并扣下**，绝不原样发出；
  // 由 send() 统一补发唯一的一份。非缴费接口也照常发（否则账单接口会全挂）。
  XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
    try {
      if (String(name).toLowerCase() === 'synjones-auth') {
        this.__cfAuthValue = value;
        // 缴费接口：先扣下，send() 里统一发一份规范化后的值
        if (isPayUrl(this.__cfUrl) && sameHost(this.__cfUrl)) return;
        // 其它接口：把页面的值规范化后原样发出（保持只有一个值）
        var own = norm(value);
        if (own) return rawSetHeader.call(this, 'synjones-auth', 'bearer ' + own);
        return; // 页面给的是空值/null，就不带这个头，交给服务端按未登录处理
      }
    } catch (e) {}
    return rawSetHeader.apply(this, arguments);
  };

  XMLHttpRequest.prototype.send = function () {
    try {
      var pay = isPayUrl(this.__cfUrl) && sameHost(this.__cfUrl);
      if (pay) {
        // 页面值有效就用它的令牌，否则用我们手里的兜底。
        // 无论哪种情况这里**只设一次**，因此不会出现双值。
        var pageToken = norm(this.__cfAuthValue);
        var finalToken = pageToken || currentToken();
        if (finalToken) rawSetHeader.call(this, 'synjones-auth', 'bearer ' + finalToken);
      }
    } catch (e) {}
    return rawSend.apply(this, arguments);
  };

  if (window.fetch && window.Headers) {
    var rawFetch = window.fetch;
    window.fetch = function (input, init) {
      try {
        var url = typeof input === 'string' ? input : (input && input.url);
        init = init || {};
        var headers = new Headers(init.headers || (input && input.headers) || undefined);
        var own = norm(headers.get('synjones-auth'));
        if (isPayUrl(url) && sameHost(url)) {
          // 缴费：规范化成单值（页面值有效就用，否则兜底）
          var finalToken = own || currentToken();
          if (finalToken) headers.set('synjones-auth', 'bearer ' + finalToken);
          else headers.delete('synjones-auth');
          init.headers = headers;
        } else if (headers.has('synjones-auth')) {
          // 其它接口：也统一成单值，去掉可能存在的多重前缀
          if (own) headers.set('synjones-auth', 'bearer ' + own);
          else headers.delete('synjones-auth');
          init.headers = headers;
        }
      } catch (e) {}
      return rawFetch.apply(this, arguments);
    };
  }})();
"""

/**
 * 生成缴费页令牌兜底脚本，把 Kotlin 侧拿到的平台令牌烘进去。
 *
 * 页面 `getToken()` 会在应用启动早期就把地址栏 query 抹掉，`onPageFinished` 再注入时
 * 往往已经读不到 URL 里的令牌；`platformToken` 是容器自己换到的有效 access_token，
 * 因此作为最后一道兜底（见 [CHARGE_APP_PAY_TOKEN_JS_TEMPLATE]）。
 *
 * 转义走 [quoteJsStringLiteral] 而不是 `JSONObject.quote`：后者在 JVM 单元测试里
 * 是 not-mocked 的桩（抛 RuntimeException），会让这套回归测试没法跑。
 */
internal fun buildChargeAppPayTokenJs(platformToken: String?): String =
    CHARGE_APP_PAY_TOKEN_JS_TEMPLATE.replace(
        "__CF_PAY_TOKEN_LITERAL__",
        quoteJsStringLiteral(platformToken.orEmpty())
    )

/**
 * 把字符串转成可直接嵌进 JS 的双引号字面量。
 *
 * 只处理会破坏字面量的字符（反斜杠、双引号、换行、回车、制表符、行分隔符），
 * 其余原样保留 —— 令牌是 Base64URL 字符集，本身不含这些。
 */
internal fun quoteJsStringLiteral(raw: String): String {
    val sb = StringBuilder(raw.length + 2)
    sb.append('"')
    for (ch in raw) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u2028' -> sb.append("\\u2028")
            '\u2029' -> sb.append("\\u2029")
            else -> sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

/**
 * lifeService 页面返回箭头的兜底脚本（幂等，document 级）。
 *
 * 背景（真机实测）：2-3 栋洗浴（`/applications/lifeService`）的顶栏返回箭头点了没反应 ——
 * 页面的返回链路是 `根.back()` → `child.back()` → `$parent.back()`（chunk-b88c7b1e 的
 * `back(){this.$parent.back()}`），而 `router-view ref="child"` 解析到的正是 lifeService
 * 组件自己，于是在「根 ↔ 子」之间无限递归，最后抛 `RangeError` 被 Vue 的事件错误处理吞掉，
 * **永远走不到 `history.go(-1)`**。系统返回键不受影响（WebView 历史确实有上一层）。
 *
 * 因此在捕获阶段拦下箭头的点击（`stopPropagation`，页面的递归处理函数不再执行），
 * 改走 `CFWebAppBridge.pageBack()`：有历史就回退、没历史才退出容器 —— 与系统返回键同义。
 * 只拦 `.van-nav-bar__left`（平台根组件渲染的顶栏返回），页面里其余按钮一概不碰；
 * 结果面板里的「返回」按钮（`showResult=false`，关面板）语义不同，也不在此列。
 */
private const val LIFE_SERVICE_BACK_SHIM_JS = """
(function() {
  if (window.__cfLifeServiceBackShimmed) return;
  window.__cfLifeServiceBackShimmed = true;
  document.addEventListener('click', function (ev) {
    try {
      var node = ev.target;
      while (node && node !== document) {
        if (node.classList && node.classList.contains('van-nav-bar__left')) {
          ev.preventDefault();
          ev.stopPropagation();
          if (window.CFWebAppBridge && window.CFWebAppBridge.pageBack) {
            window.CFWebAppBridge.pageBack();
          }
          return;
        }
        node = node.parentNode;
      }
    } catch (e) {
      console.error('lifeService back shim error:', e);
    }
  }, true);
})();
"""

/**
 * `window.open` 兜底脚本（门户专用，见 [WebAppDefinition.shimWindowOpen]）。
 *
 * 移动门户（uni-app）打开应用靠 `window.open(url)`。Android WebView 默认
 * `setSupportMultipleWindows(false)`：新窗口建不起来，这次跳转可能被直接丢掉
 * （点应用图标没反应）；就算真开了新窗口，新窗口里的跳转也不会经过本容器的拦截规则
 * —— 微信统一页与 CAS 票据桥都会失效。
 *
 * 因此把它改成「当前窗口跳转」：跳转变成主框架导航，规则 0~3 全都拦得到，
 * 用户点哪个应用都会落在同一个 WebView 里（返回键也就自然能回到门户）。
 *
 * 返回一个空壳窗口对象而不是 null：页面里常见 `var w = window.open(…); w.close()`
 * 这类写法，返回 null 会在页面自己的代码里抛错。真实 `window.open` 挂在
 * `__cfWindowOpenReal` 上备查（幂等，document 级）。
 */
private const val WINDOW_OPEN_SHIM_JS = """
(function() {
  if (window.__cfWindowOpenShimmed) return;
  window.__cfWindowOpenShimmed = true;
  window.__cfWindowOpenReal = window.open;
  var stub = {
    closed: false,
    close: function () { this.closed = true; },
    focus: function () {},
    blur: function () {},
    postMessage: function () {},
    document: { write: function () {}, close: function () {} },
    location: {
      href: 'about:blank',
      replace: function (u) { try { location.href = String(u); } catch (e) {} },
      assign: function (u) { try { location.href = String(u); } catch (e) {} }
    }
  };
  window.open = function (url) {
    if (url === undefined || url === null || url === '') return stub;
    try {
      location.href = String(url);
    } catch (e) {}
    return stub;
  };
})();
"""

/**
 * 通用网页应用全屏容器。
 *
 * 特性：
 * 1. 彻底移除顶部 TopAppBar 与底栏，只全屏展示 Web 内容，且内部避让系统状态栏与手势条；
 * 2. 右上方带一个半透明、可拖拽的圆形悬浮球供退出页面；
 * 3. 拦截物理/手势返回键：若当前处于页面主页（或已无法回退），直接退出页面容器；
 * 4. 彻底阻断并劫持跳向 CAS Web 统一认证登录页的行为：
 *    · 定义为「票据桥」应用（门户）时，先用本机凭据换一张票据、把落地地址交给 WebView，
 *      让业务系统自己核销（门户里的 ehall / zxst 都靠它）；
 *    · 其余情况转为拉起原生的 [WbuCampusAuthSheet]；
 * 5. 网页里的微信授权依赖分两类处理（见 [WebAppWechat]）：能跳过企业微信中转的直接换票跳过，
 *    跳不过的（真正需要微信）盖一层「需要微信授权」统一页（提示 + 复制链接 + 返回）；
 * 6. 旧一卡通微信服务链接（`yktyd.wbu.edu.cn` 的 `wechat` 路径下）自动改道到新一卡通平台的对应功能页
 *    （付款码直接进原生付款码页），见 [CampusCardLegacyLink]；
 * 7. WebVPN 下注入通行证 Cookie，保障校外穿透。
 */
@Composable
fun WebAppScreen(
    navBridge: NavBridge,
    appId: String,
    initialTargetUrl: String? = null,
    pendingAutoScan: String? = null,
    /** 页面自己授权完成之后才落上去的 hash 路由，见 [Destination.WebApp.pendingHashRoute]。 */
    pendingHashRoute: String? = null,
    /** 一卡通平台应用编码（旧一卡通链接改道带进来），见 [Destination.WebApp.campusCardAppCode]。 */
    campusCardAppCode: String? = null,
    /** 一卡通平台应用深链兜底，见 [Destination.WebApp.campusCardDeepLinkFallback]。 */
    campusCardDeepLinkFallback: String? = null,
    /** 这个容器为一件具体的事而开，办完即收起，见 [Destination.WebApp.endsWithTask]。 */
    endsWithTask: Boolean = false,
    viewModel: WebAppViewModel = viewModel()
) {
    /** 仅洗衣机（/wm/ 或小天鹅链接）进入时显示 U净 品牌首屏。 */
    val isWasherEntry = remember(pendingAutoScan) { isWasherRaw(pendingAutoScan) }
    /** 只带设备码、没有直达地址的洗衣机深链：需要先解析出洗衣机 H5 真实入口。 */
    val isWasherDeepLink = isWasherEntry && initialTargetUrl.isNullOrBlank()

    /**
     * 这个容器是**为一件具体的事**开的（如取水页点「去充值」）：见 [Destination.WebApp.endsWithTask]。
     *
     * 由调用方显式声明，不拿 `campusCardAppCode` 反推 —— 门户里点一卡通功能入口改道进来时
     * 同样带 appCode，但那是浏览，不能因为用户走回首页就把他踢出去。
     */
    val isTaskContainer = endsWithTask

    /**
     * 退出这个容器的落点。
     *
     * 洗衣机是唯一「退出 H5 后还有事要做」的入口：扫完码走完下单流程，用户最需要的是
     * 看进度、设洗完提醒 —— 这两样都在本应用的洗衣机页，而不是他扫码前待的课表页或设置页。
     * 所以洗衣机入口统一落到洗衣机页；别的网页应用保持原来的「返回上一页」。
     *
     * 用 [NavBridge.finishTo] 而不是 navigate：从洗衣机页里点「扫码」进来的话，
     * 栈顶下面本来就是洗衣机页，退回去不该再叠一份相同的页面。
     */
    val exitContainer: () -> Unit = remember(navBridge, isWasherEntry) {
        {
            if (isWasherEntry) {
                navBridge.finishTo(
                    Destination.CampusDeviceHub(CampusDeviceKind.WASHER.name)
                )
            } else {
                navBridge.popBackStack()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        /** 回填页面结果用：WebView 的方法只能在它自己的线程（主线程）上调用，见 [postToWebViewThread]。 */
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        val uiState by viewModel.uiState.collectAsState()

        /** 本应用自己的宿主（真实宿主 + WebVPN 代理宿主）：返回键判「首页」与地址修补都用它。 */
        val ownHosts = remember(uiState.definition) { ownHostsOf(context, uiState.definition) }

        var sslErrorState by remember { mutableStateOf<Pair<SslErrorHandler, SslError>?>(null) }
        var webViewInstance by remember { mutableStateOf<WebView?>(null) }
        var scanRequest by remember { mutableStateOf<ScanRequest?>(null) }
        /** 内容区正盖着自己的错误页：此时隐藏悬浮退出球，别压住错误页的按钮。 */
        var pageErrorVisible by remember { mutableStateOf(false) }

        /** 最终用于加载的入口地址（洗衣机深链解析完成后才有值；其余场景等于入参）。 */
        var resolvedInitialUrl by rememberSaveable(pendingAutoScan) { mutableStateOf(initialTargetUrl) }
        var washerResolving by rememberSaveable(pendingAutoScan) { mutableStateOf(isWasherDeepLink) }

        /** 洗衣机不可下单提示（离线 / 占用 / 预约 / 故障 / 停用 / 码无效），null 表示无提示。 */
        var washerNotice by remember { mutableStateOf<WasherAvailability.Unavailable?>(null) }

        /**
         * VM 是否已按应用定义开始流程。
         *
         * 未开始时 [WebAppUiState.stage] 只是默认占位值（ProbingNetwork）：
         * 洗衣机深链解析中、设备不可下单（此时根本不会启动 VM）都属于这种情况，
         * 若照常渲染就会露出「校园网环境检测」这一与洗衣机无关的页面。
         */
        var vmStarted by rememberSaveable(pendingAutoScan) { mutableStateOf(false) }

        /**
         * 深链的一次性自启（地址里的 `scanResult` 载体 / [pendingAutoScan] 注入）是否已经执行过。
         *
         * 这类自启都是「页面一挂载就直接开一单」的动作（2-3 栋淋浴 `prices` 为空时直接开用水、
         * 洗衣机直接下单、1 栋洗浴直接 `useWater`），可它躺在导航参数里：进程被系统回收后重建、
         * 或转屏重建，地址与原样都会重放一遍 —— 现场表现就是「切后台过一会儿回来又重新开始订单」。
         *
         * 标记放保存状态：同一个导航条目重建时会以 true 回来，于是只把页面本身打开
         * （页面自己会读设备状态：还在用水就显示「使用中」），不再自动开单；
         * 重新扫码进入的是新的导航条目，标记是全新的 false，自启照旧。
         */
        var deepLinkAutoStarted by rememberSaveable(pendingAutoScan) { mutableStateOf(false) }

        // 洗衣机深链这次导航条目是否已记过使用：重建（转屏 / 进程恢复）时不重复计数
        var washerUseRecorded by rememberSaveable(pendingAutoScan) { mutableStateOf(false) }

        LaunchedEffect(pendingAutoScan) {
            // 深链原文在下面几处都要用：取成本地非空值，避免每处再判一次
            val washerRaw = pendingAutoScan
            if (isWasherDeepLink && washerRaw != null) {
                // /wm/{uuid}：先解析洗衣机 H5 真实入口，避免只停在一卡通首页
                when (val result = WasherEntryResolver.resolve(context, washerRaw)) {
                    is WasherEntryResolver.Result.Ready -> {
                        resolvedInitialUrl = result.url
                        // 真正用上了（拿到直达地址）：记进校园设备管理。
                        // 扫码路径（QrScanViewModel）自己会记，这里只管深链路径；
                        // 同一个导航条目重建时不重复记（record 本身幂等，但次数不该虚增）。
                        if (!washerUseRecorded) {
                            washerUseRecorded = true
                            runCatching { WasherEntryResolver.recordUse(context, washerRaw) }
                        }
                    }
                    is WasherEntryResolver.Result.Unavailable -> washerNotice = result.availability
                    WasherEntryResolver.Result.Failed -> resolvedInitialUrl = null
                }
                washerResolving = false
            }
        }

        LaunchedEffect(
            appId,
            resolvedInitialUrl,
            washerResolving,
            washerNotice,
            campusCardAppCode,
            campusCardDeepLinkFallback
        ) {
            if (!washerResolving && washerNotice == null) {
                // 同一个导航条目又回到前台（去程是被别的页面盖住：门户里点开一卡通充值、付款码…）：
                // VM 手里的 WebView 会话还在，页面就是离开前那一页（选项卡、滚动位置都在），
                // 再走一遍 start 反而会把这一页换成重新加载过的首页。
                if (!viewModel.hasLiveSession(appId)) {
                    viewModel.start(appId, resolvedInitialUrl, campusCardAppCode, campusCardDeepLinkFallback)
                }
                vmStarted = true
            }
        }

        // CAS 票据桥刚换到的落地地址：交给 WebView 加载（同一个容器、同一个 Cookie 罐，
        // 业务系统核销票据后下的会话就落在它手里）。一次性状态，加载完立即清掉
        LaunchedEffect(uiState.casBridgeUrl, webViewInstance) {
            val target = uiState.casBridgeUrl ?: return@LaunchedEffect
            val webView = webViewInstance ?: return@LaunchedEffect
            Log.i("WebAppScreen", "CAS 票据桥落地，交给 WebView 加载：$target")
            webView.loadUrl(target)
            viewModel.onCasBridgeUrlConsumed()
        }

        washerNotice?.let { notice ->
            WasherNoticeDialog(
                notice = notice,
                onDismiss = {
                    washerNotice = null
                    exitContainer()
                }
            )
        }

        val dismissScan = remember(webViewInstance) {
            {
                val request = scanRequest
                val webView = webViewInstance
                if (request != null && webView != null) {
                    when (request) {
                        is ScanRequest.Bridge -> {
                            // 取消：回调 null 触发 U净 的 fail 分支
                            postToWebViewThread(mainHandler) {
                                webView.evaluateJavascript(
                                    "window.__cfScanResolve(${JSONObject.quote(request.callbackId)}, null);",
                                    null
                                )
                            }
                        }
                        is ScanRequest.EmBridge -> {
                            postToWebViewThread(mainHandler) {
                                webView.evaluateJavascript(
                                    "window.__cfEmScanResolve(${JSONObject.quote(request.callbackId)}, null);",
                                    null
                                )
                            }
                        }
                        is ScanRequest.JsAgent -> {
                            val callback = request.callbackName.ifBlank { "scanCallback" }
                            postToWebViewThread(mainHandler) {
                                webView.evaluateJavascript(
                                    """
                                    (function() {
                                        var cb = window[${JSONObject.quote(callback)}];
                                        if (typeof cb === 'function') {
                                            try { cb({ code: 500, msg: '用户取消' }); } catch (e) {}
                                        }
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            }
                        }
                        is ScanRequest.AndroidFunc -> {
                            val callback = request.callbackName.ifBlank { "scanCallback" }
                            postToWebViewThread(mainHandler) {
                                webView.evaluateJavascript(
                                    """
                                    (function() {
                                        var cb = window[${JSONObject.quote(callback)}];
                                        if (typeof cb === 'function') {
                                            try { cb(null); } catch (e) {}
                                        }
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            }
                        }
                        is ScanRequest.Redirect -> { /* 页面跳转类取消无需主动注入 */ }
                    }
                }
                scanRequest = null
            }
        }

        // 只在真需要用户登录统一认证时弹面板：进容器时缺凭据、以及网页撞到 CAS 登录页而凭据自愈也没成功
        if (uiState.needLogin) {
            WbuCampusAuthSheet(
                onDismiss = { viewModel.onLoginDismissed() },
                onLoginSuccess = { viewModel.onLoginSuccess() },
                requireUnifiedCas = true,
                unifiedAuthOnly = true,
                initialUseVpnOverride = uiState.requireVpnForLogin || uiState.temporaryUseVpn,
                title = uiState.definition?.let { stringResource(it.titleRes) },
                tipsScenario = WbuAuthTipsScenario.IDENTITY,
                onNavigateToAccount = { navBridge.navigate(Destination.CredentialManagement) }
            )
        }

        // 拦截返回键：主页退出或历史回退
        BackHandler {
            // 优先关闭正在进行的扫码浮层并通知页面取消，防止按返回键时退回上一页
            if (scanRequest != null) {
                dismissScan()
                return@BackHandler
            }

            // 其次关闭「需要微信授权」统一页：返回键在这里的语义是「关掉这一页」，
            // 顺带回退一次历史（回到门户里原来那一页），而不是退出整个容器
            if (uiState.wechatGate != null) {
                viewModel.dismissWechatGate()
                webViewInstance?.let { if (it.canGoBack()) it.goBack() }
                return@BackHandler
            }

            val webView = webViewInstance
            if (webView != null) {
                val currentUrl = webView.url.orEmpty()
                val def = uiState.definition
                val isDeepLinked = !initialTargetUrl.isNullOrBlank() || !pendingAutoScan.isNullOrBlank()

                if (isDeepLinked) {
                    // 全局扫码直达场景：用户扫码直接进入洗衣/洗烘程序选择页（programV2 / reserveV2）。
                    // 在程序选择页、扫码异常页（scanError）或落地首页（home）按返回键直接退出容器回 ClassFlow，
                    // 彻底解决在第三方 OAuth 历史链与自动跳转逻辑间卡住「退不出去」的问题。
                    if (isThirdPartyEntryRoute(currentUrl, def)) {
                        Log.i("WebAppScreen", "Back on deep-linked third-party entry route ($currentUrl), exiting to ClassFlow")
                        exitContainer()
                        return@BackHandler
                    }
                } else {
                    // 普通平台门户浏览场景：按之前要求，仅在 #/home 时跳回平台主页
                    val home = def?.homeUrl
                    if (!home.isNullOrBlank() && isThirdPartyHomeRoute(currentUrl, def)) {
                        Log.i("WebAppScreen", "Back on third-party home route, returning to platform home: $home")
                        webView.loadUrl(home)
                        return@BackHandler
                    }
                }

                if (!isAtHomeRoute(currentUrl, def, ownHosts) && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    exitContainer()
                }
            } else {
                exitContainer()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .safeDrawingPadding()
            ) {
            val stage = uiState.stage
            when {
                // 洗衣机（U净）：深链解析中 → 取 token 全程显示同一个 U净 品牌首屏，
                // logo 不重播淡入；不可下单时不显示加载态，交由提示弹窗说明
                isWasherEntry && (!vmStarted || stage is WebAppStage.LoadingToken) -> {
                    if (washerNotice == null) {
                        UjingBrandLoading(
                            message = stringResource(R.string.ujing_washer_checking),
                            showSpinner = true
                        )
                    }
                }

                // 非洗衣机网页应用（一卡通 / 图书馆）才走校园网探测与通道选择
                stage is WebAppStage.ProbingNetwork -> {
                    CampusNetworkProbeOverlay(
                        statusText = uiState.probeStatusText,
                        isOffCampus = false,
                        onSelectCampus = { viewModel.chooseContinueDirect() },
                        onSelectVpn = { viewModel.chooseUseVpnTemporarily() }
                    )
                }

                stage is WebAppStage.OffCampusChoice -> {
                    CampusNetworkProbeOverlay(
                        statusText = stringResource(R.string.desc_off_campus_detected),
                        isOffCampus = true,
                        onSelectCampus = { viewModel.chooseContinueDirect() },
                        onSelectVpn = { viewModel.chooseUseVpnTemporarily() }
                    )
                }

                stage is WebAppStage.LoadingToken -> {
                    // 非洗衣机网页应用（预留）：淡蓝底 + WBU 编钟纹样
                    WbuLoadingPlaceholder(
                        message = stringResource(R.string.status_fetching_webapp_token)
                    )
                }

                stage is WebAppStage.ContentReady -> {
                    FullScreenWebContent(
                        viewModel = viewModel,
                        // 深链自启已经执行过的条目（重建场景）：把地址里的一次性载体摘掉再加载，
                        // 否则页面重新挂载就会再开一单（见 [deepLinkAutoStarted]）
                        targetUrl = if (deepLinkAutoStarted) stripDeepLinkAutoTrigger(stage.url) else stage.url,
                        useVpn = stage.useVpn,
                        definition = uiState.definition,
                        platformToken = stage.token,
                        // 同理：重建时不再把自启载荷交给页面
                        pendingAutoScan = if (deepLinkAutoStarted) null else pendingAutoScan,
                        pendingHashRoute = pendingHashRoute,
                        onSslError = { handler, error -> sslErrorState = Pair(handler, error) },
                        onWebAuthRedirect = { pageUrl -> viewModel.onWebAuthRedirect(pageUrl) },
                        onWechatBlocked = { blockedUrl -> viewModel.onWechatBlocked(blockedUrl, webViewInstance?.url) },
                        onCasTicketNeeded = { service, blockedUrl -> viewModel.requestCasTicket(service, blockedUrl) },
                        onCampusCardRedirect = { redirect ->
                            // 旧一卡通链接改道：付款码进原生页，其余功能进新平台对应应用页。
                            // 用 navigate（入栈）而不是替换，返回键能回到原来的页面
                            when (redirect) {
                                CampusCardRedirect.NativePayCode -> navBridge.navigate(Destination.CampusCardPayCode)
                                is CampusCardRedirect.PlatformApp -> navBridge.navigate(
                                    Destination.WebApp(
                                        appId = WebAppId.CAMPUS_CARD.name,
                                        campusCardAppCode = redirect.appCode,
                                        campusCardDeepLinkFallback = redirect.deepLinkFallback
                                    )
                                )
                            }
                        },
                        onInterceptScan = { redirectUrl -> scanRequest = ScanRequest.Redirect(redirectUrl) },
                        onBridgeScan = { callbackId -> scanRequest = ScanRequest.Bridge(callbackId) },
                        onEmScan = { callbackId -> scanRequest = ScanRequest.EmBridge(callbackId) },
                        onJsAgentScan = { callbackName -> scanRequest = ScanRequest.JsAgent(callbackName) },
                        onAndroidFuncScan = { callbackName -> scanRequest = ScanRequest.AndroidFunc(callbackName) },
                        onDeepLinkAutoStartHandedOff = { deepLinkAutoStarted = true },
                        onPageErrorVisibleChanged = { pageErrorVisible = it },
                        onPageBackExit = exitContainer,
                        /** 一卡通任务容器跳回平台首页时「任务已完成」：见 [FullScreenWebContent] 的说明。 */
                        taskEndOnPlatformHome = isTaskContainer,
                        onTaskFinished = exitContainer,
                        onWebViewReady = { webViewInstance = it }
                    )
                }

                stage is WebAppStage.Error -> {
                    // 取 Token / 票据失败这类业务错误：与上面网络失败用同一张错误页。
                    // 这类失败没有具体页面地址（还没走到页面），所以只给说明与出路。
                    WebPageErrorView(
                        info = WebPageErrorInfo(url = null, code = null, description = null),
                        title = stringResource(R.string.title_load_failed),
                        message = stage.message,
                        onReload = { viewModel.retry() },
                        onBack = exitContainer
                    )
                }
            }

            // 可拖拽的悬浮退出球（仅在页面内容或加载阶段显示；错误页有自己的「返回」，别压住它）
            if (!pageErrorVisible &&
                (uiState.stage is WebAppStage.ContentReady || uiState.stage is WebAppStage.LoadingToken)
            ) {
                DraggableExitFab(
                    onExit = exitContainer
                )
            }

            // SSL 证书异常弹窗
            sslErrorState?.let { (handler, _) ->
                AlertDialog(
                    onDismissRequest = {
                        handler.cancel()
                        sslErrorState = null
                    },
                    title = { Text(stringResource(R.string.dialog_ssl_error_title)) },
                    text = { Text(stringResource(R.string.dialog_ssl_error_message)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                handler.proceed()
                                sslErrorState = null
                            }
                        ) {
                            Text(stringResource(R.string.action_continue_browsing))
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = {
                                handler.cancel()
                                sslErrorState = null
                            }
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
            }

            /*
             * 「需要微信授权」统一页：盖在整个 WebView 之上。
             * 微信授权这一步客户端代替不了（在 App 里那一页是微信自己的报错页），
             * 所以给出说明 + 目标站点 + 复制链接 + 返回，用户复制链接去微信里继续。
             */
            uiState.wechatGate?.let { gate ->
                WebAppWechatOverlay(
                    targetHost = WebAppWechat.hostOf(gate.blockedUrl).orEmpty(),
                    copyUrl = gate.copyUrl,
                    onCopy = {
                        copyToClipboard(context, gate.copyUrl)
                        Toast.makeText(
                            context,
                            context.getString(R.string.wechat_auth_copied),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onBack = {
                        viewModel.dismissWechatGate()
                        webViewInstance?.let { if (it.canGoBack()) it.goBack() }
                    }
                )
            }

            // 原生扫码浮层（复用「扫一扫」的相机取景；针对 U净 wx 桩桥接 / 平台扫码页回填）
            // 放在 safeDrawingPadding 之外：取景与顶部渐变遮罩可覆盖状态栏，横竖屏一致
            scanRequest?.let { request ->
                QrScannerOverlay(
                    onDismiss = dismissScan,
                    onScanned = { rawResult ->
                        val webView = webViewInstance
                        scanRequest = null
                        val hairdryer = com.shiro.classflow.data.network.wbu.UjingQrLink.parse(rawResult) as? com.shiro.classflow.data.network.wbu.UjingQrLink.Result.Hairdryer
                        if (hairdryer != null) {
                            // 吹风机不归本容器管：判型（手机蓝牙 → 支付宝 / 云端 4G → 一卡通页面）
                            // 与首次确认都在过渡页里做，这里直接换过去
                            navBridge.replace(
                                Destination.HairdryerLaunch(
                                    cd = hairdryer.cd,
                                    raw = hairdryer.raw,
                                    source = com.shiro.classflow.data.model.wbu.HairdryerLaunchSource.SCAN.name
                                )
                            )
                            return@QrScannerOverlay
                        }
                        if (webView != null && rawResult.isNotBlank()) {
                            // 相机解码回调可能不在主线程，而 WebView 的 loadUrl / evaluateJavascript
                            // 都必须在主线程调用（见 [postToWebViewThread]），这里统一兜一层。
                            when (request) {
                                is ScanRequest.Redirect -> {
                                    val separator = if (request.redirectUrl.contains("?")) "&" else "?"
                                    val encoded = java.net.URLEncoder.encode(rawResult, "UTF-8")
                                    val finalCallbackUrl = "${request.redirectUrl}${separator}scanResult=$encoded"
                                    Log.i("WebAppScreen", "Loading scan callback URL: $finalCallbackUrl")
                                    postToWebViewThread(mainHandler) { webView.loadUrl(finalCallbackUrl) }
                                }

                                is ScanRequest.Bridge -> {
                                    val js = "window.__cfScanResolve(${JSONObject.quote(request.callbackId)}, ${JSONObject.quote(rawResult)});"
                                    Log.i("WebAppScreen", "Resolving wx stub scan result")
                                    postToWebViewThread(mainHandler) { webView.evaluateJavascript(js, null) }
                                }

                                is ScanRequest.EmBridge -> {
                                    val js = "window.__cfEmScanResolve(${JSONObject.quote(request.callbackId)}, ${JSONObject.quote(rawResult)});"
                                    Log.i("WebAppScreen", "Resolving em stub scan result")
                                    postToWebViewThread(mainHandler) { webView.evaluateJavascript(js, null) }
                                }

                                is ScanRequest.JsAgent -> {
                                    val callback = request.callbackName.ifBlank { "scanCallback" }
                                    val js = """
                                        (function() {
                                            var cb = window[${JSONObject.quote(callback)}];
                                            if (typeof cb === 'function') {
                                                try {
                                                    cb({ code: 200, data: { qrCodeUTF: ${JSONObject.quote(rawResult)} } });
                                                } catch (e) {}
                                            }
                                        })();
                                    """.trimIndent()
                                    Log.i("WebAppScreen", "Resolving JsAgent scan result: $callback")
                                    postToWebViewThread(mainHandler) { webView.evaluateJavascript(js, null) }
                                }

                                is ScanRequest.AndroidFunc -> {
                                    val callback = request.callbackName.ifBlank { "scanCallback" }
                                    val js = """
                                        (function() {
                                            var cb = window[${JSONObject.quote(callback)}];
                                            if (typeof cb === 'function') {
                                                try { cb(${JSONObject.quote(rawResult)}); } catch (e) {}
                                            } else if (typeof window.scanCallback === 'function') {
                                                try { window.scanCallback(${JSONObject.quote(rawResult)}); } catch (e2) {}
                                            }
                                        })();
                                    """.trimIndent()
                                    Log.i("WebAppScreen", "Resolving AndroidFunc scan result: $callback")
                                    postToWebViewThread(mainHandler) { webView.evaluateJavascript(js, null) }
                                }
                            }
                        }
                    },
                    hint = stringResource(R.string.webapp_scan_hint)
                )
            }
        }
    }
}


/**
 * 在主线程执行一段调用 [WebView] 的代码。
 *
 * [WebView] 的每个公开方法都带线程检查（`WebView.checkThread`）：只要不是创建它的那个线程
 * （本类里恒为主线程）调用，就会直接抛出
 * `Throwable: A WebView method was called on thread ...`。
 * 扫码结果回填可能来自相机分析线程（见 [QrScannerView] 的说明），异常一旦被上层的
 * `try/catch` 吃掉，页面就永远收不到结果，表现为「扫了没反应」——所以这里统一兜一层。
 */
private fun postToWebViewThread(handler: Handler, block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        handler.post(block)
    }
}

/**
 * 从「网页要选文件」的结果里取出用户选中的文件。
 *
 * 不能只用 [WebChromeClient.FileChooserParams.parseResult]：`<input type="file" multiple>` 这种请求
 * Chrome 会带上 `EXTRA_ALLOW_MULTIPLE`，Android 14+ 的系统照片选择器于是按「多选」返回结果 ——
 * 选中的照片装在结果的 `ClipData` 里，`Intent.getData()` 是空的，而 `parseResult` 只认后者，
 * 于是「明明选好了照片」被当成用户取消：网页收到的文件是 null，文件输入框直接触发 `cancel`。
 * （真机实测：门户里「人脸采集」的 van-uploader 就是 multiple，卡片选择之后一直提交不上去。）
 *
 * 因此先自己按 ClipData → data → EXTRA_STREAM 的顺序取，取不到再回落到官方的 `parseResult`。
 */
private fun pickFileChooserUris(resultCode: Int, data: Intent?): Array<Uri>? {
    if (resultCode != android.app.Activity.RESULT_OK || data == null) return null

    val uris = mutableListOf<Uri>()
    data.clipData?.let { clip ->
        for (index in 0 until clip.itemCount) {
            clip.getItemAt(index)?.uri?.let { uris.add(it) }
        }
    }
    data.data?.let { uris.add(it) }
    when (val stream = data.extras?.get(Intent.EXTRA_STREAM)) {
        is Uri -> uris.add(stream)
        is List<*> -> uris.addAll(stream.filterIsInstance<Uri>())
    }
    if (uris.isNotEmpty()) return uris.toTypedArray()

    return runCatching {
        WebChromeClient.FileChooserParams.parseResult(resultCode, data)
    }.getOrNull()
}


/** 复制一段文本到系统剪贴板（「需要微信授权」统一页的「复制链接」用）。 */
private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("ClassFlow", text)) }
}

/**
 * 用系统浏览器打开一个地址（错误页的「用浏览器打开」用）。
 *
 * 有些页面在内置 WebView 里走不通但浏览器里没问题（例如自己会跳 `intent://` 的支付页、
 * 需要用户手势才认的 App Links），所以留这条逃生口。没有浏览器可处理时给一句提示。
 */
private fun openInExternalBrowser(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (!opened) {
        Log.w("WebAppScreen", "没有应用能打开这个地址：$url")
        Toast.makeText(
            context,
            context.getString(R.string.format_no_app_handle_request, "HTTP"),
            Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * 深链里的一次性「自启」载体：`CampusShowerEntryResolver` 把扫码原文拼在地址最后
 * （`…&scanResult=<URLEncoder 原文>`），lifeService 页面在 `mounted` 里读它就直接开单。
 */
private const val DEEP_LINK_AUTO_START_PARAM = "scanResult="

/**
 * 摘掉地址里的一次性自启载体 [DEEP_LINK_AUTO_START_PARAM]。
 *
 * 页面 `urlCallBackhandle()` 只要在地址里读到 `scanResult`，就会拿它去 `getDevicesType`；
 * 2-3 栋淋浴在没有档位价格时紧接着 `deviceStatus(1)` 开单 —— 也就是说**地址每被重新加载一次
 * 就多开一单**（切后台被系统回收 → 回来自动重建 → 页面重新挂载）。已经自启过的条目重建时
 * 用它把参数摘掉，页面就只打开设备页、显示服务端当前状态。
 *
 * `scanResult` 的值经 `URLEncoder.encode`，内部不含裸 `&`/`#`，所以按 `&` 切段丢弃即可，
 * 其余参数（`_dt` / `_implid` / `feeitemid` / `appId` / `synjones-auth`…）原样保留。
 */
internal fun stripDeepLinkAutoTrigger(url: String): String {
    val hashAt = url.indexOf('#')
    val fragment = if (hashAt >= 0) url.substring(hashAt) else ""
    val head = if (hashAt >= 0) url.substring(0, hashAt) else url
    val queryAt = head.indexOf('?')
    if (queryAt < 0) return url

    val prefix = head.substring(0, queryAt)
    val segments = head.substring(queryAt + 1).split('&')
    val kept = segments.filterNot { it.startsWith(DEEP_LINK_AUTO_START_PARAM) }
    if (kept.size == segments.size) return url

    val rebuilt = if (kept.isEmpty()) prefix else "$prefix?" + kept.joinToString("&")
    return rebuilt + fragment
}

/**
 * 判断当前加载的地址是不是带一次性自启载体的深链（含 `scanResult` 参数）。
 */
private fun hasDeepLinkAutoTrigger(url: String?): Boolean =
    !url.isNullOrBlank() && url.contains(DEEP_LINK_AUTO_START_PARAM)

/**
 * 判断是否是一卡通缴费页（需要切换为桌面/H5 UA）。
 *
 * 一卡通支付网关会按 UA 判定「官方移动端模块」：Android UA 返回 4030
 * 「安卓模块未授权(12)」，iOS UA 返回「IOS模块未授权(11)」；桌面/H5 UA 才会放行。
 * 缴费页不需要 SynATP 原生扫码，因此只在 /charge-app/ 与 /blade-pay/ 上切换。
 */
private fun isCampusCardPaymentUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    if (!uri.host.equals("yktfwpt.wbu.edu.cn", ignoreCase = true)) return false
    val path = uri.path.orEmpty()
    return path.startsWith("/charge-app") || path.startsWith("/blade-pay")
}

/**
 * 是否是一卡通缴费 SPA（`/charge-app/`）。
 *
 * 与 [isCampusCardPaymentUrl] 分开：那个还包含 `/blade-pay` 这个**接口**前缀，
 * 而令牌兜底脚本只该装到页面上（接口请求由页面自己发）。
 */
internal fun isCampusCardChargeAppUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val httpUrl = url.toHttpUrlOrNull() ?: return false
    if (!httpUrl.host.equals("yktfwpt.wbu.edu.cn", ignoreCase = true)) return false
    return httpUrl.encodedPath.startsWith("/charge-app")
}

/** `SynJSNative` 协议里「页面要求关闭自己」的主键（1 栋控水的「‹ 主页」按钮）。 */
internal const val PAGE_BACK_POP_KEY = "synjones.ecampus.window.pop"

/**
 * 当前地址是否应注入 lifeService 返回兜底脚本（[LIFE_SERVICE_BACK_SHIM_JS]）。
 *
 * 只有 2-3 栋洗浴这一页（`yktfwpt.wbu.edu.cn/applications/lifeService`）的顶栏箭头
 * 存在递归 bug；平台其它页面（`/plat/…`、`/applications/…` 的其它应用）返回语义正常，
 * 一概不注入，避免改变它们对 `child.back()` 的定制行为。
 * 真机深链不带 hash（`/applications/lifeService?_implid=…`），所以只看 host + path，
 * 不要求任何 query。
 */
internal fun isLifeServiceBackShimTarget(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val httpUrl = url.toHttpUrlOrNull() ?: return false
    if (!httpUrl.host.equals("yktfwpt.wbu.edu.cn", ignoreCase = true)) return false
    val path = httpUrl.encodedPath
    return path == "/applications/lifeService" || path.startsWith("/applications/lifeService/")
}

/**
 * 历史里「不算一页」的条目：页面自己的返回控件回退时应跳过。
 *
 * 深链进入一卡通各应用都要先落 `berserker-base/redirect` 换票据、再 302 到目标页，
 * 于是 WebView 历史里多出一条一次性跳转 —— 页面要求返回时若只回退一步，会退到这条
 * redirect 上又被 302 弹回来，表现成「返回键要按两次」（真机实测：马1 栋深链）。
 * 因此把 redirect、`about:blank` 与洗浴两个应用自身的页面都视作过渡条目：
 * 回退时跳到最近一个真实页面（通常是一卡通首页），全是过渡条目才退出容器。
 */
internal fun isTransientBackFlowEntry(url: String?): Boolean {
    if (url.isNullOrBlank() || url == "about:blank") return true
    val httpUrl = url.toHttpUrlOrNull() ?: return false
    if (httpUrl.host.equals("yktxyyy.wbu.edu.cn", ignoreCase = true)) return true
    if (httpUrl.host.equals("yktfwpt.wbu.edu.cn", ignoreCase = true)) {
        val path = httpUrl.encodedPath
        if (path.startsWith("/berserker-base/redirect")) return true
        return path == "/applications/lifeService" || path.startsWith("/applications/lifeService/")
    }
    return false
}


/**
 * 判断当前 URL 是否处于应用定义的「主页/首页」路由。
 */
internal fun isAtHomeRoute(currentUrl: String, def: WebAppDefinition?, ownHosts: Set<String>): Boolean {
    if (currentUrl.isBlank() || currentUrl == "about:blank") return true
    if (def == null) return false

    // 「首页」只对本应用自己的页面成立：门户里打开的业务系统（ehall / 入馆教育 / 超星 …）是站外页面，
    // 它们的 hash 空不空跟「门户首页」没有关系 —— 以前一刀切按「hash 为空 = 首页」判，
    // 结果在这些页面里按返回键会直接退出容器（实测反馈的问题）
    val host = currentUrl.toHttpUrlOrNull()?.host?.lowercase()
    if (host == null || host !in ownHosts) return false

    // 应用自己的页面上 hash 为空（或就是 `#/`）也算首页：hash 路由的单页应用刚加载完正是这个状态
    // （实测门户停在 `…/index.html#/`，标题就是「首页」）
    if (def.blankHashIsHome || def.homeHashMarkers.isEmpty()) {
        val hash = currentUrl.substringAfter("#", "")
        if (hash.isBlank() || hash == "/") return true
    }

    val markers = def.homeHashMarkers
    if (markers.isEmpty()) return false

    // 同时对整条 URL、Path 与 Hash 进行匹配（涵盖 Hash 路由与 History 路由如 /plat/shouyeUser）
    return markers.any { marker -> currentUrl.contains(marker, ignoreCase = true) }
}

/**
 * 本应用「自己人」的宿主集合：真实宿主 + 它在当前 WebVPN 配置下对应的那个代理宿主。
 *
 * 必须按 [WebAppDefinition.targetHost] 精确算，不能拿 `*.webvpn.wbu.edu.cn` 一把认：
 * 门户里打开的业务系统（ehall / 入馆教育 …）在校外同样跑在 `*-wbu-edu-cn.webvpn.wbu.edu.cn` 上，
 * 一把认会把它们当成门户自己的页面。
 */
private fun ownHostsOf(context: Context, def: WebAppDefinition?): Set<String> {
    if (def == null) return emptySet()
    val hosts = mutableSetOf(def.targetHost.lowercase())
    runCatching {
        WbuAuthTransport.getShared(context, true)
            .webVpnProxyBase(def.targetHost, def.vpnSingleSuffix)
            .toHttpUrlOrNull()
            ?.host
            ?.lowercase()
            ?.let { hosts.add(it) }
    }
    return hosts
}

/**
 * 地址里混进空白时把它修回正常地址（只处理「路径末尾」与「整条地址末尾」两处）。
 *
 * 实测（真机 + WebView 调试协议）：门户核销票据后给出的跳转目标偶尔是
 * `https://ydmh.wbu.edu.cn/ydmh ?v=<ts>`（`/ydmh` 与 `?` 之间多一个空格；同一接口另一次给的是
 * `…/ydmh/?v=<ts>`），Chromium 会把空格编码成 `%20`，请求因此变成 `…/ydmh%20?v=<ts>` ——
 * Tomcat 对路径 `/ydmh ` 直接 404（页面写着「请求的资源[/ydmh%20]不可用」），门户整页打不开。
 * 这里把这类多余空白抹掉后重新加载：只动空白，其它字符一个不碰。
 *
 * 只管两处，避免误伤合法地址：路径**中间**的 `%20` 是合法内容，一律不碰。
 *
 * @return 修正后的地址；无需修正时返回 null。
 */
internal fun repairStrayWhitespace(url: String): String? {
    if (url.isBlank()) return null
    val trailingWhitespace = Regex("(?i)(?:%20|%09|\\s)+$")

    val queryAt = url.indexOf('?')
    if (queryAt > 0) {
        // ① 路径末尾多出空白（`…/ydmh%20?v=…`）：只重写路径，查询原样保留
        val head = url.substring(0, queryAt)
        val fixedHead = head.replace(trailingWhitespace, "")
        if (fixedHead != head && fixedHead.toHttpUrlOrNull() != null) {
            return fixedHead + url.substring(queryAt)
        }
        return null
    }

    // ② 整条地址末尾多出空白（Location 头尾随空格）
    val fixed = url.replace(trailingWhitespace, "")
    return fixed.takeIf { it != url && it.toHttpUrlOrNull() != null }
}

/**
 * 把「本该 https、却被服务端 302 到 http」的地址抬回 https。
 *
 * 实测（2026-11-02 抓包）：移动门户核销票据后，`https://ydmh.wbu.edu.cn/ydmh/login/cas?ticket=…`
 * 回 302 到 **`http://ydmh.wbu.edu.cn/ydmh?v=…`**。这个域名在安恒云 WAF 上只接了 443，
 * 80 口直接回 `422 站点不存在`（`Server: DBAppWAF/2.1.1`），整页变成报错图 —— 连 `favicon.ico`
 * 都是同一张 422 页。
 *
 * 只在「应用自己配置的主页是 https」时才抬（见 [prefersHttpsOrigin]）：`yktfwpt` 这类本身
 * 就是 http 服务的站点不能动。
 *
 * @return 抬回 https 后的地址；无需修正（本来就是 https、或有显式非 80 端口）时返回 null。
 */
internal fun upgradeHttpToHttps(url: String): String? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http") return null
    // 显式非 80 端口（例如 WebVPN 的 :8118）不算「被降级到 http」，别碰
    if (parsed.port != 80) return null
    return parsed.newBuilder().scheme("https").port(443).build().toString()
}

/** 该应用配置的主页地址是不是 https —— 决定 http 跳转要不要抬回去。 */
internal fun prefersHttpsOrigin(realOrigin: String?): Boolean =
    realOrigin?.startsWith("https://", ignoreCase = true) == true

/**
 * 判断当前是否处于第三方 H5（如 U净）的首页路由 `#/home`。
 *
 * 只有这一页（OAuth 回调落地页）按返回键才跳回平台主页；
 * 其余第三方页面仍按 WebView 历史正常回退，避免一刀切。
 */
private fun isThirdPartyHomeRoute(currentUrl: String, def: WebAppDefinition?): Boolean {
    if (def == null || def.homeUrl.isNullOrBlank()) return false
    if (currentUrl.isBlank() || currentUrl == "about:blank") return false
    val host = runCatching { Uri.parse(currentUrl).host }.getOrNull() ?: return false
    if (host.equals(def.targetHost, ignoreCase = true)) return false
    val hash = currentUrl.substringAfter("#", "").substringBefore('?')
    return hash == "/home" || hash.startsWith("/home/")
}

/**
 * 判断当前是否处于第三方 H5（如 U净 洗衣机）扫码直达的起始/入口路由。
 * 包括：程序选择页 (`#/programV2`, `#/reserveV2`)、首页 (`#/home`)、扫码异常页 (`#/scanError`)、或根路由。
 * 用户在这些由全局扫码直达的界面按返回键时直接退出 WebApp 容器并返回 ClassFlow。
 */
private fun isThirdPartyEntryRoute(currentUrl: String, def: WebAppDefinition?): Boolean {
    if (currentUrl.isBlank() || currentUrl == "about:blank") return true
    val host = runCatching { Uri.parse(currentUrl).host }.getOrNull() ?: return true
    if (def != null && host.equals(def.targetHost, ignoreCase = true)) return false

    val hash = currentUrl.substringAfter("#", "").substringBefore('?')
    val entryRoutes = listOf("/programV2", "/reserveV2", "/home", "/scanError", "/createorder")
    return hash.isBlank() || hash == "/" || entryRoutes.any { hash == it || hash.startsWith("$it/") }
}

/**
 * 这个网页应用是不是从洗衣机（U净 / 小天鹅）入口进来的。
 *
 * 判据是导航参数里带的那条扫码原文 —— 只有洗衣机入口会带它（`/wm/{uuid}`、贴纸原文，
 * 或桌面快捷方式重建出来的小天鹅地址）。直接进一卡通门户再点进 U净 的情况不算，
 * 那种浏览用户本来就有自己的返回预期。
 *
 * 用 [UjingQrLink.parse] 而不是字符串匹配域名：扫码原文可能是被支付宝包装过的
 * `q.ujing.com.cn/…?uuid=` 形式，只认 `littleswan.com` 会漏掉。
 */
internal fun isWasherRaw(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    return UjingQrLink.parse(raw) is UjingQrLink.Result.Washer
}

/** 独立控水（马影河 1 栋「智能控水」）host。 */
private const val YKT_XYYY_HOST = "yktxyyy.wbu.edu.cn"

/** 当前 URL 是否处于智能控水页面。 */
private fun isYktXyyyUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return runCatching { Uri.parse(url).host.equals(YKT_XYYY_HOST, ignoreCase = true) }
        .getOrDefault(false)
}

/**
 * 智能控水（yktxyyy）深链自动执行脚本。
 *
 * 全局扫码分流已由服务端 `CheckKsPos` 正证过机号，进入 H5 后不应再要求用户点一次「扫一扫」：
 * 1. 轮询 Vue 组件树，找到带 `useWater` 且 `account` 就绪的主组件，
 *    按页面规则 `Number(raw.substr(8, 5))` 直接调 `useWater(posno, 'K')`，绕开页面自带的调试 alert；
 * 2. 兜底调用页面挂在 `window.scanCallback` 上的入口（临时屏蔽其调试 alert）；
 * 3. `__cfShowerAutoUsed` 保证同一页面上下文只执行一次。
 */
private fun buildYktShowerAutoUseJs(raw: String): String = """
(function() {
  if (window.__cfShowerAutoUsed) return;
  var raw = ${JSONObject.quote(raw)};
  var posno = Number(String(raw).substr(8, 5));
  if (!isFinite(posno) || posno <= 0) return;
  function find(vm) {
    if (!vm) return null;
    if (typeof vm.useWater === 'function') return vm;
    var kids = vm.${'$'}children || [];
    for (var i = 0; i < kids.length; i++) {
      var r = find(kids[i]);
      if (r) return r;
    }
    return null;
  }
  var tries = 0;
  (function tick() {
    if (window.__cfShowerAutoUsed) return;
    var root = document.querySelector('#app');
    var vm = root && root.__vue__;
    var target = vm ? find(vm) : null;
    if (target) {
      if (typeof target.account === 'string' && target.account.length > 0) {
        window.__cfShowerAutoUsed = true;
        try {
          target.useWater(String(posno), 'K');
        } catch (e) {
          window.__cfShowerAutoUsed = false;
        }
        return;
      }
      if (tries++ < 60) { setTimeout(tick, 300); return; }
    }
    if (typeof window.scanCallback === 'function') {
      window.__cfShowerAutoUsed = true;
      var oldAlert = window.alert;
      window.alert = function() {};
      try {
        window.scanCallback(raw);
      } catch (e) {
        window.__cfShowerAutoUsed = false;
      } finally {
        window.alert = oldAlert;
      }
      return;
    }
    if (tries++ < 60) setTimeout(tick, 300);
  })();
})();
""".trimIndent()

/** 判断是不是 U净 吹风机 H5（`hairdryer-h5/wechatWorkH5`）。 */
private fun isHairdryerUrl(url: String?): Boolean =
    url != null && url.contains("hairdryer-h5", ignoreCase = true)

/**
 * U净 吹风机设备页的「授权后再落路由」。
 *
 * 为什么不能直接把 `#/deviceSelector?deviceId=…` 拼在启动地址上：设备页是 `requireAuth` 路由，
 * 而页面的授权流程**只发生在 `/`（标题「授权中…」）那一步** —— 冷启动直接落在设备页时，
 * 路由守卫查不到令牌（`localStorage` 里的会话项），会把人扔到登录页，页面一个接口都不会发
 * （实测：`#/login?redirect=%2FdeviceSelector…`）。
 *
 * 所以这里只盯一件事：页面的会话令牌一出现（键名带项目名前缀，实测 `hairdryer-wechatWorkH5-token`）
 * 就把路由切过去，页面随后自己会去查
 * `controlBox/devices/{id}/info` 渲染左右机。授权没成功（平台会话真的过期）时它什么都不做，
 * 用户看到的就是页面原本的登录页。
 */
private fun buildHairdryerAutoRouteJs(route: String): String = """
(function() {
  var route = ${JSONObject.quote(route)};
  if (window.__cfHairdryerRouted === route) return;
  window.__cfHairdryerRouted = route;
  // 页面的存储是按项目名前缀的（实测键名 `hairdryer-wechatWorkH5-token`），
  // 所以这里扫一遍所有以 -token / -tempToken 结尾的键，不写死具体前缀
  function token() {
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k && /-(temp)?token$/.test(k) && localStorage.getItem(k)) return true;
      }
    } catch (e) {}
    return false;
  }
  function go() { if (location.hash !== route) location.hash = route; }
  var tries = 0;
  var timer = setInterval(function() {
    if (++tries > 150) { clearInterval(timer); return; }
    if (!token()) return;
    clearInterval(timer);
    // 页面自己在授权完成后也会 replace 到 /home，所以稍等再落，并补一次
    setTimeout(go, 400);
    setTimeout(function() { if (location.hash !== route) go(); }, 1600);
  }, 300);
})();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FullScreenWebContent(
    viewModel: WebAppViewModel,
    targetUrl: String,
    useVpn: Boolean,
    definition: WebAppDefinition?,
    platformToken: String?,
    pendingAutoScan: String? = null,
    /** 页面自己授权完成之后才落上去的 hash 路由（U净 吹风机设备页），见 [buildHairdryerAutoRouteJs]。 */
    pendingHashRoute: String? = null,
    onSslError: (SslErrorHandler, SslError) -> Unit,
    /** 网页被踢回统一认证登录页；[pageUrl] 是当前停留在的那一页（可能为 null），供自愈时回到原地。 */
    onWebAuthRedirect: (pageUrl: String?) -> Unit,
    /** 拦到了「需要微信授权」的目标（微信授权页 / 企业微信中转）：盖统一页。 */
    onWechatBlocked: (blockedUrl: String) -> Unit,
    /**
     * CAS 票据桥：用本机凭据给 [serviceUrl] 换一张票据并把落地地址交给 WebView。
     *
     * @param blockedUrl 非空 = 这次拦截来自企业微信中转，换票失败要回落到统一页（不是登录面板）。
     */
    onCasTicketNeeded: (serviceUrl: String, blockedUrl: String?) -> Unit,
    /** 旧一卡通链接改道（见 [CampusCardLegacyLink]）。 */
    onCampusCardRedirect: (CampusCardRedirect) -> Unit,
    onInterceptScan: (redirectUrl: String) -> Unit,
    onBridgeScan: (callbackId: String) -> Unit,
    onEmScan: (callbackId: String) -> Unit,
    onJsAgentScan: (callbackName: String) -> Unit,
    onAndroidFuncScan: (callbackName: String) -> Unit,
    /** 深链的一次性自启已经交给页面（见 [WebAppScreen] 的 [deepLinkAutoStarted] 说明）。 */
    onDeepLinkAutoStartHandedOff: () -> Unit = {},
    /** 盖着网页错误页：外层据此隐藏可拖拽退出球（它会压住错误页的按钮）。 */
    onPageErrorVisibleChanged: (Boolean) -> Unit = {},
    /**
     * 页面**自己**的返回控件（lifeService 顶栏箭头 / 1 栋控水「主页」按钮）要求返回、
     * 而 WebView 已无历史时调用 —— 通常等于退出容器。见 [pageBack] 的桥接说明。
     */
    onPageBackExit: () -> Unit = {},
    /**
     * 这个容器是「为某个具体动作开的一卡通任务」（例如取水页点「去充值」）。
     *
     * 为 true 时，页面一旦回到平台首页（`/plat/shouyeUser`）就认为**任务已经结束**，
     * 调 [onTaskFinished] 自动收起容器 —— 用户要的是回到发起任务的那一页，
     * 不是站在一卡通首页上再按一次返回。
     *
     * 注意这与「用户不充值直接返回」不冲突：直接返回走的是 BackHandler，
     * 那时页面还停在充值页（不是首页），根本不会命中这里。
     */
    taskEndOnPlatformHome: Boolean = false,
    /** 任务结束（见 [taskEndOnPlatformHome]）：收起这个容器。 */
    onTaskFinished: () -> Unit = {},
    onWebViewReady: (WebView) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var loadingProgress by remember { mutableFloatStateOf(0f) }

    // 主框架网络层失败（服务器压根没返回内容）：盖一层应用自己的错误页，
    // 替掉 WebView 内置那张绿色机器人页。HTTP 4xx/5xx 不会走到这里，那些页面原样展示。
    var pageError by remember { mutableStateOf<WebPageErrorInfo?>(null) }
    /** 用户点了「重新加载」：错误页继续挡着，直到新页面真的加载成功才撤。 */
    var reloadingAfterError by remember { mutableStateOf(false) }
    /** 本轮加载是否已经失败过：`onPageFinished` 在失败之后也会回调，不能拿它当「成功」的信号。 */
    var loadFailedThisRound by remember { mutableStateOf(false) }

    /**
     * 「任务容器」是否**已经离开过**平台首页。
     *
     * 一卡通平台进容器时可能先落首页、再一跳转到目标应用页（`/campus-card/?name=cardRecharge…`）。
     * 若只看「当前在首页」就收工，这一跳会把它掐死在半路 —— 用户根本还没看到充值页。
     * 所以要求「先离开首页，之后又回到首页」才算任务走完一轮。
     */
    var leftPlatformHomeOnce by remember { mutableStateOf(false) }

    var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            if (isGranted) {
                request.grant(request.resources)
            } else {
                request.deny()
            }
        }
    }

    var autoScanConsumed by remember(pendingAutoScan) { mutableStateOf(false) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback != null) {
            val uris = pickFileChooserUris(result.resultCode, result.data)
            Log.i("WebAppScreen", "网页文件选择回执：resultCode=${result.resultCode} 文件数=${uris?.size ?: 0}")
            callback.onReceiveValue(uris)
        } else {
            Log.w("WebAppScreen", "文件选择回执时没有挂起的回调（容器已经换过一轮？）")
        }
    }

    val defaultUserAgent = remember { WebSettings.getDefaultUserAgent(context) }
    // 一卡通平台 / U净 场景启用 wx 桩与原生桥（避免影响其它网页应用）
    val enableScanBridge = definition?.id == WebAppId.CAMPUS_CARD
    // 本应用自己的宿主（真实宿主 + WebVPN 代理宿主）：只修自己人的地址，别碰站外页面
    val ownHosts = remember(definition) { ownHostsOf(context, definition) }
    // 门户（uni-app）用 window.open 打应用：把它改成当前窗口跳转，见定义里的 shimWindowOpen 说明
    val shimWindowOpen = definition?.shimWindowOpen == true
    val customUserAgent = remember(defaultUserAgent, enableScanBridge) {
        // 当为一卡通平台时，追加 "SynATP E-Mobile" 使得：
        // 1. 新中新扩展应用（applications/lifeService）识别为 SynATP / TjtcApp，解锁原生扫码；
        // 2. 独立控水（yktxyyy.wbu.edu.cn:5001）识别为 TjtcApp，解锁 em.scanQRCode 原生扫码；
        // 3. 绝不追加 Synjones-E-Campus，保证 sessionStorage.agentType 保持为 "h5"。
        //
        // 但缴费页（/charge-app/）必须例外：一卡通的支付网关会按 UA 判定「官方移动端模块」，
        // 非官方 App 使用 Android UA 会被 401 拒绝（4030 安卓模块未授权(12)），iOS UA 则是 11。
        // 因此缴费页单独使用桌面/H5 UA，服务端会按普通 H5 放行。
        if (enableScanBridge && !defaultUserAgent.contains("SynATP")) {
            "$defaultUserAgent SynATP E-Mobile"
        } else {
            defaultUserAgent
        }
    }
    // 初始页面如果本身就是缴费页（如外部深链直达 /charge-app/），创建 WebView 时就要用 H5 UA
    val initialUserAgent = if (enableScanBridge && isCampusCardPaymentUrl(targetUrl)) {
        DESKTOP_USER_AGENT
    } else {
        customUserAgent
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // 网页定位桥（见 [WebAppGeolocationController]）：页面真要定位时才弹系统授权，
    // 没用到定位的页面不会被打扰。桥跟着导航条目走，与会话 WebView 同寿。
    val geoController = remember { viewModel.geolocationController() }
    val geoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 粗/精两个一起申请：允许任意一个就能出位置（网络定位只需要 COARSE）
        geoController.onPermissionResult(permissions.values.any { it })
    }

    // 网页原生 alert / confirm / prompt 的挂起队列（由 WebJsDialogHost 渲染成应用自己的弹窗）
    val jsDialogState = remember { WebJsDialogState() }

    // 网页弹窗标题：优先用应用名（一卡通 / 图书馆座位预约），没匹配到定义时才退回域名
    val jsDialogPageLabel: String? = definition?.let { context.getString(it.titleRes) }

    val webView = remember {
        // 会话 WebView（见 [WebAppViewModel.reusableSessionWebView]）：容器被别的页面盖住又回来时，
        // 挂回来的还是同一个 WebView —— 页面文档、选项卡、滚动位置全靠它。
        viewModel.reusableSessionWebView() ?: WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.javaScriptEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.textZoom = 100
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = initialUserAgent
            settings.mediaPlaybackRequiresUserGesture = false

            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            // 若处于 WebVPN 模式，向 CookieManager 注入 TWFID 以支持代理鉴权
            if (useVpn) {
                val twfid = WbuAuthTransport.getTwfid(context)
                if (twfid.isNotBlank()) {
                    cookieManager.setCookie("https://webvpn.wbu.edu.cn", "TWFID=$twfid; Path=/; Domain=.webvpn.wbu.edu.cn")
                    cookieManager.flush()
                }
            }

            /**
             * 页面自己的返回控件要求返回（`CFWebAppBridge.pageBack()` /
             * `SynJSNative` 的 `synjones.ecampus.window.pop`）。
             *
             * 与系统返回键「回退一步」不同：历史里躺着的一次性跳转条目
             * （`berserker-base/redirect` 票据跳转、`about:blank`、洗浴应用自身页面，
             * 见 [isTransientBackFlowEntry]）会被逐条跳过，直接落回最近一个真实页面
             * （通常是一卡通首页）—— 否则退到 redirect 上又被 302 弹回来，
             * 表现成「返回键要按两次」（真机实测：马1 栋深链）。全是跳转条目
             * （深链直达）才交给容器退出。
             */
            fun pageBack() {
                val list = copyBackForwardList()
                var index = list.currentIndex - 1
                while (index >= 0) {
                    val candidate = list.getItemAtIndex(index)?.url.orEmpty()
                    if (!isTransientBackFlowEntry(candidate)) {
                        Log.i("WebAppScreen", "Page back control: going back to $candidate")
                        goBackOrForward(index - list.currentIndex)
                        return
                    }
                    index--
                }
                Log.i("WebAppScreen", "Page back control: no real page behind, exiting container")
                onPageBackExit()
            }

            // 原生桥：供注入的 wx 桩、em 桩、JsAgent 以及 AndroidFunc 调用扫码与外跳支付
            if (enableScanBridge) {
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun scanQRCode(callbackId: String) {
                            mainHandler.post { onBridgeScan(callbackId) }
                        }

                        @JavascriptInterface
                        fun scanQRCodeEm(callbackId: String) {
                            mainHandler.post { onEmScan(callbackId) }
                        }

                        @JavascriptInterface
                        fun scanQRCodeJsAgent(callbackName: String) {
                            mainHandler.post { onJsAgentScan(callbackName) }
                        }

                        @JavascriptInterface
                        fun openExternal(url: String): Boolean {
                            val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
                            val allowed = setOf("weixin", "alipays", "alipay", "upwallet", "unionpay")
                            if (scheme == null || scheme !in allowed) {
                                Log.w("WebAppScreen", "openExternal rejected scheme=$scheme url=$url")
                                return false
                            }
                            return runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                                true
                            }.getOrElse {
                                Log.w("WebAppScreen", "openExternal failed: $url", it)
                                false
                            }
                        }

                        /**
                         * 页面自己的返回控件要求返回。2-3 栋 lifeService 页面的顶栏箭头在 H5 载体下
                         * 会递归调自身（`back()` → `child.back()` → `$parent.back()` → …，
                         * `RangeError` 被 Vue 吞掉），注入的返回兜底脚本（[LIFE_SERVICE_BACK_SHIM_JS]）
                         * 拦下箭头后改为调这里。
                         */
                        @JavascriptInterface
                        fun pageBack() {
                            mainHandler.post { pageBack() }
                        }
                    },
                    "CFWebAppBridge"
                )

                // 注入 AndroidFunc（支持传统水控或备用 SynJSNative 协议，不诱发 agentType 改变）
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun SynJSNative(jsonStr: String?): String {
                            Log.d("WebAppScreen", "AndroidFunc.SynJSNative invoked: $jsonStr")
                            if (jsonStr.isNullOrBlank()) return ""
                            try {
                                val json = JSONObject(jsonStr)
                                val primaryKey = json.optString("primaryKey")
                                val callback = json.optString("callback").ifBlank { "scanCallback" }
                                if (primaryKey.contains("scan", ignoreCase = true)) {
                                    mainHandler.post {
                                        onAndroidFuncScan(callback)
                                    }
                                    return JSONObject().apply {
                                        put("code", 200)
                                        put("message", "success")
                                    }.toString()
                                }
                                // 「synjones.ecampus.window.pop」：页面要求关闭自己（返回上一层）。
                                // 1 栋智能控水（yktxyyy:5001）的「‹ 主页」按钮走这里；历史里还有上一层
                                // 就回退，没有（深链首次直达）才交给容器退出 —— 与系统返回键语义一致。
                                if (primaryKey == PAGE_BACK_POP_KEY) {
                                    mainHandler.post { pageBack() }
                                    return JSONObject().apply {
                                        put("code", 200)
                                        put("message", "success")
                                    }.toString()
                                }
                            } catch (e: Exception) {
                                Log.w("WebAppScreen", "Failed to parse SynJSNative JSON", e)
                            }
                            return ""
                        }

                        @JavascriptInterface
                        fun invokeNativeMethod(methodName: String?, param: String? = null): String {
                            Log.d("WebAppScreen", "AndroidFunc.invokeNativeMethod invoked: $methodName, param: $param")
                            val callback = if (!methodName.isNullOrBlank() && !methodName.contains("invoke", ignoreCase = true)) {
                                methodName
                            } else "scanCallback"
                            mainHandler.post {
                                onAndroidFuncScan(callback)
                            }
                            return "success"
                        }
                    },
                    "AndroidFunc"
                )
            }

            // 网页定位桥：http 站点上 Blink 会硬拒绝 navigator.geolocation（非安全源），
            // 所以容器自己取位置、由注入的脚本把结果交给页面。所有网页应用都装，不限于一卡通。
            addJavascriptInterface(geoController, WebAppGeolocationScript.BRIDGE_NAME)

            webViewClient = object : WebViewClient() {
                /** 已经抬过 https 的地址：同一条 http 地址只抬一次，防止服务端来回 302 打成死循环。 */
                private val upgradedOnce = HashSet<String>()

                /**
                 * 根据主框架 URL 在「SynATP 移动 UA」与「桌面/H5 UA」之间切换。
                 *
                 * 一卡通缴费页 (/charge-app/) 必须使用 H5 UA：支付网关会按 UA 判定官方移动端模块，
                 * Android UA 会返回 4030「安卓模块未授权(12)」。其余页面保留移动 UA 以免影响原生扫码。
                 *
                 * 注意：只能在 onPageStarted / doUpdateVisitedHistory 中调用，绝不能在
                 * shouldOverrideUrlLoading 里调用——该回调中修改 userAgentString 后返回 false
                 * 会让当前这次跳转被 WebView 直接取消（表现为点应用图标没反应）。
                 */
                /**
                 * 根据主框架 URL 在「SynATP 移动 UA」与「桌面/H5 UA」之间切换。
                 *
                 * 一卡通缴费页 (/charge-app/) 必须使用 H5 UA：支付网关会按 UA 判定官方移动端模块，
                 * Android UA 会返回 4030「安卓模块未授权(12)」。其余页面保留移动 UA 以免影响原生扫码
                 * （平台首页靠 `SynATP E-Mobile` 解锁 `em.scanQRCode`，绝不能把桌面 UA 带过去）。
                 *
                 * 注意：只能在 onPageStarted / onPageFinished 中调用，绝不能在
                 * shouldOverrideUrlLoading 里调用——该回调中修改 userAgentString 后返回 false
                 * 会让当前这次跳转被 WebView 直接取消（表现为点应用图标没反应）。
                 * 更不能在 doUpdateVisitedHistory 里调用：缴费页 SPA 的 `replaceState`
                 * （抹掉地址栏令牌那一步）也会回调那里，此刻改 UA 会打断正在进行的缴费请求
                 * （见 [CHARGE_APP_PAY_TOKEN_JS_TEMPLATE] 的根因说明）。
                 */
                private fun applyCampusCardUserAgent(view: WebView?, url: String?) {
                    if (!enableScanBridge || url.isNullOrBlank()) return
                    val useDesktop = isCampusCardPaymentUrl(url)
                    val desired = if (useDesktop) DESKTOP_USER_AGENT else customUserAgent
                    if (view?.settings?.userAgentString != desired) {
                        Log.i("WebAppScreen", "Switch UA for $url -> ${if (useDesktop) "desktop" else "mobile"}")
                        view?.settings?.userAgentString = desired
                    }
                }

                /**
                 * 给缴费页补装令牌兜底脚本（幂等）。
                 *
                 * 只在 `/charge-app/` 上装：其它页面（`/plat/`、`/campus-card/`）的令牌链路
                 * 本来正常，不该被我们改写请求头。脚本内部还会再判一次「只在 `/blade-pay/`、
                 * `/blade-auth/` 且页面自己没给出有效令牌时」才补。
                 */
                private fun injectChargeAppPayToken(view: WebView?, url: String?) {
                    if (!enableScanBridge || !isCampusCardChargeAppUrl(url)) return
                    view?.evaluateJavascript(buildChargeAppPayTokenJs(platformToken), null)
                    // 脚本本身是幂等的（装过一次就不再装），所以每次回调再显式触发一次
                    // 「从地址栏捞令牌」：页面抹 query 的时机不确定，捞到就更新，捞不到保持原值。
                    view?.evaluateJavascript(
                        "(function(){try{if(window.__cfPayCapture)window.__cfPayCapture();}catch(e){}})();",
                        null
                    )
                }

                /**
                 * 地址里混进空白时，修回正常地址并加载（实测门户核销票据后的跳转目标
                 * `…/ydmh ?v=…`，Chromium 编码成 `…/ydmh%20?v=…` 后 Tomcat 直接 404）。
                 *
                 * 只对本应用自己的目标主机生效，避免误伤第三方页面里合法的 `%20`。
                 * 两处都接：`shouldOverrideUrlLoading`（能拦到就势拦下这次跳转）与
                 * `onPageStarted`（服务端 302 那种一跳到底、拦不到时，页面上手就换成修好的地址）。
                 *
                 * @return true = 已经改为加载修好的地址，调用方不要再管这次跳转。
                 */
                private fun repairUrlIfNeeded(view: WebView?, url: String?): Boolean {
                    if (url.isNullOrBlank()) return false
                    // 只管本应用自己的地址：真实宿主 + 它在 WebVPN 下的代理宿主
                    // （校外加载门户时落点正是 `ydmh-wbu-edu-cn-s.webvpn.wbu.edu.cn:8118/…`，
                    //  实测同一个 404 在直连与代理两个通道上都会出现）
                    val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
                    if (host !in ownHosts) return false

                    // ① 空白修补
                    val repaired = repairStrayWhitespace(url)
                    if (repaired != null) {
                        Log.w("WebAppScreen", "地址里混进空白，修回后重新加载：$url -> $repaired")
                        view?.loadUrl(repaired)
                        return true
                    }

                    // ② https 被服务端降级成 http：这个 http 口在云 WAF 上没接入，
                    //    实测直接回 422「站点不存在」（ydmh 的核销跳转就是这样）。
                    //    只在应用自己的主页是 https 时抬回去，http 服务（yktfwpt）不动。
                    if (prefersHttpsOrigin(definition?.realOrigin)) {
                        val upgraded = upgradeHttpToHttps(url)
                        // 防循环：服务端如果坚持把 https 再 302 回同一个 http 地址，
                        // 再抬一次就会 https→http→https 无限打转。同一条 http 地址只抬一次。
                        if (upgraded != null && upgradedOnce.add(url)) {
                            Log.w("WebAppScreen", "跳转被降级到 http，抬回 https 后重新加载：$url -> $upgraded")
                            view?.loadUrl(upgraded)
                            return true
                        }
                    }

                    return false
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false

                    // -1. 地址里混进空白：修回正常地址再加载
                    if (repairUrlIfNeeded(view, url)) return true

                    // 0. 旧一卡通微信服务链接（yktyd.wbu.edu.cn 的 wechat 路径下）改道新平台。
                    //    这些链接点开必然 302 到企业微信 OAuth（回调回 yktyd 自身，客户端伪造不了），
                    //    在 App 里只能落到微信报错页；新平台（CAMPUS_CARD）已支持免密换票，
                    //    因此这里直接改道，并落到与新平台功能对应的那个页面（见 CampusCardLegacyLink）。
                    //    已经站在一卡通容器里时不改道，防自我跳转。
                    if (definition?.id != WebAppId.CAMPUS_CARD) {
                        val redirect = CampusCardLegacyLink.parse(url)
                        if (redirect != null) {
                            Log.i("WebAppScreen", "旧一卡通链接改道：$url -> $redirect")
                            onCampusCardRedirect(redirect)
                            return true
                        }
                    }

                    // 1. 微信自己的授权页：在 App 里打开只会渲染微信的「请在微信客户端打开链接」报错页，
                    //    而这一步客户端代替不了 —— 换成我们自己的统一页（提示 + 复制链接 + 返回）
                    if (WebAppWechat.isWechatAuthorizeUrl(url)) {
                        Log.i("WebAppScreen", "拦到需要微信授权的目标：$url")
                        onWechatBlocked(url)
                        return true
                    }

                    // 2. 统一身份认证的企业微信中转（weixinQYLogin.do?qyId=…&success=…）：
                    //    它要的其实只是一张 CAS 票据（success 就是一个 CAS 登录地址），
                    //    手里有 CASTGC 就直接把后半段走完，用户不必去微信转一圈。
                    //    解析不出 success / success 不是 CAS 登录地址时才动统一页
                    if (WebAppWechat.isWeixinQyLoginUrl(url)) {
                        val success = WebAppWechat.parseSuccessTarget(url)
                        val service = success?.let { WebAppWechat.casServiceOf(it) }
                        when {
                            service != null -> {
                                Log.i("WebAppScreen", "跳过企业微信中转，直接换票：service=$service")
                                onCasTicketNeeded(service, url)
                            }

                            success != null -> {
                                Log.i("WebAppScreen", "企业微信中转无需 CAS，直接落到 success：$success")
                                view?.loadUrl(success)
                            }

                            else -> {
                                Log.i("WebAppScreen", "企业微信中转没有可用的 success，弹统一页：$url")
                                onWechatBlocked(url)
                            }
                        }
                        return true
                    }

                    // 3. 跳向统一身份认证登录页。两种情况：
                    //    · 业务系统在正常要一张票据（门户里的 ehall / zxst 都这样）→ 票据桥就地换票；
                    //    · 这一页手里的会话真的失效了 → 交回 ViewModel 用本机凭据自救，
                    //      救不回来它才把需要登录的状态交给 UI 弹原生登录窗。
                    //    （核心要求不变：坚决不让 WebView 展示统一身份认证的 Web 登录页面）
                    if (WebAppWechat.isCasLoginUrl(url)) {
                        val service = if (definition?.casTicketBridge == true && url.contains("/authserver/login")) {
                            WebAppWechat.casServiceOf(url)
                        } else null

                        if (service != null) {
                            Log.i("WebAppScreen", "CAS 票据桥：为 $service 换票")
                            onCasTicketNeeded(service, null)
                        } else {
                            Log.i("WebAppScreen", "Intercepted navigation to CAS login: $url (page=${view?.url})")
                            onWebAuthRedirect(view?.url)
                        }
                        return true
                    }

                    // 4. 针对 U净 / 平台扫一扫：页面跳转 /plat/scan?redirectUrl=... 时拦截，并拉起原生扫码
                    if (url.contains("/plat/scan")) {
                        val parsed = Uri.parse(url)
                        val redirect = parsed.getQueryParameter("redirectUrl").orEmpty()
                        if (redirect.isNotBlank()) {
                            Log.i("WebAppScreen", "Intercepted /plat/scan with redirectUrl: $redirect")
                            onInterceptScan(redirect)
                            return true
                        }
                    }

                    // 5. U净 → 平台 OAuth：给 authorize 补上平台身份，让平台把 code 回调给 U净 页面
                    if (enableScanBridge &&
                        !platformToken.isNullOrBlank() &&
                        url.contains("/berserker-auth/oauth/authorize") &&
                        !url.contains("synjones-auth=")
                    ) {
                        val separator = if (url.contains("?")) "&" else "?"
                        val encodedToken = java.net.URLEncoder.encode("bearer $platformToken", "UTF-8")
                        val authedUrl = "$url${separator}synjones-auth=$encodedToken"
                        Log.i("WebAppScreen", "Appending synjones-auth to OAuth authorize navigation")
                        this@apply.loadUrl(authedUrl)
                        return true
                    }

                    // 6. 拦截微信支付（weixin://）、支付宝（alipays://）及其他第三方自定义 URI Scheme
                    val uri = Uri.parse(url)
                    val scheme = uri.scheme?.lowercase()
                    if (scheme != null && scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data" && scheme != "javascript") {
                        Log.i("WebAppScreen", "Handling external app scheme: $url")
                        return runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            true
                        }.getOrElse {
                            Log.w("WebAppScreen", "Failed to launch intent for scheme $scheme", it)
                            val appName = when {
                                scheme.startsWith("weixin") -> context.getString(R.string.label_wechat)
                                scheme.startsWith("alipay") -> context.getString(R.string.label_alipay)
                                scheme.startsWith("upwallet") -> context.getString(R.string.label_unionpay)
                                else -> scheme
                            }
                            Toast.makeText(context, context.getString(R.string.format_no_app_handle_request, appName), Toast.LENGTH_SHORT).show()
                            true
                        }
                    }

                    return false
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    onSslError(handler, error)
                }

                /**
                 * 主框架网络层失败（连不上 / 超时 / DNS / SSL / 服务器直接断开…）。
                 *
                 * 只有「服务器压根没返回内容」才会回调这里；服务端真回了 4xx/5xx 页面走的是
                 * [onReceivedHttpError]，我们**不接管** —— 那些页面带着服务端自己的提示，抢过来只会丢信息。
                 * 这里把失败信息留下来，由 Compose 盖一层自己的错误页，替掉 WebView 内置的绿色机器人页。
                 */
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request == null || error == null) return
                    // 子框架（iframe）里的小广告挂掉不该把整页判死
                    if (!request.isForMainFrame) return
                    val failedUrl = request.url?.toString()
                    // aborted（-3）是导航被新页面顶掉，不是真失败；about:/data: 不是真页面
                    if (!isRealWebLoadFailure(failedUrl, error.errorCode)) return
                    Log.w(
                        "WebAppScreen",
                        "主框架加载失败：$failedUrl code=${error.errorCode} desc=${error.description}"
                    )
                    pageError = WebPageErrorInfo(
                        url = failedUrl?.takeIf { it.isNotBlank() } ?: view?.url,
                        code = error.errorCode,
                        description = error.description?.toString()
                    )
                    loadFailedThisRound = true
                    // 又失败了：把主按钮的转圈停下，让用户能再点一次
                    reloadingAfterError = false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // 新一轮加载开始：上一轮失败记录作废。但不是立刻撤掉错误页 ——
                    // 用户点「重新加载」时，先露出的往往是 WebView 还没渲染好的空白/旧页，
                    // 等 onPageFinished 确认这次没失败再撤（见下面的 pageError 处理）。
                    loadFailedThisRound = false
                    // 兜底：服务端 302 直接跳到「路径里混进空格」的地址时上头那处拦不到，
                    // 页面刚上手就换成修好的地址（修好的地址不会再触发，不会成环）
                    if (repairUrlIfNeeded(view, url)) return
                    applyCampusCardUserAgent(view, url)
                    // 缴费页令牌兜底尽早装上：页面 getToken() 在启动早期就会抹掉地址栏 query
                    injectChargeAppPayToken(view, url)
                    // 定位接管脚本尽早装上（幂等）：页面在 load 阶段就定位的也别漏
                    injectGeolocationShim(view)
                    // 门户：把 window.open 改成当前窗口跳转（幂等），否则点应用可能毫无反应
                    if (shimWindowOpen) {
                        view?.evaluateJavascript(WINDOW_OPEN_SHIM_JS, null)
                    }
                    // 2-3 栋 lifeService：顶栏箭头递归 bug 的返回兜底（幂等，见脚本说明）
                    if (isLifeServiceBackShimTarget(url)) {
                        view?.evaluateJavascript(LIFE_SERVICE_BACK_SHIM_JS, null)
                    }
                    // document 早期注入 wx 桩（幂等），保证 U净 扫码/支付桥可用
                    if (enableScanBridge) {
                        view?.evaluateJavascript(WX_STUB_JS, null)
                        if (!pendingAutoScan.isNullOrBlank() && !autoScanConsumed) {
                            autoScanConsumed = true
                            val autoJs = """
                                (function() {
                                    window.__cfAutoScan = ${JSONObject.quote(pendingAutoScan)};
                                    try {
                                        sessionStorage.setItem('scanResult', ${JSONObject.quote(pendingAutoScan)});
                                    } catch (e) {}
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(autoJs, null)
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 这一次真的加载成功了：撤掉上次留下的错误页（失败的话 loadFailedThisRound 是 true，
                    // 且 onPageFinished 在失败回调之后也会来一次，不能拿它当成功信号）。
                    if (!loadFailedThisRound) {
                        pageError = null
                        reloadingAfterError = false
                    }
                    applyCampusCardUserAgent(view, url)
                    // 缴费页令牌兜底再装一次（部分机型 onPageStarted 时 JS 上下文尚未就绪；
                    // 页面也可能在这一轮里已经抹掉了地址栏令牌）
                    injectChargeAppPayToken(view, url)
                    injectGeolocationShim(view)
                    // lifeService 返回兜底同理再装一次（部分机型 onPageStarted 时 JS 上下文尚未就绪）
                    if (isLifeServiceBackShimTarget(url)) {
                        view?.evaluateJavascript(LIFE_SERVICE_BACK_SHIM_JS, null)
                    }
                    // 本次加载是否把「一次性自启」交给了页面
                    var autoStartHandedOff = false
                    // 门户：window.open 兜底再注入一次（部分机型 onPageStarted 时 JS 上下文尚未就绪），
                    // 也覆盖「门户跳走后又回到门户」这类情况
                    if (shimWindowOpen) {
                        view?.evaluateJavascript(WINDOW_OPEN_SHIM_JS, null)
                    }
                    // 兜底再注入一次（部分机型 onPageStarted 时 JS 上下文尚未就绪）
                    if (enableScanBridge) {
                        view?.evaluateJavascript(WX_STUB_JS, null)
                        if (!pendingAutoScan.isNullOrBlank() && !autoScanConsumed) {
                            autoScanConsumed = true
                            autoStartHandedOff = true
                            val autoJs = """
                                (function() {
                                    window.__cfAutoScan = ${JSONObject.quote(pendingAutoScan)};
                                    try {
                                        sessionStorage.setItem('scanResult', ${JSONObject.quote(pendingAutoScan)});
                                    } catch (e) {}
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(autoJs, null)
                        }
                        // 智能控水（1 栋洗浴）：深链已带票据，页面就绪后自动进入设备页
                        if (!pendingAutoScan.isNullOrBlank() && isYktXyyyUrl(url)) {
                            view?.evaluateJavascript(buildYktShowerAutoUseJs(pendingAutoScan), null)
                            autoStartHandedOff = true
                        }
                        // U净 吹风机：设备页是受保护路由，得等页面自己授权完再切过去
                        if (!pendingHashRoute.isNullOrBlank() && isHairdryerUrl(url)) {
                            view?.evaluateJavascript(buildHairdryerAutoRouteJs(pendingHashRoute), null)
                        }
                    }
                    // 通知外层「这个条目的深链自启已经用完」：它存进保存状态，
                    // 于是进程被回收后重建时不再重放（页面重新挂载就会再开一单）
                    if (autoStartHandedOff || hasDeepLinkAutoTrigger(url)) {
                        onDeepLinkAutoStartHandedOff()
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    // SPA 内部 pushState/replaceState 会回调这里。这里**不改 UA**：
                    // 页面抹掉地址栏令牌用的正是 replaceState，此刻改 userAgentString 会打断
                    // 正在进行的缴费请求（详见 applyCampusCardUserAgent 的说明）。
                    // 反过来，这里是最该补注入的时机：页面刚要把 query 里的令牌抹掉，
                    // 让兜底脚本抢在它前面把令牌落到 sessionStorage。
                    injectChargeAppPayToken(view, url)

                    // 「有目的的一卡通任务」跳回平台首页 = 任务结束，自动收起这个容器。
                    //
                    // 实测路径（真机 2026-11-07）：取水页欠费 → 点「去充值」→ 微信支付 →
                    // 支付结果页点「完成」→ 一卡通首页。此时用户要的是「回到我的取水页接着结算」，
                    // 而不是站在一卡通首页。不收起的话，他得先按一次返回退出容器（还要碰运气
                    // 命中「首页即退出」的判据），再指望那一页自己活着 —— 实测那次直接掉回了课表。
                    //
                    // 两个条件都不能省：
                    // - **只在首页触发**：这顺带保证了「用户不充值直接返回」不受影响 ——
                    //   那时页面还停在充值页，BackHandler 先接住，压根走不到这里。
                    // - **必须先离开过首页**（[leftPlatformHomeOnce]）：进容器时可能先落首页
                    //   再一跳转到充值页，少了这条会把那次跳转掐死在半路，用户根本看不到充值页。
                    if (taskEndOnPlatformHome && !isReload && url != null) {
                        if (!isAtHomeRoute(url, definition, ownHosts)) {
                            leftPlatformHomeOnce = true
                        } else if (leftPlatformHomeOnce) {
                            Log.w("WebAppScreen", "任务容器回到平台首页，自动收起：$url")
                            onTaskFinished()
                        }
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    // U净 washer-h5 的 index.html 必定加载 jweixin（见研究笔记第 16 节）。
                    // 非微信环境里它没有 WeixinJSBridge：config 静默失败、ready/success/fail 都不回调，
                    // 既会覆盖注入的 wx 桩，又让扫码按钮"点了没反应"。这里直接吞掉 jweixin-*.js，
                    // 让注入的 wx 桩始终生效（water-h5 不受影响）。
                    val reqUrl = request?.url?.toString().orEmpty()
                    if (enableScanBridge &&
                        reqUrl.contains("jweixin", ignoreCase = true) &&
                        reqUrl.substringBefore('?').endsWith(".js", ignoreCase = true)
                    ) {
                        Log.i("WebAppScreen", "Blocked jweixin script: $reqUrl")
                        return WebResourceResponse(
                            "application/javascript",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    // 子框架（iframe）里的跳转不走 shouldOverrideUrlLoading，这里兜一层：
                    // 命中微信授权页 / 企业微信中转且不是主框架时，置出统一页状态并回一个空响应，
                    // 免得 iframe 里渲染出微信自己的报错页。
                    // （子框架里不做票据桥：那要把主框架整体导航过去，等于把人从门户里踢出去）
                    if (request != null && !request.isForMainFrame && WebAppWechat.looksLikeWechatGate(reqUrl) &&
                        (WebAppWechat.isWechatAuthorizeUrl(reqUrl) || WebAppWechat.isWeixinQyLoginUrl(reqUrl))
                    ) {
                        Log.i("WebAppScreen", "子框架里拦到需要微信的目标：$reqUrl")
                        mainHandler.post { onWechatBlocked(reqUrl) }
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    // 当在 WebVPN 模式下，若页面 JS 内部写死了公网真实域名发起接口请求，将其自动重写为代理宿主请求
                    if (useVpn && definition != null) {
                        val reqUri = request?.url ?: return null
                        if (reqUri.host.equals(definition.targetHost, ignoreCase = true)) {
                            val transport = WbuAuthTransport.getShared(context, true)
                            val proxyBase = transport.webVpnProxyBase(definition.targetHost, withSingleSuffix = definition.vpnSingleSuffix)
                            val proxyUri = Uri.parse(proxyBase)
                            val rewritten = reqUri.buildUpon()
                                .scheme(proxyUri.scheme)
                                .encodedAuthority(proxyUri.encodedAuthority)
                                .build()
                            Log.d("WebAppScreen", "Rewrote subresource to VPN proxy: $reqUri -> $rewritten")
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loadingProgress = newProgress / 100f
                }

                // ── 劫持网页原生对话框：返回 true 表示由我们自己弹窗 ──
                // 页面 JS 会阻塞到 JsResult 被落定为止（与系统弹窗行为一致），
                // 因此这里只入队，落定交给 WebJsDialogHost 的按钮 / 页面销毁兜底。

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (message == null || result == null) {
                        return super.onJsAlert(view, url, message, result)
                    }
                    Log.i("WebAppScreen", "接管网页 alert: $message @$url")
                    jsDialogState.enqueue(
                        WebJsDialogRequest.Alert(
                            pageLabel = jsDialogPageLabel ?: webDialogPageLabel(url ?: view?.url),
                            message = message,
                            onConfirm = { result.confirm() },
                        )
                    )
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (message == null || result == null) {
                        return super.onJsConfirm(view, url, message, result)
                    }
                    Log.i("WebAppScreen", "接管网页 confirm: $message @$url")
                    jsDialogState.enqueue(
                        WebJsDialogRequest.Confirm(
                            pageLabel = jsDialogPageLabel ?: webDialogPageLabel(url ?: view?.url),
                            message = message,
                            onConfirm = { result.confirm() },
                            onCancel = { result.cancel() },
                        )
                    )
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?
                ): Boolean {
                    if (message == null || result == null) {
                        return super.onJsPrompt(view, url, message, defaultValue, result)
                    }
                    Log.i("WebAppScreen", "接管网页 prompt: $message @$url")
                    jsDialogState.enqueue(
                        WebJsDialogRequest.Prompt(
                            pageLabel = jsDialogPageLabel ?: webDialogPageLabel(url ?: view?.url),
                            message = message,
                            defaultValue = defaultValue.orEmpty(),
                            onConfirmInput = { input -> result.confirm(input) },
                            onCancel = { result.cancel() },
                        )
                    )
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    val resources = request.resources
                    val needsCamera = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

                    if (needsCamera) {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            request.grant(resources)
                        } else {
                            pendingPermissionRequest = request
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    } else {
                        request.grant(resources)
                    }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                    if (pendingPermissionRequest == request) {
                        pendingPermissionRequest = null
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallbackInternal: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = filePathCallbackInternal

                    return runCatching {
                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        fileChooserLauncher.launch(intent)
                        true
                    }.getOrElse {
                        Log.w("WebAppScreen", "拉起文件选择器失败", it)
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = null
                        false
                    }
                }
            }

            loadUrl(targetUrl)
        }.also { viewModel.holdSessionWebView(it) }
    }

    LaunchedEffect(webView) {
        // 定位桥每次挂回来都要重新绑一次：容器被别的页面盖住过时换的是容器，不是 WebView，
        // 而桥上的回调（跳系统授权）属于「当前这一个」容器。
        geoController.bind(webView) { permissions -> geoPermissionLauncher.launch(permissions) }
        // 挂回来的页面（可能早就加载完、脚本也跑过了）也要把接管脚本补上
        injectGeolocationShim(webView)
        // 挂回来的可能是一个已经加载完的页面（容器被别的页面盖住过）：它不会再回调进度，
        // 不从 WebView 自己的进度补一下，顶部进度条就会永远停在 0
        if (webView.progress >= 100) loadingProgress = 1f
        onWebViewReady(webView)
    }

    DisposableEffect(webView) {
        onDispose {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            pendingPermissionRequest?.deny()
            pendingPermissionRequest = null

            // 先把还挂着的网页对话框按「取消」落定，否则页面 JS 会永久卡住
            jsDialogState.cancelAll()

            // 定位桥解绑：这一轮容器走了，挂起的定位请求不能再等（页面侧还有 25 秒兜底超时）
            geoController.release()

            // 注意：这里**不销毁、也不清空** WebView。
            //
            // 容器卸载有两种情况：一是用户真的退出去了，二是被别的页面压住（门户里点开一卡通充值 /
            // 付款码，压进来的是原生页或另一个 WebApp）。两种情况的卸载长得一模一样，而第二种要把
            // 这一页原样端回来 —— 页面文档、选项卡、滚动位置都只在 WebView 自己的内存里。
            //
            // 所以销毁与清理统一挪到 [WebAppViewModel.onCleared]：导航条目真正从回退栈弹出时才走，
            // 退出时的清理（缓存 / 历史 / WebStorage）与以前一致。
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> FrameLayout(ctx) },
            update = { holder -> holder.claimSessionWebView(webView) }
        )

        // 网页原生 alert / confirm / prompt：用应用自己的弹窗替代系统样式
        WebJsDialogHost(jsDialogState)

        // 主框架网络层失败：盖掉 WebView 内置的绿色机器人页，换成应用自己的错误页。
        // 会话 WebView 不销毁，点「重新加载」就是 reload()，历史与登录态都还在。
        pageError?.let { error ->
            WebPageErrorView(
                info = error,
                retrying = reloadingAfterError,
                onReload = {
                    // 错误页继续挡着：等 onPageFinished 确认这次加载成功再撤，
                    // 否则重载过程中会先露出 WebView 里那张绿机器人页。
                    reloadingAfterError = true
                    webView.reload()
                },
                onCopyUrl = {
                    copyToClipboard(context, error.url.orEmpty())
                    Toast.makeText(
                        context,
                        context.getString(R.string.web_error_url_copied),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onOpenInBrowser = error.url?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { url ->
                    {
                        openInExternalBrowser(context, url)
                    }
                },
                onBack = onPageBackExit
            )
        }

        if (pageError == null && loadingProgress < 1.0f) {
            LinearProgressIndicator(
                progress = { loadingProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }
    }

    // 让外层把可拖拽退出球收起来：错误页自带「返回」，不被悬浮球压住
    LaunchedEffect(pageError != null) {
        onPageErrorVisibleChanged(pageError != null)
    }
    DisposableEffect(Unit) {
        onDispose { onPageErrorVisibleChanged(false) }
    }
}

/**
 * 把会话 WebView「认领」到当前容器上。
 *
 * 不能直接 `AndroidView(factory = { webView })`：一个 View 只能有一个父容器，而容器换页时
 * 新旧两帧可能同时要求持有它（导航条目回来得比退场动画快的时候），`addView` 会直接抛
 * 「child already has a parent」。所以外面套一层 FrameLayout，挂之前先把 WebView 从上一个
 * 容器上摘下来 —— 同一个 WebView 因此在容器之间搬来搬去，页面本身不受影响。
 */
private fun FrameLayout.claimSessionWebView(webView: WebView) {
    if (webView.parent === this) return
    (webView.parent as? ViewGroup)?.removeView(webView)
    addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}

/**
 * 装上网页定位接管脚本（见 [WebAppGeolocationScript]）。
 *
 * 脚本自己幂等（装过就退出、桥不在也退出），所以三处都注：`onPageStarted`（页面 load 阶段
 * 就调用定位的页面）、`onPageFinished`（部分机型 `onPageStarted` 时 JS 上下文尚未就绪）、
 * 以及容器挂回来时（页面可能早就跑完了）。
 */
private fun injectGeolocationShim(view: WebView?) {
    view?.evaluateJavascript(WebAppGeolocationScript.JS, null)
}

/**
 * 可拖拽悬浮退出球。
 */
@Composable
private fun DraggableExitFab(
    onExit: () -> Unit
) {
    val density = LocalDensity.current
    val fabSize = 44.dp
    val fabSizePx = with(density) { fabSize.toPx() }
    val marginPx = with(density) { 16.dp.toPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxXPx = constraints.maxWidth - fabSizePx - marginPx
        val maxYPx = constraints.maxHeight - fabSizePx - marginPx

        // 默认停靠在右侧中下部 (约屏幕 70% 高度处)，避开顶部导航栏、右上方个人中心卡片/头像以及底部手势条
        var offsetX by remember { mutableFloatStateOf(maxXPx) }
        var offsetY by remember { mutableFloatStateOf((maxYPx * 0.7f).coerceIn(marginPx, maxYPx)) }

        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(fabSize)
                .shadow(elevation = 6.dp, shape = CircleShape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(marginPx, maxXPx)
                        offsetY = (offsetY + dragAmount.y).coerceIn(marginPx, maxYPx)
                    }
                },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            IconButton(
                onClick = onExit,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_exit),
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 校园网检测浮层，支持随时中断并自主决策直接进入或走 WebVPN。
 */
@Composable
private fun CampusNetworkProbeOverlay(
    statusText: String,
    isOffCampus: Boolean = false,
    onSelectCampus: () -> Unit,
    onSelectVpn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "campusProbe")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = if (isOffCampus) 1.0f else 0.75f,
        targetValue = if (isOffCampus) 1.0f else 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = if (isOffCampus) 0.3f else 0.12f,
        targetValue = if (isOffCampus) 0.3f else 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(140.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            alpha = pulseAlpha
                        }
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            shape = CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .graphicsLayer {
                            scaleX = pulseScale * 0.85f
                            scaleY = pulseScale * 0.85f
                            alpha = pulseAlpha * 0.8f
                        }
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = CircleShape
                        )
                )
                Surface(
                    shape = CircleShape,
                    color = if (isOffCampus) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(60.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isOffCampus) Icons.Default.VpnKey else Icons.Default.Wifi,
                            contentDescription = null,
                            tint = if (isOffCampus) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = stringResource(if (isOffCampus) R.string.title_off_campus_detected else R.string.title_campus_network_detect),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (!isOffCampus) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(180.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(if (isOffCampus) R.string.hint_off_campus_choose_access else R.string.hint_choose_access_method_direct),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // WebVPN Card (校外模式下优先置顶或突出高亮)
            OutlinedCard(
                onClick = onSelectVpn,
                shape = RoundedCornerShape(16.dp),
                colors = if (isOffCampus) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f)) else CardDefaults.outlinedCardColors(),
                border = if (isOffCampus) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.secondary)) else CardDefaults.outlinedCardBorder(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.label_webvpn_off_campus),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (isOffCampus) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                ) {
                                    Text(
                                        text = stringResource(R.string.label_recommended),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.desc_webvpn_guide),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 校园网直连 Card
            OutlinedCard(
                onClick = onSelectCampus,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_campus_direct_on_campus),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.desc_campus_direct_guide),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}


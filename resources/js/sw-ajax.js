"use strict"; // http://ejohn.org/blog/ecmascript-5-strict-mode-json-and-more/



/// swHandleError ///
/////////////////////

function swHandleError(jq_xhr, text_status, error_thrown){
  if(jq_xhr.status == 200){
    $.sticky("<b>SymbolicWeb</b><p>Client side error.</p><p>Check <b>console.error</b> for details.</p><p>Developers have _not_ (TODO: fix) been notified of this event.</p>");
    console.error([error_thrown, jq_xhr.responseText, jq_xhr]);
  }
}



/// swGetCurrentHash ///
////////////////////////

var swGetCurrentHash;
if($.browser.mozilla)
  swGetCurrentHash = function(){
    if((window.location.hash).length > 1)
      // https://bugzilla.mozilla.org/show_bug.cgi?id=378962 *sigh*
      return "#" + window.location.href.split("#")[1].replace(/%27/g, "'");
    else
      return "#";
  };
else
  swGetCurrentHash = function(){
    return location.hash;
  };



/// swAddOnLoadFN ///
/////////////////////

var swAddOnLoadFN, swDoOnLoadFNs;
(function (){
   var funs = new Array();

   swAddOnLoadFN = function(fun){
     funs.push(fun);
   };

   swDoOnLoadFNs = function(){
     for(var fun in funs){
       try {
         funs[fun]();
       }
       catch(err){
         console.error("swDoOnLoadFNs:\n" + funs[fun]);
         console.error(err);
       }
     }
   };
 })();



/// swURL ///
/////////////

var swURL, swDURL;

(function (){
   var fn = function(dynamic_p, params){
     var res = [window.location.protocol, "//",
                (function(){ if(dynamic_p) return(_sw_dynamic_subdomain); else return(""); })(),
                window.location.host,
                // TODO: Document this.
                (function(){
                   var str = "";
                   for(var p = window.location.pathname.split("/"), i = 0; i < (p.length - 1); i++)
                     if(p[i] != "")
                       str += "/" + p[i];
                   return str;
                 })(),
                "?_sw_viewport_id=", _sw_viewport_id,
                params.join("")
               ].join("");
     return(res);
   };

   // Request; not vs. random sub-domain.
   swURL = function(params){
     return fn(false, params);
   };

   // Request (but jQuery will use <script> tag background loading); vs. random sub-domain.
   swDURL = function(params){
     return fn(true, params);
   };})();



/// swAjax ///
//////////////

var swAjax =
  (function(){
     var queue = new Array();
     var timer = false;

     function displaySpinner(){
       $("#sw-loading-spinner").css("display", "block");
     }

     function handleRestOfQueue(){
       queue.shift();
       if(queue.length != 0)
         queue[0]();
       else{
         if(timer){
           clearTimeout(timer);
           timer = false;
           $("#sw-loading-spinner").css("display", "none");
         }
       }
     }

     return function(params, callback_data, after_fn){
       if(queue.push(function(){
                       var url = swURL(["&_sw_request_type=ajax", params]);
                       var options = {
                         type: (function(){
                                  // http://bit.ly/1z3xEu
                                  // MAX for GET is apparently 2048 (IE). We stay a bit below this just in case.
                                  //console.log(callback_data.length + url.length);
                                  if(callback_data.length + url.length > 1950)
                                    return "POST";
                                  else
                                    return "GET";
                                })(),
                         url: url,
                         data: callback_data,
                         cache: false,
                         dataType: "script",
                         beforeSend: function(){ if(!timer){ timer = setTimeout(displaySpinner, 500); }}, // TODO: 500 should be configurable.
                         error: function(jq_xhr, text_status, thrown_error){
                           $.sticky("<b>SymbolicWeb: HTTP 500</b><br/>Something went wrong. Check <b>console.error</b> for details.<br/><br/>Developers have been notified of this event.");
                           handleRestOfQueue();
                         },
                         complete: handleRestOfQueue
                       };

                       if(after_fn) options.success = after_fn;

                       $.ajax(options);
                     }) == 1) // if()..
         queue[0]();
     };
   })();



/// swComet ///
///////////////

var _sw_comet_response = false;
var _sw_comet_last_response_ts = new Date().getTime();

var swComet  =
  (function(){
     function callback(){
       if(_sw_comet_response){
         _sw_comet_last_response_ts = new Date().getTime();
         _sw_comet_response = false;
         swComet("&do=ack");
       }
       else
         setTimeout("swComet('&do=timeout');", 1000);
     }

     function doIt(params){
       $.ajax({type: "GET",
               url: swURL(["&_sw_request_type=comet", params]),
               dataType: "script",
               cache: false,
               error: function(jq_xhr, text_status, error_thrown){
                 swHandleError(jq_xhr, text_status, error_thrown);
               },
               complete: callback});
     }

     if($.browser.mozilla || $.browser.webkit)
       // Stops "throbbing of doom".
       return function(params){ setTimeout(function(){ doIt(params); }, 0); };
     else
       return doIt;
   })();



/// swHandleEvent ///
/////////////////////

function swHandleEvent(callback_id, js_before, callback_data, js_after){
  if(js_before())
    swAjax("&_sw_event=dom-event&_sw_callback-id=" + callback_id,
           callback_data,
           js_after());
}



/// swMsg ///
/////////////

function swMsg(widget_id, callback_id, js_before, callback_data, js_after){
  if(js_before())
    swAjax("&_sw_event=dom-event" + "&_sw_widget-id=" + widget_id + "&_sw_callback-id=" + callback_id,
           callback_data,
           js_after());
}



/// swTerminateSession ///
//////////////////////////

function swTerminateSession(){
  swAjax("&_sw_event=terminate-session", "", function(){ window.location.reload(); });
}



/// swReturnValue ///
/////////////////////

function swReturnValue(code_id, func){
  swAjax("&event=js-ack&code-id=" + code_id,
         "&return-value=" + encodeURIComponent(func()));
}



/// swReturnFail ///
////////////////////

function swReturnFail(code_id, exception){
  swAjax("&event=js-fail&code-id=" + code_id,
         "&exception-str=" + encodeURIComponent(exception.toString()));
}



/// swRun ///
/////////////

function swRun(code_id, async_p, func){
  try{
    if(async_p)
      func();
    else
      swReturnValue(code_id, func);
  }
  catch(exception){
    swReturnFail(code_id, exception);
  }
}



/// Shutdown ///
/////////////////

// So unload event fires on (some, but still not all ..sigh) navigation in Opera too.
if(typeof(opera) != 'undefined'){
  opera.setOverrideHistoryNavigationMode('compatible');
  history.navigationMode = 'compatible';
}

// GC the server side Viewport object and all its Widgets (and their incomming connections from the Model/DB) on page unload.
$(window).on("unload", function(){
  $.ajax({type: "GET",
          url: swURL(["&_sw_request_type=ajax", ["&do=unload"]]),
          cache: false,
          async: false});
});






/// Boot! ///
/////////////

function swBoot(url){
  // Pre-boot; sets _sw_viewport_id etc..
  $.ajax({
    async: false,
    type: "GET",
    url: url,
    dataType: "script",
    success: function(){ 
      // At this point pre-boot and all context (variables etc) is good to go so we connect our background channel..
      swComet("&do=refresh");
      // ..and set up something that'll ensure the channel stays alive 
      // when faced with JS dying after computer waking up from suspend etc..
      var sw_mouse_poll_ts = new Date().getTime();
      var sw_mouse_poll_interval_ms = 5000;
      var sw_comet_timeout_window_ms = 5000; // Response time window after long poll timeout.
      $(document).on("mousemove", function(e){
        var ts = new Date().getTime();
        if((ts - sw_mouse_poll_ts) > sw_mouse_poll_interval_ms){
          sw_mouse_poll_ts = ts;
          if((ts - _sw_comet_last_response_ts) > (_sw_comet_timeout_ts + sw_comet_timeout_window_ms)){
            console.log("SymbolicWeb: Client connection JS-loop has died: rebooting...");
            window.location.href = window.location.href;
          }
        }
      });
    }});
}


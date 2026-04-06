// Main app entry point - load all dependencies
import htmx from 'htmx.org';
import _hyperscript from 'hyperscript.org';
import { computePosition, flip, shift, offset } from '@floating-ui/dom';

// Expose libraries globally
window.htmx = htmx;
window._hyperscript = _hyperscript;

// Initialize hyperscript
_hyperscript.browserInit();

console.log('Bundle loaded!', { htmx: window.htmx, hyperscript: window._hyperscript });

// Tooltip positioning with Floating UI
window.positionTooltip = async function(triggerElement) {
  // Store tooltip reference on the trigger element if we haven't already
  let tooltip = triggerElement._tooltip;

  if (!tooltip) {
    // Look for tooltip as a sibling in the parent container
    tooltip = triggerElement.parentElement.querySelector('.tooltip');
    if (!tooltip) {
      console.warn('No tooltip found in', triggerElement.parentElement);
      return;
    }

    // Store reference and move to body once
    triggerElement._tooltip = tooltip;

    // Move tooltip to body for proper fixed positioning
    if (tooltip.parentElement !== document.body) {
      document.body.appendChild(tooltip);
    }
  }

  // Update position every time (trigger might have moved)
  console.log("trigger-element", triggerElement)
  const { x, y } = await computePosition(triggerElement, tooltip, {
    placement: 'top',
    middleware: [
      offset(8),
      flip(),
      shift({ padding: 8 })
    ]
  });

  Object.assign(tooltip.style, {
    left: `${x}px`,
    top: `${y}px`,
  });
};

console.log('positionTooltip function registered');

// Register service worker for PWA installability
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js');
}

// =============================================================================
// Push notification helpers (called from hyperscript on the settings page)
// =============================================================================

function urlBase64ToUint8Array(base64String) {
  var padding = '='.repeat((4 - base64String.length % 4) % 4);
  var base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  var rawData = atob(base64);
  var outputArray = new Uint8Array(rawData.length);
  for (var i = 0; i < rawData.length; i++) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray;
}

window.shouldShowPushButton = async function() {
  if (!('PushManager' in window)) return false;
  if (!('serviceWorker' in navigator)) return false;
  var isStandalone = window.matchMedia('(display-mode: standalone)').matches
                     || navigator.standalone === true;
  if (!isStandalone) return false;
  if (typeof Notification !== 'undefined' && Notification.permission === 'denied') return false;
  try {
    var reg = await navigator.serviceWorker.ready;
    var sub = await reg.pushManager.getSubscription();
    return sub === null;
  } catch (e) {
    return false;
  }
};

window.subscribePush = async function(vapidPublicKey, subscribeUrl) {
  try {
    var permission = await Notification.requestPermission();
    if (permission !== 'granted') {
      console.warn('Push notification permission denied');
      return false;
    }

    var reg = await navigator.serviceWorker.ready;
    var existing = await reg.pushManager.getSubscription();
    if (existing) return true;

    var sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(vapidPublicKey)
    });

    await fetch(subscribeUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(sub.toJSON())
    });
    return true;
  } catch (e) {
    console.error('Push subscription failed:', e);
    return false;
  }
};

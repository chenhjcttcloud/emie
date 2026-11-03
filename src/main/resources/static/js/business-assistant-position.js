const EMIE = window.EMIE;
const POSITION_KEY = 'design_pm_assistant_position';
const MARGIN = 12, PANEL_GAP = 10, DRAG_THRESHOLD = 5;
const attachedWidgets = new WeakMap();
const clamp = (value, min, max) => Math.min(Math.max(value, min), Math.max(min, max));

function storedPosition() {
  try {
    const position = JSON.parse(localStorage.getItem(POSITION_KEY));
    return position && Number.isFinite(position.x) && Number.isFinite(position.y)
      ? { x: position.x, y: position.y } : null;
  } catch { return null; }
}

function viewport() {
  const visual = window.visualViewport;
  return {
    left: visual?.offsetLeft || 0, top: visual?.offsetTop || 0,
    width: visual?.width || document.documentElement.clientWidth,
    height: visual?.height || window.innerHeight,
  };
}

function attachAssistantPosition(widget) {
  if (attachedWidgets.has(widget)) return attachedWidgets.get(widget);
  const launcher = widget.querySelector('#businessAssistantLauncher');
  const panel = widget.querySelector('#businessAssistantPanel');
  if (!launcher || !panel) return () => {};
  let position = storedPosition(), drag = null, suppressClickUntil = 0, scheduled = false;

  function placeLauncher(x, y, bounds) {
    launcher.style.width = 'max-content';
    launcher.style.maxWidth = `${Math.max(0, bounds.width - MARGIN * 2)}px`;
    const rect = launcher.getBoundingClientRect();
    position = {
      x: clamp(x, bounds.left + MARGIN, bounds.left + bounds.width - MARGIN - rect.width),
      y: clamp(y, bounds.top + MARGIN, bounds.top + bounds.height - MARGIN - rect.height),
    };
    Object.assign(launcher.style, { left: `${position.x}px`, top: `${position.y}px`, right: 'auto', bottom: 'auto' });
  }

  function refresh() {
    if (!widget.isConnected || widget.hidden) return;
    const bounds = viewport();
    if (position) placeLauncher(position.x, position.y, bounds);
    if (panel.hidden) return;
    const button = launcher.getBoundingClientRect();
    const above = Math.max(0, button.top - bounds.top - MARGIN - PANEL_GAP);
    const below = Math.max(0, bounds.top + bounds.height - MARGIN - button.bottom - PANEL_GAP);
    const opensAbove = above >= below;
    const fullHeight = Math.max(0, bounds.height - MARGIN * 2);
    const available = opensAbove ? above : below;
    // A launcher near the middle must not squeeze the composer out of the panel.
    const overlapsLauncher = available < Math.min(360, fullHeight);
    Object.assign(panel.style, {
      maxWidth: `${Math.max(0, bounds.width - MARGIN * 2)}px`,
      height: overlapsLauncher ? `${fullHeight}px` : '',
      minHeight: '0', maxHeight: `${overlapsLauncher ? fullHeight : available}px`, right: 'auto', bottom: 'auto',
    });
    const rect = panel.getBoundingClientRect();
    const x = clamp(button.right - rect.width, bounds.left + MARGIN, bounds.left + bounds.width - MARGIN - rect.width);
    const y = opensAbove ? button.top - PANEL_GAP - rect.height : button.bottom + PANEL_GAP;
    panel.style.left = `${x}px`;
    panel.style.top = `${clamp(y, bounds.top + MARGIN, bounds.top + bounds.height - MARGIN - rect.height)}px`;
  }

  function scheduleRefresh() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => { scheduled = false; refresh(); });
  }

  function finishDrag(event) {
    if (!drag || event.pointerId !== drag.pointerId) return;
    const moved = drag.moved;
    drag = null;
    launcher.classList.remove('is-dragging');
    try { if (launcher.hasPointerCapture(event.pointerId)) launcher.releasePointerCapture(event.pointerId); } catch { /* capture may already be lost */ }
    if (moved) {
      suppressClickUntil = performance.now() + 1000;
      try { localStorage.setItem(POSITION_KEY, JSON.stringify(position)); } catch { /* position still works without storage */ }
    }
  }

  launcher.addEventListener('pointerdown', event => {
    if (drag || event.isPrimary === false || event.button !== 0) return;
    suppressClickUntil = 0;
    const rect = launcher.getBoundingClientRect();
    drag = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, left: rect.left, top: rect.top, moved: false };
    try { launcher.setPointerCapture(event.pointerId); } catch { /* ignore unavailable capture */ }
  });
  launcher.addEventListener('pointermove', event => {
    if (!drag || event.pointerId !== drag.pointerId) return;
    const dx = event.clientX - drag.x, dy = event.clientY - drag.y;
    if (!drag.moved && Math.hypot(dx, dy) <= DRAG_THRESHOLD) return;
    drag.moved = true;
    launcher.classList.add('is-dragging');
    event.preventDefault();
    placeLauncher(drag.left + dx, drag.top + dy, viewport());
    refresh();
  });
  ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(name => launcher.addEventListener(name, finishDrag));
  launcher.addEventListener('click', event => {
    if (event.detail !== 0 && performance.now() < suppressClickUntil) {
      event.preventDefault(); event.stopImmediatePropagation(); suppressClickUntil = 0;
    }
  }, true);
  launcher.title ||= '拖动可调整位置，点击打开小觅';
  window.addEventListener('resize', scheduleRefresh);
  window.visualViewport?.addEventListener('resize', scheduleRefresh);
  window.visualViewport?.addEventListener('scroll', scheduleRefresh);
  attachedWidgets.set(widget, refresh);
  refresh();
  return refresh;
}

EMIE.registerModule('businessAssistantPosition', { attachAssistantPosition });

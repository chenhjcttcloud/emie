// EMIE 声明式事件运行时：代替 HTML 内联 on* 属性，并支持后续动态渲染的内容。
const EMIE = window.EMIE;
const compiledEventHandlers = new Map();
const registeredEventHandlers = new Map();
const forbiddenEventSource = /\b(?:constructor|__proto__|prototype|window|globalThis|Function|eval|import|fetch|XMLHttpRequest|WebSocket|localStorage|sessionStorage|cookie)\b/i;

function validateEventSource(source) {
  if (forbiddenEventSource.test(source)) throw new Error('事件动作包含禁止内容');
  const withoutAllowedDocumentCalls = source.replaceAll('document.getElementById', '');
  if (/\bdocument\b/.test(withoutAllowedDocumentCalls)) throw new Error('事件动作使用了未授权的 document 能力');
}

function createActionScope(event) {
  const allowed = Object.assign(Object.create(null), EMIE.actions, { event, document });
  return new Proxy(allowed, {
    has: () => true,
    get: (target, key) => key === Symbol.unscopables ? undefined : target[key],
  });
}

function compileEventHandler(source) {
  if (compiledEventHandlers.has(source)) return compiledEventHandlers.get(source);
  validateEventSource(source);
  // 属性内容只来自版本化静态模板，且只能访问注册动作和有限 DOM 能力。
  const handler = new Function('scope', `with (scope) { return (function () { ${source}\n }).call(this); }`);
  compiledEventHandlers.set(source, handler);
  return handler;
}

function findEventElement(event, attributeName) {
  if (!(event.target instanceof Element)) return null;
  if (event.type === 'click') return event.target.closest(`[${attributeName}]`);
  return event.target.hasAttribute(attributeName) ? event.target : null;
}

// 安全事件入口：模板只保存不透明 action key，不保存可执行 JavaScript。
function registerEventAction(key, handler) {
  if (typeof key !== 'string' || !/^[a-z][a-z0-9:_-]{1,120}$/i.test(key)) {
    throw new Error('事件 action key 非法');
  }
  if (typeof handler !== 'function') throw new TypeError('事件处理器必须是函数');
  registeredEventHandlers.set(key, handler);
  return () => registeredEventHandlers.delete(key);
}

function findRegisteredEventElement(event) {
  if (!(event.target instanceof Element)) return null;
  const attribute = event.type === 'keydown' ? '[data-emie-keydown-action]' : '[data-emie-action]';
  return event.target.closest(attribute);
}

function dispatchRegisteredEvent(event) {
  const element = findRegisteredEventElement(event);
  if (!element) return false;
  const descriptor = element.getAttribute(event.type === 'keydown' ? 'data-emie-keydown-action' : 'data-emie-action') || '';
  const separator = descriptor.indexOf(':');
  if (separator <= 0) return false;
  const eventName = descriptor.slice(0, separator);
  const key = descriptor.slice(separator + 1);
  if (eventName !== event.type) return false;
  const handler = registeredEventHandlers.get(key);
  if (!handler) return false;
  const result = handler.call(element, event, element);
  if (result === false) event.preventDefault();
  return true;
}

function dispatchDeclarativeEvent(event) {
  dispatchRegisteredEvent(event);
}

['click', 'change', 'input', 'submit', 'keydown'].forEach(eventName => {
  document.addEventListener(eventName, dispatchDeclarativeEvent);
});

EMIE.registerActions({
  registerEventAction,
  validateEventSource,
  createActionScope,
  compileEventHandler,
  findEventElement,
  dispatchDeclarativeEvent,
});

EMIE.registerModule('eventRuntime', {
  registerEventAction,
  dispatchDeclarativeEvent,
});

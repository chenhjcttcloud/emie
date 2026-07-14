// EMIE 声明式事件运行时：代替 HTML 内联 on* 属性，并支持后续动态渲染的内容。
const EMIE = window.EMIE;
const compiledEventHandlers = new Map();
const forbiddenEventSource = /\b(?:constructor|__proto__|prototype|window|globalThis|Function|eval|import|fetch|XMLHttpRequest|WebSocket|localStorage|sessionStorage|cookie)\b/;

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

function dispatchDeclarativeEvent(event) {
  const attributeName = `data-emie-on${event.type}`;
  const element = findEventElement(event, attributeName);
  if (!element) return;
  const source = element.getAttribute(attributeName);
  if (!source) return;
  const result = compileEventHandler(source).call(element, createActionScope(event));
  if (result === false) event.preventDefault();
}

['click', 'change', 'input', 'submit'].forEach(eventName => {
  document.addEventListener(eventName, dispatchDeclarativeEvent);
});

EMIE.registerActions({
  validateEventSource,
  createActionScope,
  compileEventHandler,
  findEventElement,
  dispatchDeclarativeEvent,
});

EMIE.registerModule('eventRuntime', {
  dispatchDeclarativeEvent,
});

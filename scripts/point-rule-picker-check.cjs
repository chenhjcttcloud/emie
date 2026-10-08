const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/static/js/project-tasks.js', 'utf8');
const context = vm.createContext({ window: { EMIE: { actions: { escHtml: value => String(value).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;') } } } });
vm.runInContext(source.slice(0, source.indexOf('function pointRuleCategoryHint')), context);
const rules = Array.from({ length: 23 }, (_, index) => ({
  ruleCode: `D30_${index + 1}`,
  category: index < 20 ? '产品设计' : '包装设计',
  subcategory: index < 20 ? (index % 2 ? '概念' : '落地') : null,
  description: `测试任务${index + 1}`,
  points: 2,
}));
context.rules = rules;
const html = vm.runInContext('renderPointRulePicker(rules)', context);
assert.match(html, /data-point-category-tab="产品设计"/);
assert.match(html, /data-point-search/);
assert.match(html, /共 23 条 · 1 \/ 3 页/);
assert.equal((html.match(/data-point-rule-code="D30_/g) || []).length, 10);
assert.doesNotMatch(html, /difficultyMultiplier/);
const selected = vm.runInContext("renderPointRulePicker(rules, 'D30_2')", context);
assert.match(selected, /data-point-subcategory-tab="概念"/);
console.log('point rule picker checks passed');
